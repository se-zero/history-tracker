#!/usr/bin/env python
"""라벨 큐 터미널 라벨러 — 큐 YAML의 `label: null` 칸을 한 쌍씩 보며 키 하나로 채운다 (docs/measurement.md §4.5).

`label_queue.py make`가 만든 큐는 수천 줄 YAML이라 손으로 label 칸을 찾아 고치기 어렵다. 이 도구는 항목을
하나씩 보여 주고, 키를 누르면 그 항목의 `  label:` 줄 한 줄만 바로 저장한다. 큐에는 점수·모델 정보가 없고
이 도구도 그런 정보를 만들어 내지 않는다 — 모델과 점수를 모른 채 판단해야 비교가 공정하기 때문이다.

  r relevant · i irrelevant · u unsure · s 건너뛰기 · b 이전 항목 · f 전체/축약 · ? 도움말 · q 종료

기본(축약) 화면은 터미널 높이에 들어가게 src·dst 블록을 행 단위로 줄인다. 한글·전각 글자는 2칸으로 세어
폭에 맞춰 감싼다. f를 누르면 높이와 상관없이 전부 보여 준다(위로 스크롤될 수 있다). 마지막 항목을 넘겨도
바로 끝나지 않고, b로 마지막 항목을 다시 고치거나 q로 끝낸다. 한글 입력 상태에서 누른 키는 라벨로 읽지
않고 안내만 한다.

저장은 파일을 다시 덤프하지 않고 줄 하나만 바꾼다(주석 헤더·리터럴 블록·다른 항목이 바이트 그대로 남는다).
바꾼 결과를 저장 전에 다시 파싱해 "그 항목의 label만 달라졌는지" 확인하고, 어긋나면 파일을 건드리지 않고 멈춘다.
처음 저장할 때 원본을 `<경로>.orig`로 한 번만 복사해 둔다.

편집기(VS Code 등)에서 같은 파일을 열어 두면 편집기의 저장이 이 도구가 쓴 라벨을 덮어쓸 수 있다. 닫고 쓴다.

실행 (ai-engine venv 사용):
    services/ai-engine/.venv/bin/python eval/label_cli.py eval/edge_labels/<큐 파일>.yaml
    옵션: --review  (이미 라벨한 항목까지 전부 돌며 고친다)
    다 채우면 label_queue.py fill로 표본 파일에 되돌려 채운다.
"""

import argparse
import contextlib
import copy
import os
import shlex
import re
import shutil
import sys
import tempfile
import unicodedata

import yaml

EVAL_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, EVAL_DIR)

from edge_eval import VALID_LABELS  # noqa: E402

CLEAR = "\x1b[2J\x1b[H"
MAX_FILES = 12               # 축약 보기에서 보여 주는 변경 파일 수
MIN_BLOCK_ROWS = 5           # 축약 보기에서 src·dst 블록 하나가 보장받는 최소 행 수
DEFAULT_HEIGHT = 24          # 터미널 높이를 알 수 없을 때
HANGUL_NOTICE = "한글 입력 상태입니다 — 영문으로 바꾼 뒤 r·i·u를 누르세요"

LABEL_KEYS = {"r": "relevant", "i": "irrelevant", "u": "unsure"}

DEFAULT_QUESTION = "이 둘이 실제로 의미적으로 관련 있나?"
QUESTIONS = {
    "REFERENCE": "이 커밋이 아래 대화(스레드)와 실제로 같은 사안을 다루나?",
    "DISCUSSED_IN": "이 이슈가 아래 대화에서 실제로 논의됐나?",
    "TRIGGERED_BY": "이 커밋이 이 이슈 때문에 생긴 변경인가?",
}

