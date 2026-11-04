#!/usr/bin/env python
"""GPU 잡에 올릴 묶음(tgz) 하나를 만든다 (docs/embedding-finetune.md §5, 묶음 E). 표준 라이브러리만 쓴다.

학교 GPU는 웹 터미널로만 닿아서 파일을 올리고 내려받는 왕복이 비싸다. 그래서 학습 코드·설정·데이터를 한 파일로 묶고,
안에 SHA256SUMS를 넣어 GPU 쪽(gpu_run.sh start)이 잘리거나 바뀐 파일로 한 시간을 돌리지 않게 한다.

  ef-bundle/
    gpu_run.sh  train.py  train_core.py  requirements-train.txt
    SHA256SUMS                      자기 자신을 뺀 모든 파일의 해시 ("<해시>  <상대 경로>", sha256sum -c 형식)
    data/pairs-train.jsonl  pairs-val.jsonl  pairs-manifest.json

같은 입력이면 같은 tgz가 나온다(항목 정렬, mtime·uid·gid·모드 고정, gzip 헤더에 시각·파일 이름 없음). 올린 파일이 맞는지
앞 8자만 눈으로 대조해도 되게 하려는 것이다. 쓴 뒤에는 파일을 다시 열어 SHA256SUMS와 대조한다.

실행:
  eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/make_gpu_bundle.py
  → eval/embedding_finetune/data/gpu-bundle-<날짜>.tgz  (data/는 gitignore)

테스트: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import argparse
import gzip
import hashlib
import io
import os
import sys
import tarfile
from datetime import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DATA_DIR = os.path.join(HERE, "data")

BUNDLE_DIR = "ef-bundle"
SUMS_NAME = "SHA256SUMS"
CODE_FILES = ("gpu_run.sh", "train.py", "train_core.py", "requirements-train.txt")
DATA_FILES = ("pairs-train.jsonl", "pairs-val.jsonl", "pairs-manifest.json")
EXECUTABLE = frozenset({"gpu_run.sh"})


class BundleError(Exception):
    """입력 파일이 없거나, 만든 묶음이 SHA256SUMS와 맞지 않는다."""


def read_inputs(code_dir: str, data_dir: str) -> dict[str, bytes]:
    """묶음 안 상대 경로 -> 내용. 파일을 한 번만 읽어 그 바이트로 해시도 계산하므로, 도중에 파일이 바뀌어도 어긋나지 않는다."""
    sources = [(name, os.path.join(code_dir, name)) for name in CODE_FILES]
    sources += [(f"data/{name}", os.path.join(data_dir, name)) for name in DATA_FILES]
    missing = [path for _, path in sources if not os.path.isfile(path)]
    if missing:
        raise BundleError("입력 파일이 없습니다:\n  " + "\n  ".join(missing))
    members = {}
    for rel, path in sources:
        with open(path, "rb") as f:
            members[rel] = f.read()
    return members


def sums_text(members: dict[str, bytes]) -> str:
    """SHA256SUMS 본문. `sha256sum -c`가 ef-bundle/ 안에서 읽는 형식이라 경로는 묶음 기준 상대 경로다."""
    return "".join(f"{hashlib.sha256(data).hexdigest()}  {rel}\n" for rel, data in sorted(members.items()))


def _info(name: str, *, size: int = 0, is_dir: bool = False, mode: int) -> tarfile.TarInfo:
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE if is_dir else tarfile.REGTYPE
    info.size = 0 if is_dir else size
    info.mode = mode
    info.mtime = 0
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    return info


def write_tgz(members: dict[str, bytes], out_path: str) -> None:
    files = dict(members)
    files[SUMS_NAME] = sums_text(members).encode("utf-8")

    # 이름순으로 넣는다. 디렉터리는 자기 하위 항목보다 문자열로 앞서므로 풀 때 먼저 만들어진다
    entries = {BUNDLE_DIR: None, f"{BUNDLE_DIR}/data": None}
    entries.update({f"{BUNDLE_DIR}/{rel}": data for rel, data in files.items()})

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    tmp = f"{out_path}.tmp"
    with open(tmp, "wb") as raw:
        # filename=""이 없으면 gzip이 출력 파일 이름을 헤더에 적어, 같은 내용이어도 저장 위치에 따라 바이트가 달라진다
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, compresslevel=9, mtime=0) as gz:
            with tarfile.open(fileobj=gz, mode="w", format=tarfile.GNU_FORMAT) as tar:
                for name in sorted(entries):
                    data = entries[name]
                    if data is None:
                        tar.addfile(_info(name, is_dir=True, mode=0o755))
                        continue
                    executable = name.rsplit("/", 1)[-1] in EXECUTABLE
                    tar.addfile(_info(name, size=len(data), mode=0o755 if executable else 0o644), io.BytesIO(data))
    os.replace(tmp, out_path)


def verify_tgz(path: str) -> int:
    """tgz를 다시 열어 SHA256SUMS와 대조한다. 맞으면 SHA256SUMS가 다루는 파일 수를 돌려준다."""
    with tarfile.open(path, "r:gz") as tar:
        contents = {}
        for member in tar.getmembers():
            if member.isfile():
                contents[member.name] = tar.extractfile(member).read()

        sums_member = f"{BUNDLE_DIR}/{SUMS_NAME}"
        if sums_member not in contents:
            raise BundleError(f"{sums_member}가 묶음에 없습니다")
        listed = {}
        for line in contents[sums_member].decode("utf-8").splitlines():
            digest, rel = line.split("  ", 1)
            listed[rel] = digest

        problems = []
        for rel, digest in listed.items():
            data = contents.get(f"{BUNDLE_DIR}/{rel}")
            if data is None:
                problems.append(f"SHA256SUMS에 있는 {rel}가 묶음에 없습니다")
            elif hashlib.sha256(data).hexdigest() != digest:
                problems.append(f"{rel}의 해시가 SHA256SUMS와 다릅니다")
        extra = sorted(set(contents) - {f"{BUNDLE_DIR}/{rel}" for rel in listed} - {sums_member})
        problems += [f"SHA256SUMS에 없는 파일이 묶음에 있습니다: {name}" for name in extra]
        required = set(CODE_FILES) | {f"data/{name}" for name in DATA_FILES}
        problems += [f"필수 파일이 SHA256SUMS에 없습니다: {rel}" for rel in sorted(required - set(listed))]
        if problems:
            raise BundleError("\n  ".join(problems))
        return len(listed)


def build_bundle(data_dir: str, out_path: str, code_dir: str = HERE) -> dict:
    members = read_inputs(code_dir, data_dir)
    write_tgz(members, out_path)
    file_count = verify_tgz(out_path)
    digest = hashlib.sha256()
    with open(out_path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return {"path": os.path.abspath(out_path), "size": os.path.getsize(out_path), "sha256": digest.hexdigest(),
            "files": file_count}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="GPU 잡에 올릴 묶음(tgz)을 만든다")
    ap.add_argument("--data-dir", default=DEFAULT_DATA_DIR, help="pairs-*.jsonl · pairs-manifest.json이 있는 폴더")
    ap.add_argument("--out", help="만들 tgz 경로 (기본: <data-dir>/gpu-bundle-<YYYYMMDD>.tgz)")
    args = ap.parse_args(argv)
    out = args.out or os.path.join(args.data_dir, f"gpu-bundle-{datetime.now():%Y%m%d}.tgz")

    try:
        info = build_bundle(args.data_dir, out)
    except BundleError as exc:
        sys.exit(f"묶음을 만들지 못했습니다: {exc}")

    name = os.path.basename(info["path"])
    print("묶음을 만들었습니다.")
    print(f"  경로   : {info['path']}")
    print(f"  크기   : {info['size'] / 1e6:.1f} MB ({info['size']:,} bytes)")
    print(f"  sha256 : {info['sha256']}")
    print(f"  앞 8자 : {info['sha256'][:8]}")
    print(f"  검사   : 파일 {info['files']}개가 SHA256SUMS와 맞습니다")
    print()
    print("GPU 웹 터미널에서, 이 파일을 올린 폴더에 가서 한 줄로 실행합니다. 앞 8자가 위와 같은지 눈으로 보세요.")
    print(f"  sha256sum {name} | cut -c1-8 && tar xzf {name} && bash {BUNDLE_DIR}/gpu_run.sh start")
    return 0


if __name__ == "__main__":
    sys.exit(main())
