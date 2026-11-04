"""embedding_finetune/make_gpu_bundle.py와 gpu_run.sh의 묶음 계약 테스트 (오프라인 — GPU·네트워크 불필요).

묶음은 웹 터미널로 올리고 GPU에서 한 시간 넘게 돌리는 파일 하나다. 여기가 틀리면 잘린 파일로 학습이 시작되거나,
빠진 파일이 GPU에서야 드러나거나, 같은 입력인데 해시가 달라 올린 파일이 맞는지 눈으로 대조할 수 없다.
gpu_run.sh는 bash 문법과 묶음 검사 함수(verify_bundle)만 시험한다 — 전체 흐름은 Mac에서 작은 모델로 돌려 확인한다
(eval/embedding_finetune/README.md).

실행: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import hashlib
import io
import os
import re
import shutil
import subprocess
import sys
import tarfile

import pytest

EMBEDDING_FINETUNE_DIR = os.path.abspath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "embedding_finetune"))
sys.path.insert(0, EMBEDDING_FINETUNE_DIR)

import make_gpu_bundle as mgb  # noqa: E402
import train_core  # noqa: E402

GPU_RUN = os.path.join(EMBEDDING_FINETUNE_DIR, "gpu_run.sh")
needs_bash = pytest.mark.skipif(shutil.which("bash") is None, reason="bash가 없다")
needs_bash_and_sha = pytest.mark.skipif(
    shutil.which("bash") is None or not (shutil.which("sha256sum") or shutil.which("shasum")),
    reason="bash 또는 sha256sum/shasum이 없다")

EXPECTED_NAMES = [
    "ef-bundle",
    "ef-bundle/SHA256SUMS",
    "ef-bundle/data",
    "ef-bundle/data/pairs-manifest.json",
    "ef-bundle/data/pairs-train.jsonl",
    "ef-bundle/data/pairs-val.jsonl",
    "ef-bundle/gpu_run.sh",
    "ef-bundle/requirements-train.txt",
    "ef-bundle/train.py",
    "ef-bundle/train_core.py",
]


@pytest.fixture
def inputs(tmp_path):
    """묶음에 들어갈 가짜 코드·데이터 폴더. 내용은 파일마다 다르게 둔다(해시가 서로 달라야 대조가 의미 있다)."""
    code = tmp_path / "code"
    data = tmp_path / "data"
    code.mkdir()
    data.mkdir()
    for name in mgb.CODE_FILES:
        (code / name).write_text(f"# {name}\n", encoding="utf-8")
    for name in mgb.DATA_FILES:
        (data / name).write_text(f'{{"file": "{name}", "text": "한글"}}\n', encoding="utf-8")
    return code, data


def build(inputs, tmp_path, name="bundle.tgz"):
    code, data = inputs
    return mgb.build_bundle(str(data), str(tmp_path / name), code_dir=str(code))


def read_members(path):
    with tarfile.open(path, "r:gz") as tar:
        return {m.name: (m, tar.extractfile(m).read() if m.isfile() else None) for m in tar.getmembers()}


# ─── 구조 ──────────────────────────────────────────────────────────────────────


def test_bundle_has_the_expected_layout(inputs, tmp_path):
    info = build(inputs, tmp_path)

    members = read_members(info["path"])
    assert list(members) == EXPECTED_NAMES   # 이름순 — 디렉터리가 하위 파일보다 먼저라 풀 때 먼저 만들어진다
    assert members["ef-bundle"][0].isdir() and members["ef-bundle/data"][0].isdir()


def test_real_code_files_exist_where_the_bundle_expects_them():
    for name in mgb.CODE_FILES:
        assert os.path.isfile(os.path.join(EMBEDDING_FINETUNE_DIR, name)), name


def test_sha256sums_lists_every_file_except_itself_in_sha256sum_format(inputs, tmp_path):
    info = build(inputs, tmp_path)

    members = read_members(info["path"])
    lines = members["ef-bundle/SHA256SUMS"][1].decode("utf-8").splitlines()
    assert all(re.fullmatch(r"[0-9a-f]{64}  \S+", line) for line in lines)   # 해시 + 공백 둘 + 상대 경로
    listed = {line.split("  ", 1)[1]: line.split("  ", 1)[0] for line in lines}
    assert set(listed) == {n[len("ef-bundle/"):] for n, (m, _) in members.items() if m.isfile()} - {"SHA256SUMS"}
    for rel, digest in listed.items():
        assert hashlib.sha256(members[f"ef-bundle/{rel}"][1]).hexdigest() == digest
    assert info["files"] == len(listed) == len(mgb.CODE_FILES) + len(mgb.DATA_FILES)


@needs_bash_and_sha
def test_extracted_bundle_passes_sha256sum_check(inputs, tmp_path):
    info = build(inputs, tmp_path)
    dest = tmp_path / "unpacked"
    with tarfile.open(info["path"], "r:gz") as tar:
        tar.extractall(dest, filter="data")
    tool = "sha256sum -c SHA256SUMS" if shutil.which("sha256sum") else "shasum -a 256 -c SHA256SUMS"

    result = subprocess.run(["bash", "-c", tool], cwd=dest / "ef-bundle", capture_output=True, text=True,
                            encoding="utf-8")

    assert result.returncode == 0, result.stdout + result.stderr


def test_gpu_run_script_is_executable_and_the_rest_are_plain_files(inputs, tmp_path):
    members = read_members(build(inputs, tmp_path)["path"])

    assert members["ef-bundle/gpu_run.sh"][0].mode == 0o755
    assert members["ef-bundle/train.py"][0].mode == 0o644
    assert members["ef-bundle/data/pairs-train.jsonl"][0].mode == 0o644
    assert members["ef-bundle/data"][0].mode == 0o755


def test_entries_carry_no_owner_or_timestamp(inputs, tmp_path):
    for name, (member, _) in read_members(build(inputs, tmp_path)["path"]).items():
        assert (member.mtime, member.uid, member.gid, member.uname, member.gname) == (0, 0, 0, "", ""), name


# ─── 재현성 ────────────────────────────────────────────────────────────────────


def test_same_inputs_give_identical_bytes_regardless_of_output_name_or_file_mtimes(inputs, tmp_path):
    first = build(inputs, tmp_path, "one.tgz")
    for folder in inputs:   # 입력 파일의 수정 시각이 달라져도 묶음은 같아야 한다
        for entry in os.scandir(folder):
            os.utime(entry.path, (1_000_000_000, 1_000_000_000))
    other_dir = tmp_path / "elsewhere"
    other_dir.mkdir()
    second = build(inputs, other_dir, "two-different-name.tgz")

    assert first["sha256"] == second["sha256"]
    with open(first["path"], "rb") as a, open(second["path"], "rb") as b:
        assert a.read() == b.read()


def test_gzip_header_has_no_file_name_or_timestamp(inputs, tmp_path):
    with open(build(inputs, tmp_path)["path"], "rb") as f:
        header = f.read(10)

    assert header[:2] == b"\x1f\x8b"
    assert header[3] & 0x08 == 0                      # FNAME 플래그 없음
    assert header[4:8] == b"\x00\x00\x00\x00"         # mtime 0


def test_changing_one_input_changes_the_bundle_and_only_its_line_in_the_sums(inputs, tmp_path):
    code, _ = inputs
    before = build(inputs, tmp_path, "before.tgz")
    (code / "train.py").write_text("# 바뀐 코드\n", encoding="utf-8")
    after = build(inputs, tmp_path, "after.tgz")

    assert before["sha256"] != after["sha256"]
    sums_before = read_members(before["path"])["ef-bundle/SHA256SUMS"][1].decode().splitlines()
    sums_after = read_members(after["path"])["ef-bundle/SHA256SUMS"][1].decode().splitlines()
    changed = [b for b, a in zip(sums_before, sums_after) if b != a]
    assert len(changed) == 1 and changed[0].endswith("  train.py")


# ─── 입력 누락·변조 ─────────────────────────────────────────────────────────────


def test_missing_inputs_are_reported_by_path_and_leave_no_output(inputs, tmp_path):
    code, data = inputs
    os.remove(data / "pairs-val.jsonl")
    os.remove(code / "train_core.py")

    with pytest.raises(mgb.BundleError) as exc:
        build(inputs, tmp_path)

    assert "pairs-val.jsonl" in str(exc.value) and "train_core.py" in str(exc.value)
    assert not (tmp_path / "bundle.tgz").exists()
    assert not (tmp_path / "bundle.tgz.tmp").exists()


def test_main_exits_with_a_clear_message_when_inputs_are_missing(tmp_path):
    empty = tmp_path / "empty"
    empty.mkdir()

    with pytest.raises(SystemExit) as exc:
        mgb.main(["--data-dir", str(empty), "--out", str(tmp_path / "x.tgz")])

    assert "pairs-train.jsonl" in str(exc.value.code)
    assert not (tmp_path / "x.tgz").exists()


def rewrite(path, out, transform):
    """tgz를 읽어 {이름: 내용}에 transform을 적용한 뒤 다시 쓴다 (변조된 묶음을 만들어 verify_tgz가 잡는지 본다)."""
    contents = {name: data for name, (m, data) in read_members(path).items() if m.isfile()}
    transform(contents)
    with tarfile.open(out, "w:gz") as tar:
        for name, data in contents.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, io.BytesIO(data))


def test_verify_accepts_an_untouched_bundle(inputs, tmp_path):
    info = build(inputs, tmp_path)

    assert mgb.verify_tgz(info["path"]) == info["files"]


def test_verify_rejects_a_modified_member(inputs, tmp_path):
    path = build(inputs, tmp_path)["path"]
    tampered = str(tmp_path / "tampered.tgz")
    rewrite(path, tampered, lambda c: c.__setitem__("ef-bundle/train.py", b"# tampered\n"))

    with pytest.raises(mgb.BundleError) as exc:
        mgb.verify_tgz(tampered)

    assert "train.py" in str(exc.value)


def test_verify_rejects_a_missing_member(inputs, tmp_path):
    path = build(inputs, tmp_path)["path"]
    tampered = str(tmp_path / "missing.tgz")
    rewrite(path, tampered, lambda c: c.pop("ef-bundle/data/pairs-val.jsonl"))

    with pytest.raises(mgb.BundleError) as exc:
        mgb.verify_tgz(tampered)

    assert "pairs-val.jsonl" in str(exc.value)


def test_verify_rejects_a_file_the_sums_do_not_cover(inputs, tmp_path):
    path = build(inputs, tmp_path)["path"]
    tampered = str(tmp_path / "extra.tgz")
    rewrite(path, tampered, lambda c: c.__setitem__("ef-bundle/extra.sh", b"echo hi\n"))

    with pytest.raises(mgb.BundleError) as exc:
        mgb.verify_tgz(tampered)

    assert "extra.sh" in str(exc.value)


def test_verify_rejects_a_bundle_whose_sums_omit_a_required_file(inputs, tmp_path):
    path = build(inputs, tmp_path)["path"]
    tampered = str(tmp_path / "omitted.tgz")

    def drop_line(contents):
        sums = contents["ef-bundle/SHA256SUMS"].decode().splitlines(keepends=True)
        contents["ef-bundle/SHA256SUMS"] = "".join(l for l in sums if not l.endswith("  train_core.py\n")).encode()
        del contents["ef-bundle/train_core.py"]   # 파일과 해시 줄이 함께 빠져도 필수 파일 점검이 잡아야 한다

    rewrite(path, tampered, drop_line)

    with pytest.raises(mgb.BundleError) as exc:
        mgb.verify_tgz(tampered)

    assert "train_core.py" in str(exc.value)


# ─── CLI 출력 ──────────────────────────────────────────────────────────────────


def test_main_prints_the_hash_and_the_one_line_command(inputs, tmp_path, capsys):
    _, data = inputs
    out = tmp_path / "my-bundle.tgz"

    code = mgb.main(["--data-dir", str(data), "--out", str(out)])

    printed = capsys.readouterr().out
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    assert code == 0
    assert str(out) in printed and digest in printed and f"앞 8자 : {digest[:8]}" in printed
    assert "sha256sum my-bundle.tgz | cut -c1-8 && tar xzf my-bundle.tgz && bash ef-bundle/gpu_run.sh start" in printed


def test_default_output_is_a_dated_file_in_the_data_dir(inputs, tmp_path):
    _, data = inputs

    mgb.main(["--data-dir", str(data)])   # 코드 파일은 이 저장소의 진짜 파일을 쓴다 — 실제 묶음이 만들어지는지도 본다

    made = [n for n in os.listdir(data) if n.startswith("gpu-bundle-") and n.endswith(".tgz")]
    assert len(made) == 1 and re.fullmatch(r"gpu-bundle-\d{8}\.tgz", made[0])
    assert mgb.verify_tgz(str(data / made[0])) == len(mgb.CODE_FILES) + len(mgb.DATA_FILES)


# ─── gpu_run.sh ────────────────────────────────────────────────────────────────


def script_text():
    with open(GPU_RUN, encoding="utf-8") as f:
        return f.read()


@needs_bash
def test_gpu_run_has_valid_bash_syntax():
    result = subprocess.run(["bash", "-n", GPU_RUN], capture_output=True, text=True, encoding="utf-8")

    assert result.returncode == 0, result.stderr


def test_gpu_run_defaults_match_train_core():
    # 기본 모델·리비전이 두 군데에 있다 — 한쪽만 바뀌면 GPU에서 다른 리비전으로 학습한다
    text = script_text()

    assert re.search(r'^DEFAULT_MODEL="([^"]+)"', text, re.M).group(1) == train_core.DEFAULT_MODEL
    assert re.search(r'^DEFAULT_REVISION="([^"]+)"', text, re.M).group(1) == train_core.DEFAULT_REVISION


def test_gpu_run_copies_exactly_the_files_the_bundle_carries():
    # 묶음에 파일을 더하고 gpu_run.sh의 복사 목록을 빠뜨리면 GPU에서 cp가 실패한다
    text = script_text()
    code = set(re.search(r'^BUNDLE_CODE_FILES="([^"]*)"', text, re.M).group(1).split())
    data = set(re.search(r'^BUNDLE_DATA_FILES="([^"]*)"', text, re.M).group(1).split())

    assert code == set(mgb.CODE_FILES) | {mgb.SUMS_NAME}
    assert data == set(mgb.DATA_FILES)


def test_gpu_run_runs_the_variants_train_core_defines():
    assert re.search(r'^VARIANTS="\$\{VARIANTS:-([^}]+)\}"', script_text(), re.M).group(1).split() == list(
        train_core.VARIANTS)


def test_requirements_pin_the_training_stack_and_leave_torch_to_the_environment():
    with open(os.path.join(EMBEDDING_FINETUNE_DIR, "requirements-train.txt"), encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip() and not l.lstrip().startswith("#")]

    assert sorted(lines) == sorted([
        "sentence-transformers==6.2.0", "transformers==5.22.1", "datasets==5.1.0", "accelerate==1.15.0",
        "numpy==2.4.6",
    ])   # torch가 여기 있으면 GPU에서 PyPI의 CUDA 13 빌드가 cu126 빌드를 덮어쓸 수 있다


def unpack_real_bundle(tmp_path):
    """진짜 코드 파일 + 가짜 데이터로 묶음을 만들어 푼다. 푼 ef-bundle 폴더 경로를 돌려준다."""
    data = tmp_path / "data"
    data.mkdir()
    for name in mgb.DATA_FILES:
        (data / name).write_text("{}\n", encoding="utf-8")
    info = mgb.build_bundle(str(data), str(tmp_path / "b.tgz"))
    with tarfile.open(info["path"], "r:gz") as tar:
        tar.extractall(tmp_path / "unpacked", filter="data")
    return tmp_path / "unpacked" / "ef-bundle"


def run_bash(script, cwd, env_extra=None):
    env = dict(os.environ)
    env.update(env_extra or {})
    return subprocess.run(["bash", "-c", script], cwd=cwd, env=env, capture_output=True, text=True, encoding="utf-8")


@needs_bash_and_sha
def test_verify_bundle_passes_for_an_untouched_bundle(tmp_path):
    folder = unpack_real_bundle(tmp_path)

    result = run_bash("source ./gpu_run.sh && verify_bundle", folder)

    assert result.returncode == 0, result.stdout + result.stderr


@needs_bash_and_sha
def test_verify_bundle_names_the_tampered_file_and_stops(tmp_path):
    folder = unpack_real_bundle(tmp_path)
    with open(folder / "train.py", "a", encoding="utf-8") as f:
        f.write("# 변조\n")

    result = run_bash("source ./gpu_run.sh && verify_bundle", folder)

    assert result.returncode != 0
    assert "train.py" in result.stderr and "FAILED" in result.stderr
    assert "파일이 잘렸거나 바뀌었습니다" in result.stderr
    assert "gpu_run.sh: OK" not in result.stderr   # 멀쩡한 파일까지 실패로 보이게 하지 않는다


@needs_bash_and_sha
def test_verify_bundle_catches_a_truncated_data_file(tmp_path):
    folder = unpack_real_bundle(tmp_path)
    (folder / "data" / "pairs-train.jsonl").write_bytes(b"")   # 업로드가 중간에 끊긴 경우

    result = run_bash("source ./gpu_run.sh && verify_bundle", folder)

    assert result.returncode != 0 and "pairs-train.jsonl" in result.stderr


@needs_bash_and_sha
def test_verify_bundle_catches_a_missing_file(tmp_path):
    folder = unpack_real_bundle(tmp_path)
    os.remove(folder / "train_core.py")

    result = run_bash("source ./gpu_run.sh && verify_bundle", folder)

    assert result.returncode != 0 and "train_core.py" in result.stderr


@needs_bash_and_sha
def test_verify_bundle_requires_the_sums_file(tmp_path):
    folder = unpack_real_bundle(tmp_path)
    os.remove(folder / "SHA256SUMS")

    result = run_bash("source ./gpu_run.sh && verify_bundle", folder)

    assert result.returncode != 0 and "SHA256SUMS가 없습니다" in result.stderr


@needs_bash_and_sha
def test_start_refuses_a_tampered_bundle_before_creating_any_run(tmp_path):
    folder = unpack_real_bundle(tmp_path)
    with open(folder / "train.py", "a", encoding="utf-8") as f:
        f.write("# 변조\n")
    root = tmp_path / "root"

    result = run_bash("bash ./gpu_run.sh start", folder, {"ROOT": str(root), "ALLOW_CPU": "1"})

    assert result.returncode != 0
    assert "파일이 잘렸거나 바뀌었습니다" in result.stderr
    assert not root.exists()   # 실행 폴더도, latest 표시도 만들지 않았다


@needs_bash
def test_sourcing_the_script_does_not_start_anything(tmp_path):
    result = run_bash("source ./gpu_run.sh && echo sourced", EMBEDDING_FINETUNE_DIR, {"ROOT": str(tmp_path / "root")})

    assert result.returncode == 0 and result.stdout.strip() == "sourced"
    assert not (tmp_path / "root").exists()