FOOTER = [
    "[r] relevant 관련  [i] irrelevant 무관  [u] unsure 판단불가",
    "[s] 건너뛰기  [b] 이전  [f] 전체/축약  [q] 종료  [?] 도움말",
]
HELP = [
    "relevant   관련    : 위 질문에 '그렇다' — 둘이 실제로 같은 사안이다",
    "irrelevant 무관    : 유사도로 이어졌을 뿐 실제로는 무관하다",
    "unsure     판단불가: 원문만으로 판단 불가",
    "키를 누르는 즉시 파일에 저장됩니다. 처음 저장할 때 원본을 <경로>.orig로 한 번 복사합니다.",
    "건너뛴 항목은 다시 실행하면 이어서 나옵니다. 이미 준 라벨은 b로 돌아가거나 --review로 고칩니다.",
]


class QueueFileError(Exception):
    """큐 파일을 읽거나 고칠 수 없다 — 파일은 그대로 두고 멈춘다."""


# --------------------------------------------------------------------------- 순수 로직

def current_label(entry: dict) -> str | None:
    """edge_eval이 채점에 쓰는 것과 같은 기준(공백·대소문자 무시)으로 유효한 라벨만 돌려준다."""
    label = str(entry.get("label") or "").strip().lower()
    return label if label in VALID_LABELS else None


def select_indices(items: list[dict], review: bool) -> list[int]:
    """라벨링 대상 항목의 위치 — 기본은 라벨이 없거나 허용값이 아닌 것만, --review는 전부."""
    return [i for i, item in enumerate(items) if review or current_label(item) is None]


def count_labels(items: list[dict]) -> dict[str, int]:
    counts = {"relevant": 0, "irrelevant": 0, "unsure": 0}
    for item in items:
        label = current_label(item)
        if label:
            counts[label] += 1
    return counts


def summary_lines(items: list[dict], queue_path: str) -> list[str]:
    counts = count_labels(items)
    remaining = len(items) - sum(counts.values())
    lines = [f"relevant {counts['relevant']} · irrelevant {counts['irrelevant']} · unsure {counts['unsure']}"
             f" · 남은 {remaining}개 (전체 {len(items)}개)"]
    if remaining == 0:
        lines += ["", "모든 항목에 라벨이 있습니다. 다음 명령으로 표본 파일에 채웁니다:",
                  f"  services/ai-engine/.venv/bin/python eval/label_queue.py fill --queue {shlex.quote(queue_path)} <표본 파일들>"]
    else:
        lines.append("라벨 없는 항목은 다시 실행하면 이어서 나옵니다.")
    return lines


# --- 렌더링 -----------------------------------------------------------------

# (텍스트, 첫 줄 들여쓰기, 이어지는 줄 들여쓰기) — 긴 줄을 감쌀 때 어디에 맞춰 이어 쓸지까지 담는다
_Line = tuple[str, str, str]


def _plain(text: str, indent: str = "  ") -> _Line:
    return (text, indent, indent)


def _commit_block(side: dict, full: bool) -> tuple[str, list[_Line]]:
    body = side.get("body") or {}
    header = f"커밋 {(body.get('hash') or str(side['id']))[:7]}"
    lines = [_plain(line) for line in str(body.get("message") or "").splitlines()]
    files = list(body.get("files") or [])
    if files:
        shown = files if full else files[:MAX_FILES]
        lines += [_plain(""), _plain(f"변경 파일 {len(files)}개:")]
        lines += [(f"- {path}", "    ", "      ") for path in shown]
        if len(shown) < len(files):
            lines.append(_plain(f"… 외 {len(files) - len(shown)}개", "    "))
    return header, lines


def _issue_block(side: dict, full: bool) -> tuple[str, list[_Line]]:
    body = side.get("body") or {}
    status = f" [{body['status']}]" if body.get("status") else ""
    header = f"이슈 {body.get('key') or side['id']}{status}"
    lines = [_plain(str(body.get("title") or ""))]
    text = str(body.get("body") or "").strip()
    if text:
        lines += [_plain("")] + [_plain(line) for line in text.splitlines()]
    return header, lines


