#!/usr/bin/env python
"""공개 저장소 학습 쌍 수집 — "같은 사안을 다루는 글 쌍"을 모아 임베딩 파인튜닝의 대조학습 데이터(JSONL)로 만든다
(docs/embedding-finetune.md §5 2단계, 묶음 D).

쌍은 운영의 세 엣지에 대응한다: 코드 변경↔대화(REFERENCE), 변경↔이슈(TRIGGERED_BY), 이슈↔대화(DISCUSSED_IN).
허용 라이선스(MIT·Apache-2.0·BSD-2/3-Clause)의 비포크 저장소만 쓰고, 봇·Copilot 글, 사람 계정으로 올라온 기계 글
(자동 보고서·AI 리뷰), 짧은 글은 뺀다.
작성자 정보는 파일에 남기지 않는다. 표준 라이브러리만 쓰고, GitHub 호출은 `gh api`(토큰은 gh가 다룬다)다.

  fetch   조직(또는 --repos)의 저장소를 라이선스로 거르고, 저장소마다 원자료를 data/raw/<owner>__<repo>/에 받는다.
          meta.json에 complete가 있는 저장소는 건너뛰므로 끊겨도 다시 실행하면 이어 받는다 (--refresh로 다시 받기).
          리뷰 댓글 한 쪽(100건)에 몇 초씩 걸려 3시간 안팎 걸린다(2026-11-04 실측 2.8시간). REST 한도(시간당
          5,000회)가 모자라면 reset까지 알아서 기다린다.
  build   원자료로 쌍을 만들어 pairs-train.jsonl · pairs-val.jsonl · pairs-manifest.json을 쓴다. 네트워크를 쓰지 않는다.
  sample  pairs-<split>.jsonl에서 (type, anchor_kind) 층마다 고르게 뽑아 pairs-sample<n>.jsonl로 쓴다.
          사람이 "같은 사안인가"를 읽어 보는 용도다.

실행 (Mac venv — 준비는 README.md):
    eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py fetch
    eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py build --per-pr-commit-cap 0
    eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py sample --n 50

테스트 (오프라인 — gh 호출은 주입한 가짜):
    services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

# --------------------------------------------------------------------------- 상수

DEFAULT_DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")

# fetch와 build가 같은 집합으로 두 번 거른다 — 한쪽이 뚫리거나 원자료가 옛 것이어도 다른 쪽이 막는다
ALLOWED_LICENSES = frozenset({"MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause"})
DEFAULT_ORGS = "next-step,woowacourse,woowacourse-teams,daangn,toss"
# 운영 데이터와 가장 닮은 팀 프로젝트라 학습에 섞지 않고 검증 전용으로 둔다 (docs/embedding-finetune.md §5)
DEFAULT_VAL_REPOS = "woowacourse-teams/2022-dallog,woowacourse-teams/2023-hang-log"
DEFAULT_SEED = 42

GH_TIMEOUT_SECONDS = 120
PER_PAGE = 100
BACKOFF_SECONDS = (2, 4, 8, 16, 32)   # 5xx·시간 초과·네트워크 오류 재시도 간격. 길이가 곧 재시도 횟수다
MAX_ATTEMPTS = len(BACKOFF_SECONDS) + 1
RATE_CHECK_EVERY = 100
RATE_LIMIT_FLOOR = 100
RATE_LIMIT_EXTRA_WAIT = 5             # reset 직후에는 아직 안 풀려 있을 수 있어 여유를 둔다

MAX_TEXT_CHARS = 4000
MIN_CONVERSATION_CHARS = 20
MIN_ANCHOR_CHARS = 8
MAX_HUNK_LINES = 10
MAX_LINKED_ISSUES = 3

# 쌍이 탈락하는 사유. 매니페스트에 전부 0부터 채워 넣어 "0건"과 "집계 누락"이 구분되게 한다
DROP_REASONS = (
    "license", "bot", "copilot", "machine", "short_or_ack", "anchor_short", "commit_filtered",
    "no_changed_lines", "per_pr_cap", "per_issue_cap", "duplicate", "repo_type_cap",
)
TYPE_ORDER = ("change_conversation", "change_issue", "issue_conversation")


def _say(message: str) -> None:
    # 진행 줄이 파이프·리다이렉트에서 몇 시간 동안 버퍼에 갇히지 않게 바로 내보낸다
    print(message, flush=True)


# --------------------------------------------------------------------------- GitHub 호출 (gh api)

class GhError(Exception):
    """gh api 호출 실패 — 재시도로 풀리지 않거나 재시도를 다 쓴 경우."""


class GhNotFound(GhError):
    """HTTP 404 — 저장소가 없거나 볼 수 없다. 호출부가 그 저장소를 경고하고 건너뛴다."""


class GhGone(GhError):
    """HTTP 410 — 이슈 기능이 꺼진 저장소의 이슈 목록이 이렇게 온다."""


def _run_gh(path: str) -> subprocess.CompletedProcess:
    # 인코딩을 못 박는다 — 로캘이 UTF-8이 아니면 한글 본문이 깨진다
    return subprocess.run(["gh", "api", path], capture_output=True, text=True, encoding="utf-8",
                          timeout=GH_TIMEOUT_SECONDS)


def _http_status(stderr: str) -> int | None:
    found = re.search(r"HTTP (\d{3})", stderr)
    return int(found.group(1)) if found else None


def _first_line(stderr: str) -> str:
    for line in stderr.splitlines():
        if line.strip():
            return line.strip()[:200]
    return "(stderr 없음)"


class GhApi:
    """`gh api` 호출 래퍼. runner·sleep·clock을 주입할 수 있어 테스트가 네트워크도 시간도 쓰지 않는다.

    runner(path) -> subprocess.CompletedProcess (returncode·stdout·stderr). path는 `repos/o/r/issues?...` 형태다.
    요청은 순차로만 보낸다 — 병렬 호출은 secondary rate limit에 걸리기 쉽다.
    """

    def __init__(self, runner=None, sleep=time.sleep, clock=time.time, log=_say):
        self._run = runner or _run_gh
        self._sleep = sleep
        self._clock = clock
        self._log = log
        self._requests = 0

    def get(self, path: str):
        # 첫 요청에서도 확인한다 — 직전 실행이 한도를 거의 다 쓰고 끊긴 채 다시 시작하는 경우를 막는다
        if self._requests % RATE_CHECK_EVERY == 0:
            core = self._request("rate_limit", wait_on_rate_limit=False)["resources"]["core"]
            if core["remaining"] < RATE_LIMIT_FLOOR:
                self._wait_until(core["reset"], f"남은 한도 {core['remaining']}회")
        self._requests += 1
        return self._request(path)

    def pages(self, path: str, max_pages: int | None = None) -> list:
        """목록 API를 page=1부터 돌며 이어 붙인다. path에 per_page=100이 들어 있어야 한다."""
        sep = "&" if "?" in path else "?"
        items: list = []
        page = 1
        while True:
            batch = self.get(f"{path}{sep}page={page}")
            items.extend(batch)
            if len(batch) < PER_PAGE or (max_pages is not None and page >= max_pages):
                return items
            page += 1

    def _request(self, path: str, *, wait_on_rate_limit: bool = True):
        last = ""
        for attempt in range(MAX_ATTEMPTS):
            try:
                result = self._run(path)
            except subprocess.TimeoutExpired:
                last = "시간 초과"
            else:
                if result.returncode == 0:
                    try:
                        return json.loads(result.stdout)
                    except ValueError as e:
                        raise GhError(f"{path}: 응답이 JSON이 아니다") from e
                stderr = result.stderr or ""
                status = _http_status(stderr)
                last = _first_line(stderr)
                if status == 404:
                    raise GhNotFound(f"{path}: {last}")
                if status == 410:
                    raise GhGone(f"{path}: {last}")
                if status in (403, 429) and "rate limit" in stderr.lower():
                    if not wait_on_rate_limit:
                        raise GhError(f"{path}: {last}")
                    core = self._request("rate_limit", wait_on_rate_limit=False)["resources"]["core"]
                    self._wait_until(core["reset"], "한도 초과")
                    continue   # reset까지 기다렸으니 백오프를 더하지 않고 바로 다시 보낸다
                # 4xx는 다시 보내도 같은 답이다. HTTP 상태가 없는 실패(DNS 실패·연결 끊김 같은 네트워크 오류)는
                # 잠깐의 끊김일 수 있어 5xx·시간 초과와 같이 백오프로 다시 시도한다
                if status is not None and status < 500:
                    raise GhError(f"{path}: {last}")
            if attempt < MAX_ATTEMPTS - 1:
                self._sleep(BACKOFF_SECONDS[attempt])
        raise GhError(f"{path}: 재시도 {MAX_ATTEMPTS - 1}회 뒤에도 실패 ({last})")

    def _wait_until(self, reset_epoch: float, reason: str) -> None:
        seconds = max(0.0, reset_epoch - self._clock()) + RATE_LIMIT_EXTRA_WAIT
        self._log(f"  {reason} — 한도가 풀리는 시각까지 {int(seconds)}초 기다린다")
        self._sleep(seconds)


# --------------------------------------------------------------------------- 순수 로직: 라이선스

def license_spdx(repo: dict) -> str | None:
    return (repo.get("license") or {}).get("spdx_id")


def is_allowed_license(spdx_id: str | None) -> bool:
    return spdx_id in ALLOWED_LICENSES


def is_collectable_repo(repo: dict) -> bool:
    """포크가 아니고 허용 라이선스인 저장소.

    포크는 원본과 같은 글을 중복해서 싣게 되므로 뺀다. 보관(archived)은 쓰기만 막힌 상태라 읽는 데 문제가 없어
    제외하지 않는다. fork 필드가 없는 응답은 포크가 아님을 확인할 수 없으니 뺀다.
    """
    return repo.get("fork") is False and is_allowed_license(license_spdx(repo))


def repo_sort_key(full_name: str) -> tuple[str, str]:
    return full_name.lower(), full_name


# --------------------------------------------------------------------------- 순수 로직: 텍스트 정제·가림

_HTML_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
# 템플릿 잔여물(주석·_No response_)을 지운 자리에 쌓인 빈 줄을 정리한다 (빈 줄 3줄 이상 → 2줄)
_BLANK_RUN_RE = re.compile(r"\n(?:[ \t]*\n){3,}")


def clean_text(text: str | None) -> str:
    """이슈·PR 템플릿이 남긴 잔여물을 걷어낸다. 4,000자에서 자른다(학습 입력은 어차피 512토큰이다)."""
    if not text:
        return ""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = _HTML_COMMENT_RE.sub("", text)
    text = "\n".join(line for line in text.split("\n") if line.strip() != "_No response_")
    text = _BLANK_RUN_RE.sub("\n\n\n", text)
    return text.strip()[:MAX_TEXT_CHARS]


_URL_RE = re.compile(r"https?://\S+")
# `&#123;` 같은 HTML 엔티티는 이슈 번호가 아니다. 뒤가 한글이어도 가려야 해서 \b 대신 ASCII 문자만 본다
_ISSUE_REF_MASK_RE = re.compile(r"(?<!&)#\d+(?![A-Za-z0-9_])")
# re.ASCII: `HT-12를`처럼 뒤에 한글이 바로 붙어도 단어 경계로 보게 한다
_JIRA_KEY_RE = re.compile(r"\b([A-Z][A-Z0-9]{1,9})-\d+\b", re.ASCII)
# 표준·인코딩 이름(UTF-8, SHA-256 ...)은 이슈 키가 아니라 의미가 있는 글이라 남긴다
_NOT_A_KEY_PREFIXES = frozenset({"UTF", "ISO", "SHA", "ES", "HTTP", "TLS", "SSL", "RFC", "CP", "EUC", "KS"})
# 숫자와 a-f가 둘 다 있어야 해시로 본다 — 1234567이나 deadbeef 같은 평범한 단어까지 지우지 않는다
_HASH_RE = re.compile(r"\b(?=[0-9a-f]*[0-9])(?=[0-9a-f]*[a-f])[0-9a-f]{7,40}\b", re.ASCII)


def _mask_key(match: re.Match) -> str:
    return match.group(0) if match.group(1) in _NOT_A_KEY_PREFIXES else "KEY-N"


def mask_text(text: str) -> str:
    """같은 문자열(#123, HT-12, URL, 해시)로 짝을 맞히는 법을 배우지 않게 가린다. 키가 없는 쌍에는 소용이 없다.

    URL을 먼저 지운다 — URL 안의 #123·해시를 따로 처리하다 URL이 반쯤 남는 일을 막는다.
    """
    text = _URL_RE.sub("<URL>", text)
    text = _ISSUE_REF_MASK_RE.sub("#N", text)
    text = _JIRA_KEY_RE.sub(_mask_key, text)
    return _HASH_RE.sub("<HASH>", text)


_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}")
# 로그인은 영숫자·하이픈이라 토큰을 거기서 끊는다 — `@junsu님`처럼 한글 조사가 붙어도 멘션이 잡힌다
_MENTION_RE = re.compile(r"(?<![\w@])@([A-Za-z0-9_][A-Za-z0-9_-]*)")


def mask_personal(text: str, logins: set[str]) -> str:
    """이메일과, 이 저장소에서 글을 쓴 사람의 `@로그인`을 가린다.

    로그인 집합에 있는 것만 가려서 `@ManyToOne` 같은 애너테이션은 남는다. 이메일은 앞서 지워야 한다 —
    `a@example.com`의 `@example`을 멘션으로 오인하지 않는다.
    """
    text = _EMAIL_RE.sub("<EMAIL>", text)
    if not logins:
        return text
    return _MENTION_RE.sub(lambda m: "@user" if m.group(1).lower() in logins else m.group(0), text)


_CODE_BLOCK_RE = re.compile(r"```.*?(?:```|\Z)", re.DOTALL)
# 이모지 범위는 코드포인트로 적는다 — 소스에 ZWJ 같은 눈에 안 보이는 문자를 직접 넣지 않으려는 것이다
_EMOJI_RANGES = ((0x1F000, 0x1FAFF), (0x2300, 0x23FF), (0x2600, 0x27BF), (0x2B00, 0x2BFF))
_EMOJI_JOINERS = (0x200D, 0x20E3, 0xFE0F)   # 이모지를 잇는 ZWJ · 키캡 · 변형 선택자
_EMOJI_RE = re.compile("[" + "".join(f"{chr(lo)}-{chr(hi)}" for lo, hi in _EMOJI_RANGES)
                       + "".join(map(chr, _EMOJI_JOINERS)) + "]")
_ACK_PHRASES = ("lgtm", "좋아요", "좋습니다", "감사합니다", "감사해요", "넵", "네", "확인했습니다", "확인했어요",
                "반영했습니다", "반영했어요", "수정했습니다", "수정했어요", "반영 완료")
# 인사·확인 문구만 (뒤에 !.~ㅎㅋ 허용) 한 번 이상 이어진 글 — 20자를 넘겨도 "확인했습니다. 반영했습니다."는 정보가 없다
_ACK_RE = re.compile(r"(?:(?:" + "|".join(re.escape(p) for p in _ACK_PHRASES) + r")[\s!.~,ㅎㅋ]*)+", re.IGNORECASE)


def natural_text(text: str) -> str:
    """코드블록과 이모지를 뺀 글. 코드블록만 붙인 댓글이나 👍만 단 댓글은 대화로서 정보가 없다."""
    text = _CODE_BLOCK_RE.sub(" ", text)
    text = _EMOJI_RE.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def is_conversation_text(text: str) -> bool:
    natural = natural_text(text)
    return len(natural) >= MIN_CONVERSATION_CHARS and not _ACK_RE.fullmatch(natural)


# 사람 계정으로 올라와 봇 판정(user.type·앱 표시)을 피하는 자동 글이다 — Codecov는 일반 사용자 계정, Gatsby Cloud는
# 삭제돼 ghost가 된 계정, AI 리뷰 에이전트는 사람 계정으로 쓴다. 원자료에 작성자를 남기지 않아 글 모양으로 거른다.
# 낱말로는 거르지 않는다("codecov"·"deploy preview"·🤖는 사람 글에도 나온다). 보고서·템플릿은 첫 줄이 정해져 있어
# 첫 줄만 본다 — clean_text가 앞쪽 빈 줄·주석을 걷어내므로 match는 곧 첫 번째 비어 있지 않은 줄의 시작이다.
#   ## [Codecov](...) Report        # [Gatsby Cloud](...) Build Report
#   ## :white_check_mark: team.daangn.com deploy preview ready
#   **Review 2 · Pass**
_MACHINE_FIRST_LINE_RE = re.compile(
    r"#{1,6}[ \t][^\n]*\[(?:Codecov|Gatsby Cloud)\]"
    r"|#{1,6}[ \t]+:[\w+-]+:[ \t]+\S+[ \t]+deploy preview (?:ready|failed)"
    r"|\*\*Review \d+ \N{MIDDLE DOT} (?:Pass|Fail)")
# AI가 쓴 글에 붙는 서명은 본문 끝에 있어 위치를 따지지 않는다. 긴 AI 글은 서명이 4,000자 절단으로 잘려 나가는 쪽에
# 붙으므로 자르기 전의 원문에서 찾는다
_MACHINE_FOOTER_RE = re.compile(r"Generated with \[Claude Code\]")


def is_machine_text(body: str | None) -> bool:
    """봇 판정을 피한 자동 보고서·AI 리뷰·AI 서명 글인가. 첫 줄은 정제한 글로 봐서 주석 뒤에 가려진 첫 줄도 잡고,
    서명은 자르기 전 원문에서 찾는다."""
    return bool(_MACHINE_FIRST_LINE_RE.match(clean_text(body)) or _MACHINE_FOOTER_RE.search(body or ""))


def prepare_conversation_text(body: str | None) -> str | None:
    """대화 글(리뷰·PR·이슈 댓글)을 정리·가림한 뒤 쓸 만하면 돌려주고, 아니면 None.

    가린 뒤에 길이를 잰다 — URL만 붙인 댓글이 원문 길이로는 통과하고 정작 남는 글은 `<URL>`뿐인 일을 막는다.
    """
    text = mask_text(clean_text(body))
    return text if is_conversation_text(text) else None


def prepare_title(title: str | None) -> str | None:
    """PR 제목 앵커. 가린 뒤 8자 미만이면 None."""
    text = mask_text(clean_text(title))
    return text if len(text) >= MIN_ANCHOR_CHARS else None


# review도 wip처럼 낱말일 때만 잡는다 — 부분 문자열로 잡으면 linkpreview·reviewer 같은 기능 커밋이 노이즈로 빠진다
_COMMIT_NOISE_RE = re.compile(r"리뷰 반영|피드백 반영|\breview\b|\bwip\b|fixup!|squash!", re.IGNORECASE | re.ASCII)


def prepare_commit_subject(subject: str | None, is_bot: bool = False) -> tuple[str | None, str | None]:
    """커밋 제목줄 앵커. (글, None) 또는 (None, 탈락 사유: bot·commit_filtered·anchor_short).

    머지·리뷰 반영·WIP 같은 커밋은 "무엇을 왜 바꿨는가"를 말하지 않아 이슈와 짝지어도 잡음이다.
    """
    subject = subject or ""
    if is_bot:
        return None, "bot"
    if subject.startswith("Merge ") or _COMMIT_NOISE_RE.search(subject):
        return None, "commit_filtered"
    text = mask_text(clean_text(subject))
    if len(text) < MIN_ANCHOR_CHARS:
        return None, "anchor_short"
    return text, None


def issue_text(title: str | None, body: str | None) -> str:
    """운영 그래프의 이슈 임베딩 텍스트와 같은 모양 — 제목, 빈 줄, 본문."""
    title = (title or "").strip()
    body = clean_text(body)
    return mask_text(f"{title}\n\n{body}" if body else title)


def format_diff_hunk(diff_hunk: str | None) -> str | None:
    """리뷰 댓글의 diff_hunk를 "[추가] `줄`" / "[제거] `줄`" 형식으로 바꾼다. 변경 줄이 없으면 None.

    운영의 파일 변경 요약("[추가] `afterUsersList` 함수 추가: ...")과 모양을 맞춘다. 마지막 줄이 댓글이 달린
    줄이라 뒤에서 10개를 남긴다. 문맥 줄은 버린다 — 댓글이 가리키는 변경이 아니다.
    """
    changes = []
    for line in (diff_hunk or "").replace("\r\n", "\n").split("\n"):
        if line.startswith("+") and not line.startswith("+++"):
            tag = "추가"
        elif line.startswith("-") and not line.startswith("---"):
            tag = "제거"
        else:
            continue   # 머리줄(@@)·문맥 줄·"\ No newline" 표시
        content = line[1:].strip()
        if content:
            changes.append(f"[{tag}] `{content}`")
    return "\n".join(changes[-MAX_HUNK_LINES:]) if changes else None


def diff_anchor(diff_hunk: str | None) -> str | None:
    """코드 줄에는 URL만 가린다. #123이나 해시 모양은 코드에서는 자주 쓰는 평범한 표기라 지우면 코드가 망가진다."""
    formatted = format_diff_hunk(diff_hunk)
    return None if formatted is None else _URL_RE.sub("<URL>", formatted)


# --------------------------------------------------------------------------- 순수 로직: 이슈 연결

_REF_RE = re.compile(r"(?<![\w/&])#(\d+)(?![A-Za-z0-9_])")
_BRANCH_RE = re.compile(r"(?:^|/)#?(\d+)(?:-|$)")


def extract_issue_refs(title: str | None, body: str | None, head_ref: str | None,
                       issue_numbers: set[int], own_number: int, repo: str) -> list[int]:
    """PR이 가리키는 이슈 번호(오름차순). fetch(커밋을 받을 PR 고르기)와 build(쌍 만들기)가 같은 함수를 쓴다.

    제목·본문의 `#N`, 본문의 같은 저장소 이슈 URL, 브랜치 이름의 번호를 본다. 그 저장소의 (PR 아닌) 이슈 번호일
    때만 인정해서 PR끼리의 언급이나 템플릿의 `ex) #이슈번호`는 걸리지 않는다. 4개 이상을 가리키면 릴리스·묶음
    PR이라 어느 이슈와도 "같은 사안"이 아니므로 빈 목록이다.
    """
    title, body, head_ref = title or "", body or "", head_ref or ""
    found = {int(n) for n in _REF_RE.findall(f"{title}\n{body}")}
    url_re = re.compile(rf"https://github\.com/{re.escape(repo)}/issues/(\d+)", re.IGNORECASE)
    found.update(int(n) for n in url_re.findall(body))
    found.update(int(n) for n in _BRANCH_RE.findall(head_ref))
    refs = sorted(n for n in found if n in issue_numbers and n != own_number)
    return [] if len(refs) > MAX_LINKED_ISSUES else refs


# --------------------------------------------------------------------------- 파일

def read_jsonl(path: str) -> list[dict]:
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def write_jsonl(path: str, rows: list[dict]) -> None:
    with open(path, "w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def write_json(path: str, doc: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")


def _replace_file(path: str, write) -> None:
    """임시 파일에 다 쓴 뒤 바꿔치기한다 — build가 도중에 죽어도 옛 산출물이 반쯤 덮어쓰인 채 남지 않는다."""
    tmp = f"{path}.tmp"
    write(tmp)
    os.replace(tmp, path)


def read_meta(repo_dir: str) -> dict | None:
    try:
        with open(os.path.join(repo_dir, "meta.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def is_complete(repo_dir: str) -> bool:
    meta = read_meta(repo_dir)
    return bool(meta) and meta.get("complete") is True


def raw_dirname(full_name: str) -> str:
    return full_name.replace("/", "__")


def write_raw_repo(raw_root: str, dirname: str, files: dict[str, list[dict]], meta: dict) -> None:
    """임시 디렉터리에 meta.json까지 다 쓴 뒤 rename으로 교체한다.

    meta.json(complete)을 맨 마지막에 쓰고 디렉터리째 바꾸므로, 어디서 끊겨도 "완료 표시가 있는데 파일이
    모자란" 저장소는 생기지 않는다. 옛 디렉터리는 새 것이 다 준비된 뒤에만 치운다(--refresh 중 실패에 대비).
    """
    tmp = os.path.join(raw_root, f".tmp-{dirname}")
    old = os.path.join(raw_root, f".old-{dirname}")
    final = os.path.join(raw_root, dirname)
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    try:
        for name, rows in files.items():
            write_jsonl(os.path.join(tmp, name), rows)
        write_json(os.path.join(tmp, "meta.json"), {**meta, "complete": True})
        shutil.rmtree(old, ignore_errors=True)
        if os.path.isdir(final):
            os.rename(final, old)
        os.rename(tmp, final)
    except BaseException:
        shutil.rmtree(tmp, ignore_errors=True)
        raise
    shutil.rmtree(old, ignore_errors=True)


# --------------------------------------------------------------------------- fetch: 대상 저장소

def list_repos(gh: GhApi, orgs: list[str], repos: list[str] | None, out=_say) -> list[dict]:
    """수집 대상 저장소를 이름 순으로. --repos가 있으면 그것만 단건 조회로 라이선스를 확인한다."""
    found: dict[str, dict] = {}
    if repos:
        for full in repos:
            try:
                found[full.lower()] = gh.get(f"repos/{full}")
            except GhNotFound:
                out(f"경고: {full}을(를) 찾을 수 없어 건너뛴다")
    else:
        for org in orgs:
            try:
                for repo in gh.pages(f"orgs/{org}/repos?type=public&per_page={PER_PAGE}"):
                    found[repo["full_name"].lower()] = repo
            except GhNotFound:
                out(f"경고: 조직 {org}을(를) 찾을 수 없어 건너뛴다")

    targets = []
    excluded = {"fork": 0, "license": 0}
    for repo in found.values():
        if is_collectable_repo(repo):
            targets.append({"full_name": repo["full_name"], "license": license_spdx(repo),
                            "archived": bool(repo.get("archived"))})
        elif repo.get("fork") is not False:
            excluded["fork"] += 1
        else:
            excluded["license"] += 1
    out(f"저장소 {len(found)}개 중 수집 대상 {len(targets)}개 (포크 {excluded['fork']}개·허용 라이선스 밖 "
        f"{excluded['license']}개 제외)")
    return sorted(targets, key=lambda r: repo_sort_key(r["full_name"]))


# --------------------------------------------------------------------------- fetch: 저장소 하나

def _user(item: dict) -> dict:
    return item.get("user") or {}


def _is_bot(item: dict) -> bool:
    return _user(item).get("type") == "Bot"


def _trailing_number(url: str) -> int:
    return int(url.rstrip("/").rsplit("/", 1)[1])


def fetch_repo_data(gh: GhApi, full: str, max_review_pages: int, product_min_issues: int) -> dict:
    """저장소 하나의 API 응답을 모은다 (가림·변환 전).

    이슈 목록을 먼저 받아 제품형 여부를 정해야 PR 목록·이슈 댓글·커밋을 받을지 알 수 있다. 미션 저장소는
    이슈가 거의 없고 리뷰 댓글만 많아서 거기서 PR 목록 전체를 받는 건 호출만 낭비한다.
    """
    try:
        issues = gh.pages(f"repos/{full}/issues?state=all&per_page={PER_PAGE}&sort=created&direction=asc")
    except GhGone:
        issues = []   # 이슈 기능을 끈 저장소 — 이슈 없는 미션형으로 다룬다
    human_issues = [i for i in issues if "pull_request" not in i and not _is_bot(i)]
    product = len(human_issues) >= product_min_issues
    data = {
        "product": product,
        "human_issues": len(human_issues),
        "issues": issues,
        # 오래된 글부터 — 생성형 AI가 리뷰를 대신 쓰기 전의 사람 글이 먼저 들어온다
        "review_comments": gh.pages(f"repos/{full}/pulls/comments?per_page={PER_PAGE}&sort=created&direction=asc",
                                    max_pages=max_review_pages),
        "pulls": [], "issue_comments": [], "commits": {},
    }
    if not product:
        return data
    data["pulls"] = gh.pages(f"repos/{full}/pulls?state=all&per_page={PER_PAGE}&sort=created&direction=asc")
    data["issue_comments"] = gh.pages(f"repos/{full}/issues/comments?per_page={PER_PAGE}&sort=created&direction=asc")
    issue_numbers = {i["number"] for i in human_issues}
    for pr in sorted(data["pulls"], key=lambda p: p["number"]):
        if _is_bot(pr):
            continue
        head_ref = (pr.get("head") or {}).get("ref")
        # 이슈와 연결된 PR만 커밋을 받는다 (PR당 1회 호출). build가 같은 함수로 같은 PR을 고른다
        if extract_issue_refs(pr["title"], pr.get("body"), head_ref, issue_numbers, pr["number"], full):
            data["commits"][pr["number"]] = gh.get(f"repos/{full}/pulls/{pr['number']}/commits?per_page={PER_PAGE}")
    return data


def collect_logins(data: dict) -> set[str]:
    """이 저장소에서 글·커밋을 쓴 사람의 로그인(소문자). 멘션 가림에만 쓰고 어디에도 저장하지 않는다."""
    logins = set()
    for key in ("issues", "review_comments", "pulls", "issue_comments"):
        logins.update(_user(item)["login"].lower() for item in data[key] if _user(item).get("login"))
    for commits in data["commits"].values():
        logins.update(c["author"]["login"].lower() for c in commits if (c.get("author") or {}).get("login"))
    return logins


def to_raw_files(data: dict) -> dict[str, list[dict]]:
    """API 응답을 원자료 행으로 바꾼다. 작성자 필드는 판정 플래그(is_bot 등)만 남기고 버린다.

    멘션 가림은 여기서 한다 — 로그인 목록을 파일에 남기지 않으려고, 목록이 메모리에 있는 이 시점에 끝낸다.
    """
    logins = collect_logins(data)

    def mask(text):
        return mask_personal(text or "", logins)

    files = {
        "issues.jsonl": [
            {"number": i["number"], "title": mask(i["title"]), "body": mask(i.get("body")),
             "created_at": i["created_at"], "is_pr": "pull_request" in i, "is_bot": _is_bot(i)}
            for i in data["issues"]
        ],
        "review_comments.jsonl": [
            {"id": c["id"], "pr_number": _trailing_number(c["pull_request_url"]), "body": mask(c.get("body")),
             "diff_hunk": mask(c.get("diff_hunk")), "path": c.get("path"), "in_reply_to_id": c.get("in_reply_to_id"),
             "created_at": c["created_at"], "is_bot": _is_bot(c), "is_copilot": bool(c.get("is_copilot_code_review"))}
            for c in data["review_comments"]
        ],
    }
    if data["product"]:
        files["pulls.jsonl"] = [
            {"number": p["number"], "title": mask(p["title"]), "body": mask(p.get("body")),
             "head_ref": (p.get("head") or {}).get("ref") or "", "created_at": p["created_at"], "is_bot": _is_bot(p)}
            for p in data["pulls"]
        ]
        files["issue_comments.jsonl"] = [
            {"id": c["id"], "number": _trailing_number(c["issue_url"]), "body": mask(c.get("body")),
             "created_at": c["created_at"],
             # 앱이 단 댓글(CI 보고·릴리스 안내 등)은 사용자 계정 형태여도 사람 글이 아니다
             "is_bot": _is_bot(c) or bool(c.get("performed_via_github_app"))}
            for c in data["issue_comments"]
        ]
        files["pr_commits.jsonl"] = [
            # 제목줄만 저장한다 — 본문의 Co-authored-by 같은 줄에 이름·이메일이 들어 있다
            {"pr_number": number, "subject": mask(c["commit"]["message"].split("\n", 1)[0].strip()),
             "is_bot": (c.get("author") or {}).get("type") == "Bot"}
            for number, commits in sorted(data["commits"].items()) for c in commits
        ]
    return files


def fetch_repo(gh: GhApi, repo: dict, raw_root: str, max_review_pages: int, product_min_issues: int) -> dict:
    """저장소 하나를 받아 원자료 디렉터리로 쓰고, 진행 줄에 쓸 요약을 돌려준다."""
    full = repo["full_name"]
    data = fetch_repo_data(gh, full, max_review_pages, product_min_issues)
    files = to_raw_files(data)
    counts = {name[:-len(".jsonl")]: len(rows) for name, rows in files.items()}
    counts["human_issues"] = data["human_issues"]
    meta = {
        "repo": full, "license": repo["license"], "archived": repo["archived"], "product": data["product"],
        "fetched_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "counts": counts,
        "params": {"max_review_pages": max_review_pages, "product_min_issues": product_min_issues},
    }
    write_raw_repo(raw_root, raw_dirname(full), files, meta)
    return {"product": data["product"], "counts": counts}


def fetch_all(gh: GhApi, *, orgs: list[str], repos: list[str] | None, data_dir: str, max_review_pages: int,
              product_min_issues: int, refresh: bool, out=_say) -> dict:
    """대상 저장소를 차례로 받는다. 한 저장소의 실패가 나머지를 막지 않게 하고, 끝에 실패 목록을 알린다."""
    targets = list_repos(gh, orgs, repos, out)
    raw_root = os.path.join(data_dir, "raw")
    os.makedirs(raw_root, exist_ok=True)
    summary = {"fetched": 0, "already_complete": 0, "not_found": [], "failed": []}
    for n, repo in enumerate(targets, 1):
        full = repo["full_name"]
        label = f"[{n}/{len(targets)}] {full}"
        if not refresh and is_complete(os.path.join(raw_root, raw_dirname(full))):
            out(f"{label} — 완료 표시가 있어 건너뜀")
            summary["already_complete"] += 1
            continue
        started = time.monotonic()
        try:
            done = fetch_repo(gh, repo, raw_root, max_review_pages, product_min_issues)
        except GhNotFound as e:
            out(f"{label} — 경고: 404라 건너뜀 ({e})")
            summary["not_found"].append(full)
            continue
        except GhError as e:
            out(f"{label} — 실패, 다음 저장소로 넘어감 ({e})")
            summary["failed"].append(full)
            continue
        counts = done["counts"]
        out(f"{label} — {'제품형' if done['product'] else '미션형'} · 이슈 {counts['issues']}"
            f"(사람 이슈 {counts['human_issues']}) · 리뷰 댓글 {counts['review_comments']}"
            + (f" · PR {counts['pulls']} · 이슈 댓글 {counts['issue_comments']} · 커밋 {counts['pr_commits']}"
               if done["product"] else "")
            + f" · {time.monotonic() - started:.1f}초")
        summary["fetched"] += 1
    out(f"fetch 끝: 새로 받음 {summary['fetched']} · 완료 표시로 건너뜀 {summary['already_complete']} · "
        f"404 {len(summary['not_found'])} · 실패 {len(summary['failed'])}")
    if summary["failed"]:
        out(f"실패한 저장소는 다시 실행하면 이어서 받는다: {', '.join(summary['failed'])}")
    return summary


# --------------------------------------------------------------------------- build: 상한·시드

def derive_rng(seed: int, repo: str, type_: str, kind: str, number="") -> random.Random:
    """단위(PR·이슈·저장소×type)마다 따로 파생한 시드. 저장소를 어떤 순서로 처리해도, 다른 저장소가 늘어도
    한 단위의 선택은 바뀌지 않는다. 문자열 시드는 PYTHONHASHSEED와 무관하게 결정적이다."""
    return random.Random(f"{seed}:{repo}:{type_}:{kind}:{number}")


def seeded_pick(items: list, limit: int, rng: random.Random) -> tuple[list, int]:
    """정렬된 items에서 limit개를 시드로 고르되 원래 순서를 지킨다. (고른 것, 버린 수)."""
    if len(items) <= limit:
        return items, 0
    chosen = sorted(rng.sample(range(len(items)), limit))
    return [items[i] for i in chosen], len(items) - limit


def normalize(text: str) -> str:
    return " ".join(text.lower().split())


def pair_id(repo: str, type_: str, anchor_kind: str, positive_kind: str, source: dict, anchor: str,
            positive: str) -> str:
    payload = json.dumps([repo, type_, anchor_kind, positive_kind, source["kind"], source["number"], anchor, positive],
                         ensure_ascii=False)
    return hashlib.sha1(payload.encode("utf-8")).hexdigest()[:16]


_HUNK_TAG_RE = re.compile(r"^\[(?:추가|제거)\] ", re.MULTILINE)


def has_korean(anchor_kind: str, anchor: str, positive: str) -> bool:
    """앵커나 짝에 한글이 있는가. 묶음 E가 한국어를 섞는 비율을 정할 때 쓰는 표지다.

    diff_hunk 앵커의 "[추가]"·"[제거]"는 우리가 붙인 글자다. 그대로 세면 영어뿐인 저장소의 코드 앵커 쌍도
    전부 한국어가 되어 표지가 아무것도 가리지 못하므로, 표지를 떼고 코드 줄의 내용만 본다.
    """
    if anchor_kind == "diff_hunk":
        anchor = _HUNK_TAG_RE.sub("", anchor)
    return bool(re.search("[가-힣]", anchor + positive))


def _candidate(type_, anchor_kind, positive_kind, anchor, positive, source_kind, source_number, created_at, sort_key):
    return {"type": type_, "anchor_kind": anchor_kind, "positive_kind": positive_kind, "anchor": anchor,
            "positive": positive, "source": {"kind": source_kind, "number": source_number},
            "created_at": created_at, "sort_key": sort_key}


def _cap_per_unit(groups: dict, cap: int, seed: int, repo: str, type_: str, kind: str, reason: str,
                  drops: dict) -> list[dict]:
    """PR·이슈 단위로 후보를 cap개까지만 남긴다. 단위 번호 순으로 돌며 단위마다 자기 시드로 고른다."""
    kept = []
    for number in sorted(groups):
        items = sorted(groups[number], key=lambda c: c["sort_key"])
        picked, dropped = seeded_pick(items, cap, derive_rng(seed, repo, type_, kind, number))
        drops[reason] += dropped
        kept.extend(picked)
    return kept


# --------------------------------------------------------------------------- build: 후보 생성
# 거르는 순서는 모든 쌍 종류에서 같다: 글쓴이 플래그(copilot → bot) → 기계 글(machine) → 짝 글(short_or_ack) → 앵커.
# 한 후보는 처음 걸린 사유 하나로만 집계된다. 기계 글은 플래그를 피한 자동 글이라 플래그 다음에 보고, 짧은 보고서가
# short_or_ack로 섞여 세어지지 않게 짧은 글 판정보다 앞에 둔다. 짝 글(대화)에만 적용하고 이슈 본문·PR 제목 앵커는 거르지 않는다.

def _review_candidates(raw: dict, seed: int, cap: int, drops: dict) -> list[dict]:
    repo = raw["meta"]["repo"]
    by_pr: dict[int, list] = {}
    for c in raw["review_comments"]:
        if c.get("is_copilot"):
            drops["copilot"] += 1
            continue
        if c.get("is_bot"):
            drops["bot"] += 1
            continue
        if is_machine_text(c.get("body")):
            drops["machine"] += 1
            continue
        positive = prepare_conversation_text(c.get("body"))
        if positive is None:
            drops["short_or_ack"] += 1
            continue
        anchor = diff_anchor(c.get("diff_hunk"))
        if anchor is None:
            drops["no_changed_lines"] += 1
            continue
        by_pr.setdefault(c["pr_number"], []).append(_candidate(
            "change_conversation", "diff_hunk", "review_comment", anchor, positive, "pr", c["pr_number"],
            c["created_at"], (c["pr_number"], 0, c["id"], 0)))
    return _cap_per_unit(by_pr, cap, seed, repo, "change_conversation", "review_comment", "per_pr_cap", drops)


def _pr_comment_candidates(raw: dict, seed: int, cap: int, drops: dict) -> list[dict]:
    repo = raw["meta"]["repo"]
    pulls = {p["number"]: p for p in raw["pulls"]}
    titles: dict[int, str | None] = {}
    by_pr: dict[int, list] = {}
    for c in raw["issue_comments"]:
        pr = pulls.get(c["number"])
        if pr is None:
            continue   # 이슈에 달린 댓글 — issue_comment 쪽에서 본다
        if pr["is_bot"] or c["is_bot"]:
            drops["bot"] += 1
            continue
        if is_machine_text(c["body"]):
            drops["machine"] += 1
            continue
        positive = prepare_conversation_text(c["body"])
        if positive is None:
            drops["short_or_ack"] += 1
            continue
        if pr["number"] not in titles:
            titles[pr["number"]] = prepare_title(pr["title"])
        if titles[pr["number"]] is None:
            drops["anchor_short"] += 1
            continue
        by_pr.setdefault(pr["number"], []).append(_candidate(
            "change_conversation", "pr_title", "pr_comment", titles[pr["number"]], positive, "pr", pr["number"],
            c["created_at"], (pr["number"], 1, c["id"], 0)))
    return _cap_per_unit(by_pr, cap, seed, repo, "change_conversation", "pr_comment", "per_pr_cap", drops)


def _change_issue_candidates(raw: dict, seed: int, cap: int, drops: dict) -> list[dict]:
    repo = raw["meta"]["repo"]
    issues = {i["number"]: i for i in raw["issues"] if not i["is_pr"] and not i["is_bot"]}
    commits: dict[int, list] = {}
    for c in raw["pr_commits"]:
        commits.setdefault(c["pr_number"], []).append(c)
    out = []
    for pr in sorted(raw["pulls"], key=lambda p: p["number"]):
        refs = extract_issue_refs(pr["title"], pr["body"], pr["head_ref"], set(issues), pr["number"], repo)
        if not refs:
            continue
        if pr["is_bot"]:
            drops["bot"] += len(refs)   # 봇이 연 PR의 제목은 쓰지 않는다
            continue
        number = pr["number"]
        title = prepare_title(pr["title"])
        if title is None:
            drops["anchor_short"] += len(refs)
        else:
            out.extend(_candidate("change_issue", "pr_title", "issue", title, issue_text(issues[n]["title"],
                       issues[n]["body"]), "pr", number, issues[n]["created_at"], (number, 0, "", n)) for n in refs)
        subjects = []
        for commit in commits.get(number, []):
            subject, reason = prepare_commit_subject(commit["subject"], commit["is_bot"])
            if subject is None:
                drops[reason] += len(refs)
            else:
                subjects.append(subject)
        # 파일 안의 순서가 아니라 글로 정렬해 둔다 — 원자료 행 순서가 바뀌어도 같은 커밋이 뽑힌다
        picked, dropped = seeded_pick(sorted(subjects), cap, derive_rng(seed, repo, "change_issue",
                                                                        "commit_message", number))
        drops["per_pr_cap"] += dropped * len(refs)
        out.extend(_candidate("change_issue", "commit_message", "issue", subject, issue_text(issues[n]["title"],
                   issues[n]["body"]), "pr", number, issues[n]["created_at"], (number, 1, subject, n))
                   for subject in picked for n in refs)
    return out


def _issue_comment_candidates(raw: dict, seed: int, cap: int, drops: dict) -> list[dict]:
    repo = raw["meta"]["repo"]
    issues = {i["number"]: i for i in raw["issues"] if not i["is_pr"]}
    anchors: dict[int, str] = {}
    by_issue: dict[int, list] = {}
    for c in raw["issue_comments"]:
        issue = issues.get(c["number"])
        if issue is None:
            continue   # PR 대화 댓글 — pr_comment 쪽에서 본다
        if issue["is_bot"] or c["is_bot"]:
            drops["bot"] += 1
            continue
        if is_machine_text(c["body"]):
            drops["machine"] += 1
            continue
        positive = prepare_conversation_text(c["body"])
        if positive is None:
            drops["short_or_ack"] += 1
            continue
        if issue["number"] not in anchors:
            anchors[issue["number"]] = issue_text(issue["title"], issue["body"])
        by_issue.setdefault(issue["number"], []).append(_candidate(
            "issue_conversation", "issue", "issue_comment", anchors[issue["number"]], positive, "issue",
            issue["number"], c["created_at"], (issue["number"], 0, c["id"], 0)))
    return _cap_per_unit(by_issue, cap, seed, repo, "issue_conversation", "issue_comment", "per_issue_cap", drops)


def generate_candidates(raw: dict, seed: int, caps: dict, drops: dict) -> dict[str, list[dict]]:
    """저장소 하나의 쌍 후보를 type별로 만든다. 봇 글을 거르고 PR·이슈당 상한까지 적용한 상태다."""
    by_type = {t: [] for t in TYPE_ORDER}
    by_type["change_conversation"] += _review_candidates(raw, seed, caps["review"], drops)
    if raw["meta"].get("product"):
        by_type["change_conversation"] += _pr_comment_candidates(raw, seed, caps["conversation"], drops)
        by_type["change_issue"] += _change_issue_candidates(raw, seed, caps["commit"], drops)
        by_type["issue_conversation"] += _issue_comment_candidates(raw, seed, caps["issue_comment"], drops)
    return by_type


# --------------------------------------------------------------------------- build: 전체

def build_pairs(repos: list[dict], *, seed: int, caps: dict, val_repos: set[str]) -> tuple[list[dict], dict]:
    """원자료 전체에서 쌍을 만든다. 반환: (쌍 목록, 저장소별 매니페스트).

    순서: 후보 생성 → PR·이슈당 상한 → 중복 제거 → 저장소×type당 상한 → 분할. 중복 제거의 "먼저 나온 것"은
    저장소 이름 순 → 안정 키 순으로 정해서, 같은 원자료·같은 시드면 출력이 같다.
    """
    seen_talk: set[str] = set()                      # 대화형 짝 글 (저장소를 가로질러 — 미션 저장소의 반복 피드백)
    seen_change: set[tuple[str, str]] = set()        # 변경↔이슈는 (앵커, 짝)이 같을 때만 중복
    pairs: list[dict] = []
    report: dict[str, dict] = {}
    for raw in sorted(repos, key=lambda r: repo_sort_key(r["meta"]["repo"])):
        meta = raw["meta"]
        repo = meta["repo"]
        drops = dict.fromkeys(DROP_REASONS, 0)
        split = "val" if repo.lower() in val_repos else "train"
        counts: dict[str, dict[str, int]] = {}
        report[repo] = {"license": meta.get("license"), "product": bool(meta.get("product")), "split": split,
                        "pairs": counts, "dropped": drops}
        if not is_allowed_license(meta.get("license")):
            drops["license"] = 1   # 이 단위는 저장소다 — 후보를 만들기 전에 막으므로 글 단위 집계가 없다
            continue

        by_type = generate_candidates(raw, seed, caps, drops)
        for type_ in TYPE_ORDER:
            kept = []
            for cand in sorted(by_type[type_], key=lambda c: c["sort_key"]):
                if type_ == "change_issue":
                    key, seen = (normalize(cand["anchor"]), normalize(cand["positive"])), seen_change
                else:
                    key, seen = normalize(cand["positive"]), seen_talk
                if key in seen:
                    drops["duplicate"] += 1
                    continue
                seen.add(key)
                kept.append(cand)
            kept, dropped = seeded_pick(kept, caps["repo_type"], derive_rng(seed, repo, type_, "repo_type_cap"))
            drops["repo_type_cap"] += dropped
            for cand in kept:
                pairs.append({
                    "id": pair_id(repo, type_, cand["anchor_kind"], cand["positive_kind"], cand["source"],
                                  cand["anchor"], cand["positive"]),
                    "type": type_, "anchor_kind": cand["anchor_kind"], "anchor": cand["anchor"],
                    "positive_kind": cand["positive_kind"], "positive": cand["positive"], "repo": repo,
                    "split": split,
                    "has_korean": has_korean(cand["anchor_kind"], cand["anchor"], cand["positive"]),
                    "source": cand["source"], "created_at": cand["created_at"],
                })
                by_kind = counts.setdefault(type_, {})
                by_kind[cand["anchor_kind"]] = by_kind.get(cand["anchor_kind"], 0) + 1
    pairs.sort(key=lambda p: (p["repo"], p["type"], p["id"]))
    return pairs, report


def build_manifest(pairs: list[dict], report: dict, *, seed: int, params: dict, val_repos: list[str],
                   incomplete: list[str]) -> dict:
    totals: dict[str, dict] = {}
    for split in ("train", "val"):
        rows = [p for p in pairs if p["split"] == split]
        totals[split] = {"pairs": len(rows),
                         "by_type": {t: sum(1 for p in rows if p["type"] == t) for t in TYPE_ORDER}}
    korean = {}
    for split in ("train", "val"):
        rows = [p for p in pairs if p["split"] == split]
        korean[split] = round(sum(p["has_korean"] for p in rows) / len(rows), 4) if rows else None
    return {"created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "seed": seed, "args": params,
            "val_repos": val_repos, "incomplete_repos": incomplete, "repos": report, "totals": totals,
            "has_korean_ratio": korean}


def load_raw_repos(raw_root: str) -> tuple[list[dict], list[str]]:
    """완료 표시가 있는 원자료만 읽는다. 반환: (저장소 목록, 완료 표시 없어 건너뛴 디렉터리 이름)."""
    repos, incomplete = [], []
    if not os.path.isdir(raw_root):
        return repos, incomplete
    for name in sorted(os.listdir(raw_root)):
        path = os.path.join(raw_root, name)
        if name.startswith(".") or not os.path.isdir(path):
            continue   # .tmp-·.old- 같은 fetch의 작업 디렉터리
        meta = read_meta(path)
        if not meta or meta.get("complete") is not True:
            incomplete.append(name)
            continue
        repos.append({"meta": meta, **{key: read_jsonl(os.path.join(path, f"{key}.jsonl")) for key in
                                       ("issues", "review_comments", "pulls", "issue_comments", "pr_commits")}})
    return repos, incomplete


# --------------------------------------------------------------------------- sample

def sample_pairs(pairs: list[dict], n: int, seed: int, split: str) -> list[dict]:
    """(type, anchor_kind) 층마다 n을 고르게 나눠 뽑는다. 나머지는 정렬 순 앞 층부터 하나씩 더 준다."""
    strata: dict[tuple[str, str], list] = {}
    for pair in pairs:
        strata.setdefault((pair["type"], pair["anchor_kind"]), []).append(pair)
    keys = sorted(strata)
    if not keys:
        return []
    base, extra = divmod(n, len(keys))
    chosen = []
    for index, key in enumerate(keys):
        quota = base + (1 if index < extra else 0)
        items = sorted(strata[key], key=lambda p: p["id"])
        picked, _ = seeded_pick(items, quota, random.Random(f"{seed}:{split}:{key[0]}:{key[1]}"))
        chosen.extend(picked)
    return chosen


# --------------------------------------------------------------------------- main

def _split_csv(value: str | None) -> list[str]:
    return [item.strip() for item in (value or "").split(",") if item.strip()]


def _fetch(args: argparse.Namespace, gh: GhApi | None) -> None:
    repos = _split_csv(args.repos)
    for full in repos:
        if not re.fullmatch(r"[\w.-]+/[\w.-]+", full):
            sys.exit(f"--repos는 owner/name 형식이어야 한다: {full!r}")
    summary = fetch_all(gh or GhApi(), orgs=_split_csv(args.orgs), repos=repos or None, data_dir=args.data_dir,
                        max_review_pages=args.max_review_pages, product_min_issues=args.product_min_issues,
                        refresh=args.refresh)
    if summary["failed"]:
        sys.exit(1)


def _build(args: argparse.Namespace, gh: GhApi | None) -> None:
    raw_root = os.path.join(args.data_dir, "raw")
    repos, incomplete = load_raw_repos(raw_root)
    if not repos:
        sys.exit(f"{raw_root}에 완료 표시가 있는 원자료가 없다 — 먼저 fetch를 실행한다")
    for name in incomplete:
        print(f"경고: {name}은(는) 완료 표시가 없어 건너뛴다 (fetch를 다시 실행하면 이어 받는다)")
    val_repos = _split_csv(args.val_repos)
    known = {r["meta"]["repo"].lower() for r in repos}
    for full in val_repos:
        if full.lower() not in known:
            print(f"경고: 검증 저장소 {full}의 원자료가 없다 — 이 저장소의 쌍은 val에 들어가지 않는다")
    caps = {"review": args.per_pr_review_cap, "conversation": args.per_pr_conversation_cap,
            "issue_comment": args.per_issue_comment_cap, "commit": args.per_pr_commit_cap,
            "repo_type": args.per_repo_type_cap}
    pairs, report = build_pairs(repos, seed=args.seed, caps=caps, val_repos={v.lower() for v in val_repos})
    # 매니페스트에는 나중에 같은 결과를 다시 만들 때 그대로 칠 수 있게 CLI 인자 이름으로 남긴다
    params = {"per_pr_review_cap": args.per_pr_review_cap, "per_pr_conversation_cap": args.per_pr_conversation_cap,
              "per_issue_comment_cap": args.per_issue_comment_cap, "per_pr_commit_cap": args.per_pr_commit_cap,
              "per_repo_type_cap": args.per_repo_type_cap}
    manifest = build_manifest(pairs, report, seed=args.seed, params=params, val_repos=val_repos, incomplete=incomplete)

    os.makedirs(args.data_dir, exist_ok=True)
    for split in ("train", "val"):
        rows = [p for p in pairs if p["split"] == split]
        _replace_file(os.path.join(args.data_dir, f"pairs-{split}.jsonl"), lambda tmp, rows=rows: write_jsonl(tmp, rows))
    _replace_file(os.path.join(args.data_dir, "pairs-manifest.json"), lambda tmp: write_json(tmp, manifest))

    print(f"원자료 저장소 {len(repos)}개 → 쌍 {len(pairs)}개")
    for split in ("train", "val"):
        total = manifest["totals"][split]
        by_type = " · ".join(f"{t} {n}" for t, n in total["by_type"].items())
        ratio = manifest["has_korean_ratio"][split]
        print(f"  {split}: {total['pairs']}쌍 ({by_type}) · 한글 포함 {'-' if ratio is None else f'{ratio:.1%}'}")
    dropped = {r: sum(v["dropped"][r] for v in report.values()) for r in DROP_REASONS}
    print("  탈락: " + (" · ".join(f"{r} {n}" for r, n in dropped.items() if n) or "없음"))
    print(f"→ {args.data_dir}/pairs-train.jsonl · pairs-val.jsonl · pairs-manifest.json")


def _sample(args: argparse.Namespace, gh: GhApi | None) -> None:
    source = os.path.join(args.data_dir, f"pairs-{args.split}.jsonl")
    if not os.path.exists(source):
        sys.exit(f"{source}이(가) 없다 — 먼저 build를 실행한다")
    pairs = read_jsonl(source)
    chosen = sample_pairs(pairs, args.n, args.seed, args.split)
    out = os.path.join(args.data_dir, f"pairs-sample{args.n}.jsonl")
    _replace_file(out, lambda tmp: write_jsonl(tmp, chosen))
    strata = {}
    for pair in chosen:
        strata[(pair["type"], pair["anchor_kind"])] = strata.get((pair["type"], pair["anchor_kind"]), 0) + 1
    print(f"{args.split} 쌍 {len(pairs)}개에서 {len(chosen)}개 → {out}")
    for (type_, kind), count in sorted(strata.items()):
        print(f"  {type_}/{kind}: {count}")
    if len(chosen) < args.n:
        print(f"  ⚠ 요청한 {args.n}개보다 적다 — 쌍이 모자란 층은 있는 만큼만 뽑았다")


def main(argv: list[str] | None = None, gh: GhApi | None = None) -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("fetch", help="저장소 목록을 라이선스로 거르고 원자료를 받는다")
    fetch.add_argument("--orgs", default=DEFAULT_ORGS, help=f"조직 목록, 쉼표 구분 (기본 {DEFAULT_ORGS})")
    fetch.add_argument("--repos", help="owner/name,... — 지정하면 이 저장소만 받는다 (단건 조회로 라이선스 확인)")
    fetch.add_argument("--data-dir", default=DEFAULT_DATA_DIR, help="데이터 디렉터리 (기본 eval/embedding_finetune/data)")
    fetch.add_argument("--max-review-pages", type=int, default=30, help="저장소당 리뷰 댓글 최대 쪽 수 (쪽당 100건, 기본 30)")
    fetch.add_argument("--product-min-issues", type=int, default=20,
                       help="PR·봇이 아닌 이슈가 이 수 이상이면 제품형 저장소 (기본 20)")
    fetch.add_argument("--refresh", action="store_true", help="완료 표시가 있어도 다시 받는다")
    fetch.set_defaults(run=_fetch)

    build = sub.add_parser("build", help="원자료로 쌍을 만든다 (네트워크 없음)")
    build.add_argument("--data-dir", default=DEFAULT_DATA_DIR)
    build.add_argument("--seed", type=int, default=DEFAULT_SEED)
    build.add_argument("--per-pr-review-cap", type=int, default=3, help="PR당 코드↔리뷰 댓글 쌍 상한 (기본 3)")
    build.add_argument("--per-pr-conversation-cap", type=int, default=3, help="PR당 PR 제목↔대화 댓글 쌍 상한 (기본 3)")
    build.add_argument("--per-issue-comment-cap", type=int, default=3, help="이슈당 이슈↔댓글 쌍 상한 (기본 3)")
    build.add_argument("--per-pr-commit-cap", type=int, default=3, help="연결 PR당 커밋 제목 앵커 상한 (기본 3)")
    build.add_argument("--per-repo-type-cap", type=int, default=2000, help="저장소×type당 쌍 상한 (기본 2000)")
    build.add_argument("--val-repos", default=DEFAULT_VAL_REPOS, help=f"검증 전용 저장소 (기본 {DEFAULT_VAL_REPOS})")
    build.set_defaults(run=_build)

    sample = sub.add_parser("sample", help="(type, anchor_kind) 층화 표본을 뽑아 사람이 읽어 본다")
    sample.add_argument("--n", type=int, default=50, help="표본 크기 (기본 50)")
    sample.add_argument("--seed", type=int, default=DEFAULT_SEED)
    sample.add_argument("--split", choices=("train", "val"), default="train")
    sample.add_argument("--data-dir", default=DEFAULT_DATA_DIR)
    sample.set_defaults(run=_sample)

    args = ap.parse_args(argv)
    args.run(args, gh)


if __name__ == "__main__":
    main()