def _thread_block(side: dict, full: bool) -> tuple[str, list[_Line]]:
    messages = (side.get("body") or {}).get("messages") or []
    lines: list[_Line] = []
    for message in messages:
        stamp = str(message.get("occurred_at") or "")[:16].replace("T", " ")
        prefix = f"[{stamp} UTC] " if stamp else ""
        prefix += f"{message.get('author') or '?'} ({message.get('channel') or '?'}): "
        text_lines = str(message.get("body") or "").splitlines() or [""]
        lines.append((prefix + text_lines[0], "  ", "    "))          # 이어지는 줄은 한 칸 더 들여 메시지 경계를 보이게
        lines += [_plain(line, "    ") for line in text_lines[1:]]
    return f"대화(스레드) {len(messages)}개 메시지", lines


_BLOCKS = {"commit": _commit_block, "issue": _issue_block, "message": _thread_block}


def char_width(char: str) -> int:
    """터미널에서 차지하는 칸 수 — 한글·전각은 2칸, 결합 문자와 폭 없는 이음 문자(이모지 조합)는 0칸."""
    if unicodedata.combining(char) or unicodedata.category(char) in ("Mn", "Me", "Cf"):
        return 0
    return 2 if unicodedata.east_asian_width(char) in ("W", "F") else 1


def display_width(text: str) -> int:
    return sum(char_width(char) for char in text)


def _fit_prefix(text: str, room: int) -> str:
    """표시 폭이 room칸 안에 들어가는 가장 긴 앞부분."""
    used = 0
    for i, char in enumerate(text):
        used += char_width(char)
        if used > room:
            return text[:i]
    return text


def wrap_text(text: str, first: str, rest: str, limit: int) -> list[str]:
    """표시 폭 limit칸 안에서 감싼다 — 공백에서 끊을 수 있으면 거기서, 공백 없는 긴 덩어리(한글 문장·경로)는
    폭이 차는 곳에서 자른다. textwrap은 글자 수로 세어서 한글 줄이 터미널에서 두 행으로 접힌다."""
    lines: list[str] = []
    line, used, filled = first, display_width(first), False
    pending = ""
    for token in re.findall(r"\s+|\S+", text.expandtabs()):
        if token.isspace():
            pending = token
            continue
        # 문단 맨 앞 공백(들여쓴 코드)은 살리고, 감겨 내려온 줄 앞의 공백은 버린다
        sep = pending if (filled or not lines) else ""
        pending = ""
        # 새 줄에는 들어가는 단어만 다음 줄로 내린다. 어느 줄에도 안 들어가는 긴 단어(경로)는 현재 줄의 남은
        # 칸부터 채워야 "-"만 덩그러니 남고 경로가 다음 줄에서 시작하는 모양이 안 된다
        if filled and used + display_width(sep + token) > limit and display_width(token) <= limit - display_width(rest):
            lines.append(line)
            line, used, filled, sep = rest, display_width(rest), False, ""
        piece = sep + token
        while used + display_width(piece) > limit:
            take = _fit_prefix(piece, limit - used)
            if not take and filled:                 # 이 줄에 한 글자도 더 못 담는다 → 줄을 바꾼다
                lines.append(line)
                line, used, filled = rest, display_width(rest), False
                piece = piece.lstrip()
                continue
            take = take or piece[0]                 # 새 줄에도 한 글자가 안 들어가는 극단적으로 좁은 폭에서 무한 반복을 막는다
            lines.append(line + take)
            piece = piece[len(take):]
            line, used, filled = rest, display_width(rest), False
        line += piece
        used += display_width(piece)
        filled = True
    return lines + [line] if filled else [""]


def _block(side: dict, limit: int, full: bool) -> list[str]:
    """블록 전체를 감싼 줄들 — 첫 줄은 헤더. 행 수에 맞춰 줄이는 건 _cut이 한다."""
    builder = _BLOCKS.get(side["type"])
    header, lines = builder(side, full) if builder else (f"{side['type']} {side['id']}", [])
    out = [f"■ {header}"]
    for text, first, rest in lines:
        out.extend(wrap_text(text, first, rest, limit))
    return out


def _cut(block: list[str], rows: int) -> list[str]:
    """블록을 rows행으로 줄인다. 헤더는 남기고 끝에 생략 안내 한 줄을 붙인다(그 줄도 rows에 든다)."""
    if len(block) <= rows:
        return block
    rows = max(rows, 2)
    return block[:rows - 1] + [f"  … ({len(block) - rows + 1}줄 생략, f 키로 전체 보기)"]


def _split_rows(need_src: int, need_dst: int, avail: int) -> tuple[int, int]:
    """두 블록이 avail행을 나눠 쓴다 — 한쪽이 짧으면 남는 행을 다른 쪽에 준다."""
    if need_src + need_dst <= avail:
        return need_src, need_dst
    src_share = (avail + 1) // 2
    if need_src <= src_share:
        return need_src, avail - need_src
    if need_dst <= avail - src_share:
        return avail - need_dst, need_dst
    return src_share, avail - src_share


def _limit(width: int) -> int:
    return max(width - 1, 20)       # 폭을 꽉 채운 줄에서 터미널이 한 줄을 더 띄우지 않게 한 칸 비운다


def render_item(item: dict, width: int, full: bool, rows: int | None = None) -> list[str]:
    """src 블록 아래에 dst 블록(구분선 포함). 축약 보기에서 rows를 주면 그 행 안으로 블록을 줄인다."""
    limit = _limit(width)
    src, dst = _block(item["src"], limit, full), _block(item["dst"], limit, full)
    if rows is not None and not full:
        src_rows, dst_rows = _split_rows(len(src), len(dst), rows - 1)       # 구분선 한 줄을 뺀다
        src, dst = _cut(src, src_rows), _cut(dst, dst_rows)
    return src + ["─" * min(limit, 60)] + dst


# 화면 배치 후보: (좁은 배치 여부, 배너 포함 여부, 블록 둘이 받아야 하는 최소 행). 앞에서부터 들어가는 첫 배치를 쓴다.
# 좁은 배치는 머리(진행·질문)와 구분선을 빼고 푸터의 진행·질문 줄이 그 몫을 한다. 마지막은 배너까지 뺀다.
_LAYOUTS = [(False, True, 2 * MIN_BLOCK_ROWS), (True, True, 4), (True, False, 0)]


def render_screen(item: dict, *, position: int, total: int, remaining: int, full: bool, width: int,
                  height: int = DEFAULT_HEIGHT, banner: list[str] | None = None, notice: str | None = None,
                  show_help: bool = False) -> list[str]:
    limit = _limit(width)
    rule = "═" * min(limit, 72)
    question = QUESTIONS.get(item["edge_type"], DEFAULT_QUESTION)
    label = current_label(item)

    def chrome(compact: bool, with_banner: bool) -> tuple[list[str], list[str]]:
        top: list[str] = []
        bottom: list[str] = []
        if banner and with_banner:
            for text in (banner[-1:] if compact else banner):      # 좁으면 편집기 경고(마지막 줄)만 남긴다
                top += wrap_text(text, "", "  ", limit)
            if not compact:
                top.append("")
        if not compact:
            top += wrap_text(f"[진행 {position}/{total}] 남은 {remaining}개 · 유형 {item['edge_type']}", "", "  ", limit)
            top += wrap_text(f"질문: {question}", "", "      ", limit)
        if label:
            top.append(f"현재 라벨: {label}")
        if notice:
            top.append(notice)
        if not compact:
            top.append(rule)
            bottom.append(rule)
        # 높은 항목은 맨 위가 위로 밀려 나가므로, 무엇을 판단하는지 키 안내 바로 위에 한 번 더 둔다
        bottom += wrap_text(f"[{position}/{total}] {item['edge_type']} — {question}", "", "  ", limit)
        for line in FOOTER:
            bottom += wrap_text(line, "", "  ", limit)
        if show_help:
            bottom.append("")
            for line in HELP:
                bottom += wrap_text(line, "", "  ", limit)
        return top, bottom

    for compact, with_banner, required in _LAYOUTS:
        top, bottom = chrome(compact, with_banner)
        rows = height - 1 - len(top) - len(bottom)       # 블록 둘과 구분선에 쓸 행 — 끝의 커서 한 줄은 남겨야 위로 안 밀린다
        if full or rows - 1 >= required:
            break
    return top + render_item(item, width, full, rows=None if full else rows) + bottom


# --- 줄 단위 수정 -----------------------------------------------------------

def set_label_in_text(text: str, index: int, label: str) -> str:
    """index번째 항목의 `  label:` 줄 하나만 바꾼 새 텍스트. 파일을 다시 덤프하지 않는다.

    항목은 열 0의 `- `로 시작하고 label은 항목 안 2칸 들여쓰기 키다. 리터럴 블록의 본문은 그보다 깊이
    들여써져 있어 본문 속 `label:` 문자열과 헷갈리지 않는다. 줄 경계는 \\n만 쓴다 — splitlines는 다른
    문자에서도 끊어 줄 번호가 YAML과 어긋나고, CRLF 파일의 줄 끝(\\r)도 그대로 남겨야 한다.
    """
    if label not in VALID_LABELS:
        raise ValueError(f"허용값이 아닌 라벨: {label!r}")
    lines = text.split("\n")
    starts = [i for i, line in enumerate(lines) if line.startswith("- ")]
    if not 0 <= index < len(starts):
        raise QueueFileError(f"{index + 1}번째 항목을 찾지 못했습니다 (파일에서 찾은 항목 {len(starts)}개)")
    end = starts[index + 1] if index + 1 < len(starts) else len(lines)
    found = [i for i in range(starts[index], end) if lines[i].startswith("  label:")]
    if len(found) != 1:
        raise QueueFileError(f"{index + 1}번째 항목의 label 줄을 하나만 찾아야 하는데 {len(found)}개 찾았습니다"
                             " — 전체를 다시 덤프하지 않고 멈춥니다")
    line = found[0]
    lines[line] = f"  label: {label}" + ("\r" if lines[line].endswith("\r") else "")
    return "\n".join(lines)


def verify_label_change(old_doc: dict, new_text: str, index: int, label: str) -> dict:
    """새 텍스트가 '그 항목의 label만 바뀐' 데이터인지 확인하고, 맞으면 파싱한 새 문서를 돌려준다."""
    try:
        new_doc = yaml.safe_load(new_text)
    except yaml.YAMLError as e:
        raise QueueFileError(f"저장 전 검증 실패 — 새 텍스트가 YAML로 읽히지 않습니다 ({type(e).__name__})") from e
    expected = copy.deepcopy(old_doc)
    expected["edges"][index]["label"] = label
    if new_doc != expected:
        raise QueueFileError(f"저장 전 검증 실패 — {index + 1}번째 항목의 label 외에도 데이터가 달라져 파일을 바꾸지 않았습니다")
    return new_doc


# --------------------------------------------------------------------------- 파일

def _read_text(path: str) -> str:
    with open(path, encoding="utf-8", newline="") as f:         # newline=""로 줄 끝(CRLF)을 그대로 읽는다
        return f.read()


def _atomic_write(path: str, text: str) -> None:
    """임시 파일에 쓰고 교체한다 — 쓰다 죽어도 큐 파일이 반쯤 잘린 채 남지 않는다."""
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(os.path.abspath(path)), prefix=".label_cli-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as f:
            f.write(text)
            f.flush()
            os.fsync(f.fileno())
        shutil.copymode(path, tmp)
        os.replace(tmp, path)
    except BaseException:
        if os.path.exists(tmp):
            os.unlink(tmp)
        raise


class QueueFile:
    """큐 파일과 읽어 둔 내용. 라벨 저장은 모두 여기를 거친다."""

    def __init__(self, path: str):
        self.path = path
        self.text = _read_text(path)
        try:
            self.doc = yaml.safe_load(self.text)
        except yaml.YAMLError as e:
            raise QueueFileError(f"{path}를 YAML로 읽지 못했습니다 ({type(e).__name__})") from e
        if not isinstance(self.doc, dict) or not isinstance(self.doc.get("edges"), list):
            raise QueueFileError(f"{path}에 edges 목록이 없습니다 — label_queue.py make가 만든 큐 파일이 아닙니다")

    @property
    def items(self) -> list[dict]:
        return self.doc["edges"]

    def set_label(self, index: int, label: str) -> None:
        # 읽은 뒤 누가 파일을 고쳤다면(편집기 저장 등) 메모리의 옛 내용으로 덮어쓰지 않는다
        if _read_text(self.path) != self.text:
            raise QueueFileError("파일이 이 도구 밖에서 바뀌었습니다(편집기 저장 등) — 덮어쓰지 않았습니다."
                                 " 편집기를 닫고 다시 실행하세요")
        new_text = set_label_in_text(self.text, index, label)
        new_doc = verify_label_change(self.doc, new_text, index, label)
        backup = self.path + ".orig"
        if not os.path.exists(backup):
            shutil.copy2(self.path, backup)
        _atomic_write(self.path, new_text)
        self.text, self.doc = new_text, new_doc


# --------------------------------------------------------------------------- 터미널

@contextlib.contextmanager
def cbreak_mode(fd: int):
    """Enter 없이 한 글자씩 읽는 모드. 어떤 이유로 빠져나가든 터미널 상태를 되돌린다.

    raw가 아니라 cbreak를 쓴다 — raw는 출력 후처리까지 꺼서 줄바꿈이 계단처럼 밀리고 Ctrl-C도 글자로만 온다.
    """
    import termios
    import tty
    saved = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd)
        yield
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)


def read_key_from_fd(fd: int) -> str | None:
    """키 하나. EOF는 None. 방향키·End 같은 이스케이프 시퀀스는 통째로 삼키고 ''를 돌려준다
    (남겨 두면 End(ESC [ F)의 'F'가 전체 보기 토글로 읽힌다)."""
    import select
    try:
        data = os.read(fd, 1)
    except OSError:                         # 터미널이 닫히면 EIO
        return None
    if not data:
        return None
    if data == b"\x1b":
        while select.select([fd], [], [], 0.02)[0]:
            os.read(fd, 64)
        return ""
    while True:                             # 한글 같은 멀티바이트 글자는 끝까지 읽어 한 글자로
        try:
            return data.decode("utf-8")
        except UnicodeDecodeError:
            more = os.read(fd, 1) if len(data) < 4 else b""
            if not more:
                return ""
            data += more


def _write_stdout(text: str) -> None:
    sys.stdout.write(text)
    sys.stdout.flush()


def _terminal_width() -> int:
    return shutil.get_terminal_size((80, 24)).columns


def _terminal_height() -> int:
    return shutil.get_terminal_size((80, DEFAULT_HEIGHT)).lines


# --------------------------------------------------------------------------- 대화 루프

def _is_hangul(key: str) -> bool:
    return any("\u3130" <= char <= "\u318f" or "\uac00" <= char <= "\ud7a3" for char in key)


def run_session(path: str, *, review: bool, read_key, write, get_width=_terminal_width,
                get_height=_terminal_height) -> int:
    """키를 읽어 항목을 넘기며 라벨을 저장한다. 종료 코드를 돌려준다 (0 정상 · 1 저장 오류).

    read_key는 키 한 글자(EOF면 None), write는 화면 출력을 받는 호출 가능 객체, get_width·get_height는 터미널
    크기를 돌려주는 호출 가능 객체라 터미널 없이 시험할 수 있다.
    """
    store = QueueFile(path)
    targets = select_indices(store.items, review)
    banner = [f"라벨 큐: {path}", "키를 누를 때마다 바로 저장합니다. ? 를 누르면 도움말.",
              "※ 편집기에서 이 파일을 열어 두었다면 닫고 쓰세요."]
    position, full, show_help, notice = 0, False, False, None
    at_end = False

    def remaining() -> int:
        return len(store.items) - sum(count_labels(store.items).values())

    def show_summary(title: str, prompt: bool = False) -> None:
        lines = [title, ""] + summary_lines(store.items, path)
        if prompt:
            lines += ["", "마지막까지 봤습니다. 고치려면 b, 끝내려면 q"]
        write(CLEAR + "\n".join(lines) + "\n")

    def hint_if_hangul(key: str) -> None:
        # IME가 켜진 채 누른 r·i·u는 자모로 와서 라벨 키가 아니다. 매핑하지 않고 안내만 한다 — 화면은 다시 그리지 않고
        # 푸터 아래 줄만 덮어쓴다(다음에 화면을 그릴 때 함께 지워진다)
        if _is_hangul(key):
            write("\r\x1b[2K" + HANGUL_NOTICE)

    try:
        while True:
            if position >= len(targets):
                show_summary("모두 끝났습니다." if remaining() == 0 else "끝까지 봤지만 건너뛴 항목이 있습니다.",
                             prompt=bool(targets))
                if not targets:
                    return 0
                at_end = True                       # 마지막 라벨·건너뛰기 뒤에 곧바로 끝내면 마지막 실수를 못 고친다
                while True:
                    key = read_key()
                    if key is None or key == "\x03" or key.lower() == "q":
                        return 0
                    if key.lower() == "b":
                        break
                    hint_if_hangul(key)
                at_end = False
                position, full = len(targets) - 1, False
                continue

            index = targets[position]
            write(CLEAR + "\n".join(render_screen(
                store.items[index], position=position + 1, total=len(targets), remaining=remaining(), full=full,
                width=get_width(), height=get_height(), banner=banner, notice=notice, show_help=show_help)) + "\n")
            banner, notice, show_help = None, None, False

            redraw = False
            while not redraw:                       # 화면을 바꾸는 키가 올 때까지 — 모르는 키는 화면을 건드리지 않는다
                key = read_key()
                if key is None or key == "\x03" or key.lower() == "q":
                    show_summary("종료합니다. 저장된 라벨은 파일에 그대로 남아 있습니다.")
                    return 0
                key = key.lower()
                if key in LABEL_KEYS:
                    try:
                        store.set_label(index, LABEL_KEYS[key])
                    except (QueueFileError, OSError) as e:
                        write(f"\n라벨을 저장하지 못해 종료합니다: {e}\n")
                        return 1
                    position, full, redraw = position + 1, False, True
                elif key == "s":
                    position, full, redraw = position + 1, False, True
                elif key == "b":
                    if position > 0:
                        position, full = position - 1, False
                    else:
                        notice = "첫 항목입니다."
                    redraw = True
                elif key == "f":
                    full, redraw = not full, True
                elif key == "?":
                    show_help, redraw = True, True
                else:
                    hint_if_hangul(key)
    except KeyboardInterrupt:
        if not at_end:                              # 끝 화면에는 요약이 이미 떠 있다
            show_summary("중단했습니다. 저장된 라벨은 파일에 그대로 남아 있습니다.")
        return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("queue", help="label_queue.py make가 만든 큐 파일 (이 파일의 label 칸을 직접 고친다)")
    ap.add_argument("--review", action="store_true", help="이미 라벨한 항목까지 전부 돌며 고친다")
    args = ap.parse_args(argv)

    if not sys.stdin.isatty():
        print("대화형 터미널에서 실행하세요 — 키를 한 글자씩 읽어서 파이프·리다이렉트로는 쓸 수 없습니다.", file=sys.stderr)
        return 2

    fd = sys.stdin.fileno()
    try:
        with cbreak_mode(fd):
            return run_session(args.queue, review=args.review, read_key=lambda: read_key_from_fd(fd),
                               write=_write_stdout)
    except (QueueFileError, OSError) as e:
        print(f"오류: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
