"""collect_pairs.py 단위 테스트 (오프라인 — gh 호출은 주입한 가짜 runner, 파일 입출력은 tmp_path).

이 스크립트가 틀리면 학습 데이터가 조용히 오염된다. 허용 라이선스 밖의 글이 섞이거나, 봇·Copilot 글이 짝으로
들어가거나, 작성자 정보·`#123` 같은 키가 남아 "같은 문자열로 맞히는 법"을 배우거나, 상한·중복 규칙이 틀려 한 저장소의
반복 피드백이 데이터를 채워도 에러 없이 JSONL만 만들어진다. 그래서 경계와 방향을 값으로 고정한다.

실행: services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import json
import os
import random
import re
import subprocess
import sys
from urllib.parse import parse_qs, urlsplit

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "embedding_finetune"))

import collect_pairs as cp  # noqa: E402

FORBIDDEN_KEY_PARTS = ("user", "login", "author", "email", "name")


def all_keys(node):
    """JSON 문서 안의 모든 키 (중첩 포함)."""
    if isinstance(node, dict):
        for key, value in node.items():
            yield key
            yield from all_keys(value)
    elif isinstance(node, list):
        for item in node:
            yield from all_keys(item)


def assert_no_author_keys(node):
    bad = [k for k in all_keys(node) if any(part in k.lower() for part in FORBIDDEN_KEY_PARTS)]
    assert bad == []


def read_jsonl(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


# ─── gh 래퍼 도우미 ─────────────────────────────────────────────────────────────

RATE_OK = {"resources": {"core": {"remaining": 5000, "reset": 0}}}


def _ok(data):
    return subprocess.CompletedProcess(["gh", "api"], 0, stdout=json.dumps(data, ensure_ascii=False), stderr="")


def _err(stderr, code=1):
    return subprocess.CompletedProcess(["gh", "api"], code, stdout="", stderr=stderr)


class Scripted:
    """경로와 무관하게 준비한 응답을 차례로 돌려주는 가짜 runner. rate_limit만 따로 응답한다."""

    def __init__(self, responses, rate=None):
        self.responses = list(responses)
        self.rate = list(rate or [])
        self.calls = []

    def __call__(self, path):
        self.calls.append(path)
        if path == "rate_limit":
            return _ok(self.rate.pop(0) if self.rate else RATE_OK)
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def make_api(runner, now=1000.0):
    sleeps = []
    return cp.GhApi(runner=runner, sleep=sleeps.append, clock=lambda: now, log=lambda *_: None), sleeps


# ─── gh 래퍼 — 재시도 · 한도 · 404 ──────────────────────────────────────────────


def test_gh_retries_5xx_with_exponential_backoff_then_succeeds():
    runner = Scripted([_err("gh: Bad Gateway (HTTP 502)"), _err("gh: Server Error (HTTP 500)"), _ok([1])])
    api, sleeps = make_api(runner)

    assert api.get("repos/o/r/x") == [1]
    assert sleeps == [2, 4]


def test_gh_gives_up_after_five_retries_with_the_full_backoff_schedule():
    runner = Scripted([_err("gh: Bad Gateway (HTTP 502)")] * 6)
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhError) as raised:
        api.get("repos/o/r/x")

    assert not isinstance(raised.value, cp.GhNotFound)
    assert sleeps == [2, 4, 8, 16, 32]
    assert runner.calls.count("repos/o/r/x") == 6


def test_gh_succeeds_on_the_last_allowed_attempt():
    runner = Scripted([_err("gh: Service Unavailable (HTTP 503)")] * 5 + [_ok({"a": 1})])
    api, sleeps = make_api(runner)

    assert api.get("repos/o/r/x") == {"a": 1}
    assert sleeps == [2, 4, 8, 16, 32]


def test_gh_retries_a_timeout_like_a_5xx():
    runner = Scripted([subprocess.TimeoutExpired("gh", 120), _ok([])])
    api, sleeps = make_api(runner)

    assert api.get("repos/o/r/x") == []
    assert sleeps == [2]


@pytest.mark.parametrize("status", [403, 429])
def test_gh_waits_until_reset_plus_five_seconds_on_rate_limit_then_retries(status):
    runner = Scripted([_err(f"gh: API rate limit exceeded for user ID 1. (HTTP {status})"), _ok([7])],
                      rate=[RATE_OK, {"resources": {"core": {"remaining": 0, "reset": 1030}}}])
    api, sleeps = make_api(runner, now=1000.0)

    assert api.get("repos/o/r/x") == [7]
    assert sleeps == [35]                     # reset까지 30초 + 여유 5초. 백오프 2초는 더하지 않는다


def test_gh_rate_limit_wait_is_only_the_margin_when_reset_already_passed():
    runner = Scripted([_err("gh: secondary rate limit (HTTP 403)"), _ok([])],
                      rate=[RATE_OK, {"resources": {"core": {"remaining": 4000, "reset": 900}}}])
    api, sleeps = make_api(runner, now=1000.0)

    api.get("repos/o/r/x")

    assert sleeps == [5]


def test_gh_does_not_retry_a_403_that_is_not_a_rate_limit():
    runner = Scripted([_err("gh: Resource not accessible by integration (HTTP 403)")])
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhError):
        api.get("repos/o/r/x")

    assert sleeps == []
    assert runner.calls.count("repos/o/r/x") == 1


def test_gh_rate_limit_retries_are_bounded():
    # 한도가 안 풀리는 채로 계속 403이면 영원히 기다리지 않고 포기한다
    runner = Scripted([_err("gh: API rate limit exceeded (HTTP 403)")] * 6)
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhError):
        api.get("repos/o/r/x")

    assert runner.calls.count("repos/o/r/x") == 6


def test_gh_404_raises_not_found_without_retrying():
    runner = Scripted([_err("gh: Not Found (HTTP 404)")])
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhNotFound):
        api.get("repos/o/gone")

    assert sleeps == [] and runner.calls.count("repos/o/gone") == 1


def test_gh_410_raises_gone_without_retrying():
    runner = Scripted([_err("gh: Issues are disabled for this repo (HTTP 410)")])
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhGone):
        api.get("repos/o/r/issues")

    assert sleeps == []


NETWORK_ERROR = 'Get "https://api.github.com/repos/o/r/x": dial tcp: lookup api.github.com: no such host'


def test_gh_retries_a_failure_without_an_http_status_with_the_same_backoff_and_returns_the_value():
    # DNS 실패·연결 끊김 같은 네트워크 오류는 stderr에 HTTP 상태가 없다 — 약 1시간 도는 동안 와이파이가 잠깐 끊긴 것일 수 있다
    runner = Scripted([_err(NETWORK_ERROR), _err("gh: dial tcp 140.82.113.6:443: connect: network is unreachable"),
                       _ok([1])])
    api, sleeps = make_api(runner)

    assert api.get("repos/o/r/x") == [1]
    assert sleeps == [2, 4]


def test_gh_failure_without_an_http_status_raises_only_after_using_every_retry():
    runner = Scripted([_err(NETWORK_ERROR)] * 6)
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhError) as raised:
        api.get("repos/o/r/x")

    assert not isinstance(raised.value, (cp.GhNotFound, cp.GhGone))
    assert sleeps == [2, 4, 8, 16, 32]
    assert runner.calls.count("repos/o/r/x") == 6
    assert "no such host" in str(raised.value)                 # 마지막 실패 사유가 메시지에 남는다


@pytest.mark.parametrize("stderr", ["gh: Bad credentials (HTTP 401)", "gh: Validation Failed (HTTP 422)"])
def test_gh_still_fails_at_once_on_other_4xx(stderr):
    # 상태가 없는 실패를 재시도하게 바꿔도, 상태가 있는 4xx는 다시 보내도 같은 답이라 재시도하지 않는다
    runner = Scripted([_err(stderr)])
    api, sleeps = make_api(runner)

    with pytest.raises(cp.GhError):
        api.get("repos/o/r/x")

    assert sleeps == []
    assert runner.calls.count("repos/o/r/x") == 1


def test_gh_rejects_a_success_with_a_body_that_is_not_json():
    api, _ = make_api(Scripted([subprocess.CompletedProcess(["gh"], 0, stdout="<html>", stderr="")]))

    with pytest.raises(cp.GhError):
        api.get("repos/o/r/x")


def test_gh_checks_rate_limit_before_the_first_and_every_hundredth_request():
    runner = Scripted([_ok([])] * 201)
    api, _ = make_api(runner)

    for _ in range(201):
        api.get("repos/o/r/x")

    assert runner.calls.count("rate_limit") == 3
    assert runner.calls[0] == "rate_limit" and runner.calls[101] == "rate_limit" and runner.calls[202] == "rate_limit"


@pytest.mark.parametrize("remaining, waits", [(99, True), (100, False)])
def test_gh_waits_until_reset_only_when_remaining_is_below_100(remaining, waits):
    runner = Scripted([_ok([])], rate=[{"resources": {"core": {"remaining": remaining, "reset": 1010}}}])
    api, sleeps = make_api(runner, now=1000.0)

    api.get("repos/o/r/x")

    assert sleeps == ([15] if waits else [])


FAKE_GH = """#!/bin/sh
# 가짜 gh — 네트워크를 쓰지 않고 호출 인자만 보고 응답한다
if [ "$1" != "api" ]; then echo "gh: unexpected command $1" >&2; exit 2; fi
case "$2" in
  rate_limit) echo '{"resources": {"core": {"remaining": 5000, "reset": 0}}}' ;;
  "repos/o/r?x=1&page=2") echo '{"제목": "한글 본문"}' ;;
  *) echo "gh: Not Found (HTTP 404)" >&2; exit 1 ;;
esac
"""


@pytest.mark.skipif(sys.platform == "win32", reason="sh 스크립트로 만든 가짜 gh")
def test_default_runner_calls_gh_api_with_the_path_and_decodes_utf8(tmp_path, monkeypatch):
    fake = tmp_path / "gh"
    fake.write_text(FAKE_GH, encoding="utf-8")
    fake.chmod(0o755)
    monkeypatch.setenv("PATH", f"{tmp_path}{os.pathsep}{os.environ['PATH']}")
    monkeypatch.setenv("LC_ALL", "C")                      # 로캘이 UTF-8이 아니어도 한글이 깨지지 않아야 한다
    api = cp.GhApi(sleep=lambda s: None, log=lambda *_: None)

    assert api.get("repos/o/r?x=1&page=2") == {"제목": "한글 본문"}
    with pytest.raises(cp.GhNotFound):
        api.get("repos/o/gone")


# ─── gh 래퍼 — 페이지네이션 ─────────────────────────────────────────────────────


def paged_runner(total):
    """page 파라미터로 total건 목록을 100건씩 자르는 가짜 runner."""
    calls = []

    def runner(path):
        if path == "rate_limit":
            return _ok(RATE_OK)
        calls.append(path)
        page = int(parse_qs(urlsplit(path).query)["page"][0])
        start = (page - 1) * 100
        return _ok(list(range(start, min(start + 100, total))))

    runner.calls = calls
    return runner


def test_pages_stops_at_the_first_short_page():
    runner = paged_runner(250)
    api, _ = make_api(runner)

    items = api.pages("repos/o/r/issues?state=all&per_page=100")

    assert items == list(range(250))
    assert runner.calls == [f"repos/o/r/issues?state=all&per_page=100&page={n}" for n in (1, 2, 3)]


def test_pages_with_an_exact_multiple_reads_one_more_empty_page():
    runner = paged_runner(200)
    api, _ = make_api(runner)

    assert len(api.pages("repos/o/r/issues?per_page=100")) == 200
    assert len(runner.calls) == 3


def test_pages_stops_on_an_empty_first_page():
    runner = paged_runner(0)
    api, _ = make_api(runner)

    assert api.pages("repos/o/r/issues?per_page=100") == []
    assert len(runner.calls) == 1


def test_pages_stops_at_max_pages_even_if_more_data_exists():
    runner = paged_runner(1000)
    api, _ = make_api(runner)

    items = api.pages("repos/o/r/pulls/comments?per_page=100", max_pages=2)

    assert items == list(range(200))
    assert len(runner.calls) == 2


def test_pages_without_a_query_string_starts_one():
    runner = paged_runner(5)
    api, _ = make_api(runner)

    api.pages("repos/o/r/issues")

    assert runner.calls == ["repos/o/r/issues?page=1"]


# ─── 라이선스 · 포크 ────────────────────────────────────────────────────────────


def api_repo(full, spdx="MIT", fork=False, archived=False):
    return {"full_name": full, "fork": fork, "archived": archived,
            "license": None if spdx is None else {"key": "x", "spdx_id": spdx}}


@pytest.mark.parametrize("spdx", ["MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause"])
def test_allowed_licenses_are_collectable(spdx):
    assert cp.is_collectable_repo(api_repo("o/r", spdx))


@pytest.mark.parametrize("spdx", [None, "NOASSERTION", "GPL-3.0", "AGPL-3.0", "LGPL-2.1", "MPL-2.0", "Unlicense",
                                  "BSD-4-Clause", "mit"])
def test_every_other_license_is_rejected(spdx):
    assert not cp.is_collectable_repo(api_repo("o/r", spdx))


def test_forks_are_excluded_even_with_an_allowed_license():
    assert not cp.is_collectable_repo(api_repo("o/r", "MIT", fork=True))


def test_archived_repos_are_still_collected():
    assert cp.is_collectable_repo(api_repo("o/r", "MIT", archived=True))


def test_a_response_without_a_fork_field_is_not_collected():
    repo = api_repo("o/r")
    del repo["fork"]
    assert not cp.is_collectable_repo(repo)


# ─── clean_text ─────────────────────────────────────────────────────────────────


def test_clean_text_converts_crlf_and_strips_outer_whitespace():
    assert cp.clean_text("  첫 줄\r\n둘째 줄\r\n  ") == "첫 줄\n둘째 줄"


def test_clean_text_removes_multiline_html_comments():
    text = "앞 <!-- 한 줄 주석 --> 뒤\n<!--\n여러 줄\n주석\n-->\n본문"
    assert cp.clean_text(text) == "앞  뒤\n\n본문"


def test_clean_text_removes_only_lines_that_are_exactly_no_response():
    text = "### 재현 방법\n\n_No response_\n\n### 비고\n이슈 _No response_ 라고 적었다\n"
    cleaned = cp.clean_text(text)
    assert "### 재현 방법" in cleaned
    assert "\n_No response_\n" not in f"\n{cleaned}\n"
    assert "이슈 _No response_ 라고 적었다" in cleaned


@pytest.mark.parametrize("text, expected", [
    ("a\n\n\n\nb", "a\n\n\nb"),                 # 빈 줄 3줄 → 2줄
    ("a\n\n\n\n\n\n\nb", "a\n\n\nb"),           # 더 길어도 2줄
    ("a\n\n\nb", "a\n\n\nb"),                   # 빈 줄 2줄은 그대로
    ("a\n\nb", "a\n\nb"),
    ("a\n \n\t\n  \nb", "a\n\n\nb"),            # 공백만 있는 줄도 빈 줄이다
])
def test_clean_text_collapses_three_or_more_blank_lines_to_two(text, expected):
    assert cp.clean_text(text) == expected


def test_clean_text_cuts_at_4000_characters():
    assert len(cp.clean_text("가" * 5000)) == 4000
    assert cp.clean_text("가" * 4000) == "가" * 4000


def test_clean_text_of_none_is_empty():
    assert cp.clean_text(None) == ""


# ─── mask_text ──────────────────────────────────────────────────────────────────


def test_mask_issue_numbers():
    assert cp.mask_text("PR #123 과 #4 를 참고") == "PR #N 과 #N 를 참고"


def test_mask_issue_number_before_korean_particle():
    assert cp.mask_text("#123번 이슈, #45에서 발생") == "#N번 이슈, #N에서 발생"


def test_mask_leaves_html_entities_and_non_numbers_alone():
    assert cp.mask_text("&#123; 와 #이슈번호 와 #12ab") == "&#123; 와 #이슈번호 와 #12ab"


def test_mask_jira_style_keys():
    assert cp.mask_text("HT-12 와 ABC-1234 와 K9-7") == "KEY-N 와 KEY-N 와 KEY-N"


def test_mask_jira_key_before_korean_particle():
    assert cp.mask_text("HT-12를 해결") == "KEY-N를 해결"


@pytest.mark.parametrize("token", ["UTF-8", "ISO-8859", "SHA-256", "ES-2015", "HTTP-2", "TLS-1", "SSL-3", "RFC-2616",
                                   "CP-949", "EUC-1", "KS-5601"])
def test_mask_keeps_encoding_and_standard_names(token):
    assert cp.mask_text(f"인코딩은 {token} 이다") == f"인코딩은 {token} 이다"


@pytest.mark.parametrize("text", ["ht-12", "A-1", "ABCDEFGHIJK-12", "KEY-N"])
def test_mask_jira_pattern_boundaries(text):
    assert cp.mask_text(text) == text


def test_mask_urls():
    assert cp.mask_text("보세요 https://github.com/o/r/pull/9#issuecomment-1 와 http://a.b/c 입니다") \
        == "보세요 <URL> 와 <URL> 입니다"


def test_mask_removes_whole_url_before_other_rules_see_its_numbers_and_hashes():
    assert cp.mask_text("https://x.io/a/40640b2?ref=#12") == "<URL>"


def test_mask_commit_hashes_mixed_digits_and_letters_only():
    assert cp.mask_text("커밋 40640b2 에서") == "커밋 <HASH> 에서"
    assert cp.mask_text("1234567 deadbeef") == "1234567 deadbeef"


@pytest.mark.parametrize("word, masked", [
    ("a" * 6 + "1", True),            # 7자
    ("0" * 39 + "a", True),           # 40자
    ("0" * 40 + "a", False),          # 41자는 해시가 아니다
    ("40640b", False),                # 6자
])
def test_mask_hash_length_boundaries(word, masked):
    assert cp.mask_text(word) == ("<HASH>" if masked else word)


def test_mask_hash_before_korean_particle():
    assert cp.mask_text("40640b2에서 깨졌다") == "<HASH>에서 깨졌다"


def test_mask_is_idempotent():
    once = cp.mask_text("#12 HT-3 https://a.b/c 40640b2 UTF-8")
    assert cp.mask_text(once) == once


# ─── mask_personal ──────────────────────────────────────────────────────────────


def test_mask_personal_replaces_email_addresses():
    assert cp.mask_personal("문의: me.x+tag@corp.co.kr 로, 또는 a@b.com.", set()) == "문의: <EMAIL> 로, 또는 <EMAIL>."


def test_mask_personal_masks_known_logins_ignoring_case():
    text = "@Alice-Dev 님 확인 부탁, cc @bob"
    assert cp.mask_personal(text, {"alice-dev", "bob"}) == "@user 님 확인 부탁, cc @user"


def test_mask_personal_keeps_annotations_that_are_not_known_logins():
    text = "@ManyToOne(fetch = LAZY) 과 @alice"
    assert cp.mask_personal(text, {"alice"}) == "@ManyToOne(fetch = LAZY) 과 @user"


def test_mask_personal_masks_a_mention_followed_by_a_korean_particle():
    assert cp.mask_personal("@alice님 확인해 주세요, @alice가 맞습니다", {"alice"}) == "@user님 확인해 주세요, @user가 맞습니다"


@pytest.mark.parametrize("text", ["foo@alice", "@@alice", "@alice2", "@alice-bob"])
def test_mask_personal_does_not_touch_look_alikes(text):
    assert cp.mask_personal(text, {"alice"}) == text


def test_mask_personal_masks_emails_before_mentions():
    # `@example`을 멘션으로 먼저 지우면 이메일이 반쯤 남는다
    assert cp.mask_personal("a@example.com", {"example"}) == "<EMAIL>"


def test_mask_personal_with_no_logins_only_masks_emails():
    assert cp.mask_personal("@alice a@b.com", set()) == "@alice <EMAIL>"


# ─── 대화 글 필터 ───────────────────────────────────────────────────────────────


def test_conversation_text_boundary_is_20_natural_characters():
    assert not cp.is_conversation_text("가" * 19)
    assert cp.is_conversation_text("가" * 20)


def test_conversation_text_collapses_whitespace_before_counting():
    # 공백을 그대로 세면 23자라 통과한다 — 한 칸으로 줄여 세면 10 + 1 + 8 = 19자다
    assert not cp.is_conversation_text("가" * 10 + "     " + "가" * 8)
    assert cp.is_conversation_text("가" * 10 + "     " + "가" * 9)


def test_conversation_text_does_not_count_code_blocks():
    code = "```java\nSystem.out.println(\"이 코드는 아주 길어서 자연어 길이를 채울 수도 있다\");\n```"
    assert not cp.is_conversation_text(code)
    assert not cp.is_conversation_text("가" * 19 + "\n" + code)
    assert cp.is_conversation_text("가" * 20 + "\n" + code)


def test_conversation_text_treats_an_unterminated_fence_as_code():
    assert not cp.is_conversation_text("```\n" + "코드가 끝나지 않는 펜스입니다 " * 5)


def test_conversation_text_does_not_count_emoji():
    assert not cp.is_conversation_text("👍" * 30)
    assert not cp.is_conversation_text("가" * 19 + ("👍🎉✅" + "\N{HEAVY BLACK HEART}\N{VARIATION SELECTOR-16}") * 3)
    assert cp.is_conversation_text("가" * 20 + "👍")


@pytest.mark.parametrize("text", [
    "LGTM!", "lgtm", "감사합니다 👍", "좋아요~", "좋습니다.", "넵 ㅎㅎ", "확인했습니다", "반영했습니다!!", "수정했습니다ㅋ",
    "반영 완료!", "Lgtm!!!",
])
def test_conversation_text_rejects_short_greetings(text):
    assert not cp.is_conversation_text(text)


@pytest.mark.parametrize("text", [
    "확인했습니다. 반영했습니다. 수정했습니다. 감사합니다!",          # 20자를 넘어도 인사·확인뿐이다
    "LGTM LGTM LGTM LGTM LGTM LGTM LGTM",
    "넵 확인했습니다~ 반영했어요 ㅎㅎ 감사합니다 반영 완료!",
])
def test_conversation_text_rejects_long_acknowledgements(text):
    assert len(text) >= 20
    assert not cp.is_conversation_text(text)


def test_conversation_text_keeps_an_acknowledgement_that_adds_content():
    assert cp.is_conversation_text("확인했습니다. 그런데 이 부분은 다시 한번 검토가 필요해 보입니다")


def test_prepare_conversation_text_measures_length_after_masking():
    # 원문은 39자라 통과할 것 같지만 가리고 나면 "<URL> 확인"이다
    assert cp.prepare_conversation_text("https://github.com/o/r/pull/123 확인") is None


def test_prepare_conversation_text_returns_cleaned_and_masked_text():
    body = "<!-- 안내 -->\r\n자세한 내용은 https://x.io/a 와 #123 이슈를 참고하세요.\r\n\r\n\r\n\r\n끝"
    assert cp.prepare_conversation_text(body) == "자세한 내용은 <URL> 와 #N 이슈를 참고하세요.\n\n\n끝"


def test_prepare_conversation_text_of_none_is_none():
    assert cp.prepare_conversation_text(None) is None


# ─── 기계 글 필터 ───────────────────────────────────────────────────────────────
# 사람 계정으로 올라와 user.type·앱 표시로는 안 걸리는 자동 보고서와 AI 리뷰다. 첫 줄은 실데이터에서 확인한 모양 그대로고,
# 이어지는 줄은 실데이터가 알려 준 것(Gatsby의 사이트 이름 줄, 리뷰 템플릿의 둘째 문단)만 붙였다.

CODECOV_REPORT = ("## [Codecov](https://app.codecov.io/gh/toss/suspensive/pull/915?src=pr&el=h1&utm_medium=referral) Report\n"
                  "> Merging [#915](https://app.codecov.io/gh/toss/suspensive/pull/915?src=pr&el=desc) into main")
CODECOV_WELCOME = "## Welcome to [Codecov](https://codecov.io?utm_source=github) :tada:"
GATSBY_REPORT = "# [Gatsby Cloud](https://www.gatsbyjs.com) Build Report\n**team.daangn.com**"
DEPLOY_READY = "## :white_check_mark: team.daangn.com deploy preview ready"
DEPLOY_FAILED = "## :x: careers.jp.karrotmarket.com deploy preview failed"
REVIEW_PASS = "**Review 2 · Pass**"      # 19자 — 기계 글 판정이 짧은 글 판정보다 앞이라는 순서를 가려내는 데 쓴다
REVIEW_QUESTION = "Does the change do what the task asked? Yes. Gate: major and worse must be fixed."
REVIEW_PASS_FULL = f"{REVIEW_PASS}\n\n{REVIEW_QUESTION}"
REVIEW_FAIL = f"**Review 1 · Fail · 1 critical, 2 major**\n\n{REVIEW_QUESTION}"
REVIEW_PASS_MINOR = f"**Review 2 · Pass · 1 minor, 1 pre-existing**\n\n{REVIEW_QUESTION}"
CLAUDE_FOOTER = "🤖 Generated with [Claude Code](https://claude.com/claude-code)"
CLAUDE_FOOTER_ALT = "Generated with [Claude Code](https://claude.ai/code)"
FOOTER_AT_THE_END = f"로그인 실패 시 서버 문구 대신 우리 문구를 쓰도록 바꿨습니다.\n\n{CLAUDE_FOOTER}"

MACHINE_TEXTS = {
    "codecov-report": CODECOV_REPORT,
    "codecov-welcome": CODECOV_WELCOME,
    "gatsby-cloud-report": GATSBY_REPORT,
    "deploy-preview-ready": DEPLOY_READY,
    "deploy-preview-failed": DEPLOY_FAILED,
    "review-pass": REVIEW_PASS_FULL,
    "review-fail": REVIEW_FAIL,
    "review-pass-with-minor": REVIEW_PASS_MINOR,
    "claude-code-footer-at-the-end": FOOTER_AT_THE_END,
    "claude-code-footer-in-the-middle": f"요약입니다.\n\n{CLAUDE_FOOTER_ALT}\n\n테스트도 함께 돌려 봤습니다.",
    "claude-code-footer-alone": CLAUDE_FOOTER_ALT,
}

# 사람이 쓴 글. 앞 여덟 개는 실데이터에서 확인한 것이고, 나머지는 규칙의 경계(요소 하나가 모자라면 사람 글)다.
HUMAN_TEXTS = {
    "codecov-as-a-plain-word": "codecov recognize only react package ?",
    "codecov-token-in-korean": "secrets에 CODECOV_TOKEN라는 이름으로 추가해뒀어요!",
    "deploy-preview-in-a-sentence": "its not work well in deploy preview.. i have to fix it",
    "gatsby-cloud-in-a-sentence": "흠.. 이거 팀당근 clean build돌려도 잘 돌아가는데 gatsby cloud에서 fail먹이는군요",
    # AI 도구에 내린 사람의 명령·답글은 이번 범위가 아니다
    "command-to-an-ai-reviewer": "@coderabbitai resolve",
    # 🤖만으로는 거르지 않는다 — 사람이 쓴 글과 코드에도 쓰인다
    "robot-emoji-alone": "🤖",
    "robot-emoji-in-a-shell-line": 'echo -e "<< pid 확인 🤖 >>"',
    # 사람이 쓴 제목으로 시작하는 글은 아래 줄에 낱말이 나와도 사람 글이다
    "human-heading-then-gatsby-line": "## 변경 사항\n- Gatsby Cloud 빌드 설정을 고쳤습니다",
    "link-without-a-heading": "[Codecov](https://codecov.io) 설정을 추가했습니다",
    "report-heading-below-the-first-line": "확인 부탁드립니다\n## [Codecov](https://codecov.io) Report",
    "emoji-heading-without-the-deploy-phrase": "## :tada: 배포 미리보기가 준비됐습니다",
    "bold-review-without-pass-or-fail": "**Review 2** 반영했습니다. 확인 부탁드려요",
    "review-mark-not-at-the-start": "결과는 **Review 2 · Pass** 입니다",
    "mention-of-claude-code-without-the-footer-link": "Generated with Claude Code라고 적혀 있던데 이 부분은 직접 확인하셨나요?",
}

# 앞에 빈 줄이나 템플릿 주석이 붙어도 첫 번째 비어 있지 않은 줄은 같다
PREFIXES = {"none": "", "blank-lines": "\n\n", "html-comment": "<!-- 템플릿 안내 -->\n",
            "multiline-html-comment-and-spaces": "<!--\n여러 줄 주석\n-->\n\n  \n"}


@pytest.mark.parametrize("prefix", list(PREFIXES.values()), ids=list(PREFIXES))
@pytest.mark.parametrize("text", list(MACHINE_TEXTS.values()), ids=list(MACHINE_TEXTS))
def test_machine_text_is_detected_for_every_known_shape(text, prefix):
    assert cp.is_machine_text(prefix + text)


@pytest.mark.parametrize("text", list(HUMAN_TEXTS.values()), ids=list(HUMAN_TEXTS))
def test_human_text_is_not_machine_text(text):
    assert not cp.is_machine_text(text)


@pytest.mark.parametrize("body", [None, "", "   \n\n  ", "<!-- 주석만 있다 -->"])
def test_empty_text_is_not_machine_text(body):
    assert not cp.is_machine_text(body)


def test_claude_code_footer_beyond_the_text_cut_is_still_detected():
    # 학습 글은 4,000자에서 자르지만 서명은 긴 AI 글의 끝, 잘려 나가는 쪽에 붙는다
    long_body = "가" * cp.MAX_TEXT_CHARS + f"\n\n{CLAUDE_FOOTER}"
    assert cp.is_machine_text(long_body)


# ─── 앵커 필터 ──────────────────────────────────────────────────────────────────


def test_prepare_title_boundary_is_8_characters_after_masking():
    assert cp.prepare_title("가" * 7) is None
    assert cp.prepare_title("가" * 8) == "가" * 8
    assert cp.prepare_title("#123 수정") is None             # 가리면 "#N 수정" 6자


@pytest.mark.parametrize("subject", [
    "Merge branch 'main' into feature/x", "Merge pull request #12 from a/b", "리뷰 반영합니다 로그인", "피드백 반영 완료했습니다",
    "address review comments here", "Code REVIEW 반영", "WIP: 로그인 작업 중입니다", "wip 로그인 작업 중입니다",
    "fixup! 로그인 오류 수정합니다", "squash! 로그인 오류 수정합니다",
    # 낱말로서의 review는 여전히 걸린다
    "refactor: code review 반영", "apply review", "review를 반영해서 이름을 바꿨다",
])
def test_commit_subjects_that_say_nothing_are_filtered(subject):
    assert cp.prepare_commit_subject(subject) == (None, "commit_filtered")


@pytest.mark.parametrize("subject", [
    "swipe 제스처 기능 추가하기", "merge 처리 로직을 수정한다", "feat: 역 사이 구간 추가 구현",
    # 낱말 안에 review가 들어 있을 뿐인 실제 기능 커밋 (2020-taggle의 linkpreview 커밋 4개가 이렇게 빠졌다)
    "refactor(linkpreview): 타이틀이 없을 경우 url 반환", "feat: reviewer 배정 기능 추가",
])
def test_commit_subject_filter_does_not_overreach(subject):
    text, reason = cp.prepare_commit_subject(subject)
    assert reason is None and text == subject


def test_commit_subject_boundary_and_masking():
    assert cp.prepare_commit_subject("fix: a1") == (None, "anchor_short")          # 7자
    assert cp.prepare_commit_subject("fix: a12") == ("fix: a12", None)              # 8자
    assert cp.prepare_commit_subject("fix: HT-12 수정") == ("fix: KEY-N 수정", None)


def test_commit_subject_from_a_bot_is_dropped_as_bot():
    assert cp.prepare_commit_subject("chore: 의존성 업데이트 작업", is_bot=True) == (None, "bot")


def test_issue_text_is_title_blank_line_cleaned_body():
    assert cp.issue_text("로그인 오류", "<!-- x -->\n본문입니다\n\n_No response_\n") == "로그인 오류\n\n본문입니다"
    assert cp.issue_text("제목만 있다", "") == "제목만 있다"
    assert cp.issue_text("제목만 있다", None) == "제목만 있다"
    assert cp.issue_text("HT-12 오류", "#34 와 같다") == "KEY-N 오류\n\n#N 와 같다"


# ─── diff_hunk 형식화 ───────────────────────────────────────────────────────────


def test_format_diff_hunk_drops_header_and_context_lines():
    hunk = "@@ -1,4 +1,5 @@ class A\n context one\n-old line\n+new line one\n+new line two\n context tail"
    assert cp.format_diff_hunk(hunk) == "[제거] `old line`\n[추가] `new line one`\n[추가] `new line two`"


def test_format_diff_hunk_strips_indentation_and_skips_blank_changes():
    hunk = "@@ -1 +1,3 @@\n+    return x;\n+\n+   \n-\t}"
    assert cp.format_diff_hunk(hunk) == "[추가] `return x;`\n[제거] `}`"


def test_format_diff_hunk_ignores_file_header_lines_and_no_newline_marker():
    hunk = "--- a/A.java\n+++ b/A.java\n@@ -1 +1 @@\n-a\n+b\n\\ No newline at end of file"
    assert cp.format_diff_hunk(hunk) == "[제거] `a`\n[추가] `b`"


def test_format_diff_hunk_keeps_the_last_ten_changes_nearest_the_comment():
    lines = [f"+line{n:02d}" for n in range(12)]
    out = cp.format_diff_hunk("@@ -0,0 +1,12 @@\n" + "\n".join(lines) + "\n context at the commented line")
    assert out.split("\n") == [f"[추가] `line{n:02d}`" for n in range(2, 12)]


def test_format_diff_hunk_with_exactly_ten_changes_keeps_all():
    out = cp.format_diff_hunk("@@ @@\n" + "\n".join(f"+l{n}" for n in range(10)))
    assert len(out.split("\n")) == 10 and out.startswith("[추가] `l0`")


@pytest.mark.parametrize("hunk", ["@@ -1,2 +1,2 @@\n ctx a\n ctx b", "@@ -1 +1 @@", "", None, "--- a\n+++ b\n@@ @@"])
def test_format_diff_hunk_without_changed_lines_is_none(hunk):
    assert cp.format_diff_hunk(hunk) is None


def test_format_diff_hunk_handles_crlf():
    assert cp.format_diff_hunk("@@ @@\r\n+a\r\n-b\r\n") == "[추가] `a`\n[제거] `b`"


def test_diff_anchor_masks_only_urls():
    hunk = "@@ @@\n+// see https://x.io/a/b for #123 and 40640b2 or HT-12\n+int x = 1;"
    assert cp.diff_anchor(hunk) == "[추가] `// see <URL> for #123 and 40640b2 or HT-12`\n[추가] `int x = 1;`"


# ─── 이슈 연결 규칙 ─────────────────────────────────────────────────────────────

ISSUES = {12, 785, 1388, 5, 6, 7, 8}
REPO = "woowacourse-teams/2023-hang-log"


def refs(title="제목", body="", head_ref="main", issues=ISSUES, own=999, repo=REPO):
    return cp.extract_issue_refs(title, body, head_ref, issues, own, repo)


def test_refs_from_body_keywords_and_blockquote():
    assert refs(body="resolve #12") == [12]
    assert refs(body="> #785") == [785]
    assert refs(body="Closes #12, fixes #785") == [12, 785]


def test_refs_from_title():
    assert refs(title="[#12] 로그인 오류 수정") == [12]


def test_refs_from_a_same_repo_issue_url_ignoring_case():
    assert refs(body=f"https://github.com/{REPO}/issues/1388") == [1388]
    assert refs(body=f"https://github.com/{REPO.upper()}/issues/1388") == [1388]


def test_refs_ignore_urls_of_other_repos_and_pull_urls():
    assert refs(body="https://github.com/other/repo/issues/12") == []
    assert refs(body=f"https://github.com/{REPO}/issues/12345") == []        # 목록 밖 번호
    assert refs(body=f"https://github.com/{REPO}x/issues/12") == []


@pytest.mark.parametrize("head_ref, expected", [
    ("feature/#785", [785]),
    ("feature/1388-essay", [1388]),
    ("12-fix-login", [12]),
    ("release/2.2.0", []),
    ("feature/HT-785", []),
    ("main", []),
    ("", []),
])
def test_refs_from_branch_names(head_ref, expected):
    assert refs(head_ref=head_ref) == expected


def test_refs_exclude_own_number_pr_numbers_and_unknown_numbers():
    assert refs(body="#999 #12", issues=ISSUES | {999}, own=999) == [12]    # 이슈 목록에 있어도 자기 번호는 뺀다
    assert refs(body="#13 #14", issues={12}) == []                          # 이슈 목록에 없는 번호(PR이거나 없는 글)


def test_refs_dedupe_the_same_issue_found_by_every_route():
    assert refs(body=f"resolve #12 https://github.com/{REPO}/issues/12", head_ref="feature/12-fix") == [12]


def test_refs_with_four_or_more_issues_is_a_release_pr_and_gives_nothing():
    assert refs(body="#5 #6 #7 #8") == []
    assert refs(body="#5 #6 #7") == [5, 6, 7]                            # 3개까지는 연결한다


def test_refs_ignore_html_entities_and_the_template_placeholder():
    assert refs(body="&#123; 와 ex) #이슈번호", issues={123}) == []
    assert refs(body="a&#12;", issues={12}) == []


@pytest.mark.parametrize("body", ["abc#12", "path/#12", "#12ab", "#12_x"])
def test_refs_require_a_clean_boundary_around_the_number(body):
    assert refs(body=body) == []


def test_refs_accept_a_number_followed_by_a_korean_particle():
    assert refs(body="#12를 해결하고 #785에서 이어짐") == [12, 785]


def test_refs_tolerate_missing_text():
    assert refs(title=None, body=None, head_ref=None) == []


# ─── fetch — 가짜 GitHub ────────────────────────────────────────────────────────

T = "2024-02-01T00:00:00Z"


def api_user(login, kind="User"):
    return {"login": login, "type": kind}


def api_issue(number, title, body="", user="zz-pm", kind="User", pr=False):
    item = {"number": number, "title": title, "body": body, "created_at": T, "user": api_user(user, kind)}
    if pr:
        item["pull_request"] = {"url": f"https://api.github.com/repos/x/y/pulls/{number}"}   # PR이면 이 키가 있다
    return item


def api_pull(number, title, body="", head="feature/x", user="zz-dev", kind="User"):
    return {"number": number, "title": title, "body": body, "head": {"ref": head}, "created_at": T,
            "user": api_user(user, kind)}


def api_review(id_, pr, body, hunk, user="zz-rev", kind="User", copilot=False, reply_to=None):
    return {"id": id_, "pull_request_url": f"https://api.github.com/repos/x/y/pulls/{pr}", "body": body,
            "diff_hunk": hunk, "path": "src/A.java", "in_reply_to_id": reply_to, "created_at": T,
            "user": api_user(user, kind), "is_copilot_code_review": copilot}


def api_icomment(id_, number, body, user="zz-rev", kind="User", app=None):
    return {"id": id_, "issue_url": f"https://api.github.com/repos/x/y/issues/{number}", "body": body,
            "created_at": T, "user": api_user(user, kind), "performed_via_github_app": app}


def api_commit(message, login="zz-dev", kind="User"):
    return {"commit": {"message": message, "author": {"name": "Zz Person", "email": "zz@corp.example.com"}},
            "author": None if login is None else api_user(login, kind)}


def slice_page(items, page, per_page):
    return items[(page - 1) * per_page: page * per_page]


class FakeGitHub:
    """gh api를 흉내 내는 가짜 runner. 경로를 해석해 목록을 page 단위로 잘라 주고, 호출 경로를 기록한다."""

    def __init__(self):
        self.org_repos = {}     # 조직 → 저장소 목록
        self.repos = {}         # "owner/name" → 저장소 단건
        self.data = {}          # "owner/name" → {issues, pulls, reviews, icomments, commits{번호: 목록}}
        self.errors = {}        # 경로에 이 문자열이 들어 있으면 매번 이 stderr로 실패
        self.calls = []

    def add_repo(self, org, repo, **data):
        self.org_repos.setdefault(org, []).append(repo)
        self.repos[repo["full_name"]] = repo
        if data:
            self.data[repo["full_name"]] = data

    def __call__(self, path):
        self.calls.append(path)
        for needle, stderr in self.errors.items():
            if needle in path:
                return _err(stderr)
        if path == "rate_limit":
            return _ok(RATE_OK)
        url = urlsplit(path)
        query = parse_qs(url.query)
        page = int(query.get("page", ["1"])[0])
        per_page = int(query.get("per_page", ["100"])[0])
        seg = url.path.split("/")
        if seg[0] == "orgs":
            listing = self.org_repos.get(seg[1])
            return _ok(slice_page(listing, page, per_page)) if listing is not None else _err("gh: Not Found (HTTP 404)")
        full = f"{seg[1]}/{seg[2]}"
        if len(seg) == 3:
            return _ok(self.repos[full]) if full in self.repos else _err("gh: Not Found (HTTP 404)")
        if full not in self.data:
            return _err("gh: Not Found (HTTP 404)")
        data, tail = self.data[full], "/".join(seg[3:])
        table = {"issues": "issues", "pulls": "pulls", "pulls/comments": "reviews", "issues/comments": "icomments"}
        if tail in table:
            return _ok(slice_page(data.get(table[tail], []), page, per_page))
        commits = re.fullmatch(r"pulls/(\d+)/commits", tail)
        if commits:
            return _ok(data.get("commits", {}).get(int(commits.group(1)), []))
        raise AssertionError(f"예상 못 한 경로: {path}")


HUNK_EMAIL = "@@ -1,3 +1,4 @@\n ctx\n-old()\n+// 문의: dev@corp.example.com\n+newCall()"
HUNK_ANNOTATION = "@@ -5,2 +5,3 @@ class B\n ctx\n+@ManyToOne(fetch = LAZY)\n+private Member member;"
LONG = "이 부분은 책임을 나눠서 테스트하기 쉽게 만드는 편이 좋겠습니다"


def mission_data():
    """미션 저장소: 사람 이슈 1건뿐이고 리뷰 댓글만 많다. 봇·Copilot·짧은 댓글이 섞여 있다."""
    return {
        "issues": [api_issue(1, "수강 신청 버그 제보", "재현 방법입니다", user="zz-stu1"),
                   api_issue(2, "1단계 - 리뷰 요청드립니다", "안녕하세요", user="zz-stu1", pr=True),
                   api_issue(3, "2단계 - 리뷰 요청드립니다", "안녕하세요", user="zz-stu2", pr=True)],
        "reviews": [
            api_review(11, 2, "객체의 책임을 분리하면 좋겠습니다. @zz-stu1 님은 어떻게 생각하세요? rev@corp.example.com",
                       HUNK_EMAIL, user="zz-rev1"),
            api_review(12, 2, "LGTM", HUNK_EMAIL, user="zz-rev1"),
            api_review(13, 3, LONG, HUNK_EMAIL, user="zz-bot", kind="Bot"),
            api_review(14, 3, LONG + " (AI)", HUNK_EMAIL, user="Copilot", kind="Bot", copilot=True),
            api_review(15, 3, "맞습니다. 그 부분은 인터페이스로 분리하도록 수정했습니다 (https://example.com/a)",
                       HUNK_ANNOTATION, user="zz-stu2", reply_to=11),
        ],
    }


def product_data():
    """제품형 저장소: 사람 이슈 22건 → 제품형. 이슈 5·6·7을 가리키는 PR이 있고 릴리스 PR·봇 PR이 섞여 있다."""
    special = {
        5: ("로그인 실패 시 오류 메시지가 보이지 않음",
            "로그인에 실패해도 사용자에게 아무 안내가 없습니다.\n\n<!-- 템플릿 주석 -->\n_No response_"),
        22: ("결제 모듈 구조 개선 제안", "결제 모듈이 너무 커졌습니다"),
    }
    issues = [api_issue(n, *special.get(n, (f"기능 {n} 요청드립니다", f"{n}번 기능 설명입니다"))) for n in range(1, 23)]
    issues.append(api_issue(23, "자동 생성된 보안 알림 이슈입니다", "봇이 열었습니다", user="zz-secbot", kind="Bot"))
    pulls = [
        api_pull(24, "로그인 실패 시 오류 메시지를 보여준다", "resolve #5\n\n문의: dev@corp.example.com", "feature/5-login"),
        api_pull(25, "결제 모듈 리팩터링 작업 진행", "#6 #7 관련", "feature/payment", user="zz-dev2"),
        api_pull(26, "2.2.0 릴리스 배포 준비 작업", "#1 #2 #3 #4", "release/2.2.0"),
        api_pull(27, "의존성 업데이트 자동 PR 생성", "#8", "dependabot/npm", user="zz-depbot", kind="Bot"),
        api_pull(28, "관련 이슈 없는 문서 수정 작업", "", "docs/readme"),
    ]
    issues += [api_issue(p["number"], p["title"], p["body"], user=p["user"]["login"], kind=p["user"]["type"], pr=True)
               for p in pulls]
    return {
        "issues": issues, "pulls": pulls,
        "reviews": [api_review(401, 24, "예외를 삼키지 말고 사용자에게 전달하는 쪽이 맞겠습니다", HUNK_ANNOTATION)],
        "icomments": [
            api_icomment(301, 5, "재현 환경을 알려 주시면 원인을 더 빨리 찾을 수 있을 것 같습니다 @zz-pm"),
            api_icomment(302, 5, "비밀번호가 틀렸을 때만 그런가요? 계정이 잠긴 경우도 확인해 주세요"),
            api_icomment(303, 5, "원인은 예외를 삼키는 catch 블록이었고 수정 PR에서 함께 처리했습니다"),
            api_icomment(304, 5, "배포 파이프라인이 이 이슈를 참조해서 자동으로 남긴 안내 댓글입니다", app={"id": 1}),
            api_icomment(305, 5, "LGTM"),
            api_icomment(306, 23, "봇이 연 이슈에 달린 사람의 댓글이라도 앵커가 봇 글이라 쓰지 않는다"),
            api_icomment(307, 24, "에러 문구는 서버 응답 메시지를 그대로 쓰지 말고 우리 문구로 바꿔 주세요"),
            api_icomment(308, 24, "토스트보다 입력창 아래에 표시하는 편이 접근성에 더 좋아 보입니다"),
            api_icomment(309, 27, "봇이 연 PR에 달린 댓글도 앵커가 봇 글이라 쓰지 않는다"),
        ],
        "commits": {
            24: [api_commit("feat: 로그인 실패 시 메시지 노출\n\nCo-authored-by: Zz Person <zz@corp.example.com>"),
                 api_commit("Merge branch 'main' into feature/5-login"), api_commit("wip")],
            25: [api_commit("fix: 결제 금액 계산 오류 수정", login=None),
                 api_commit("refactor: 결제 모듈 분리 작업")],
        },
    }


def make_world():
    world = FakeGitHub()
    world.add_repo("next-step", api_repo("next-step/mission-a", "MIT", archived=True), **mission_data())
    world.add_repo("next-step", api_repo("next-step/forked", "MIT", fork=True), **mission_data())
    world.add_repo("next-step", api_repo("next-step/gpl-thing", "GPL-3.0"), **mission_data())
    world.add_repo("next-step", api_repo("next-step/no-license", None), **mission_data())
    world.add_repo("next-step", api_repo("next-step/unclear", "NOASSERTION"), **mission_data())
    world.add_repo("daangn", api_repo("daangn/prod-b", "Apache-2.0"), **product_data())
    return world


def run_fetch(data_dir, world, *extra):
    sleeps = []
    gh = cp.GhApi(runner=world, sleep=sleeps.append, clock=lambda: 0.0, log=lambda *_: None)
    cp.main(["fetch", "--data-dir", str(data_dir), "--orgs", "next-step,daangn", *extra], gh=gh)
    return sleeps


def raw_dir(data_dir, full):
    return data_dir / "raw" / full.replace("/", "__")


def data_calls(world):
    return [c for c in world.calls if c.startswith("repos/") and c.count("/") > 2]


# ─── fetch — 왕복 ───────────────────────────────────────────────────────────────


def test_fetch_filters_repos_by_license_and_fork_before_downloading_anything(tmp_path):
    world = make_world()

    run_fetch(tmp_path, world)

    fetched = {c.split("/")[2] for c in data_calls(world)}
    assert fetched == {"mission-a", "prod-b"}                       # 포크·GPL·라이선스 없음·NOASSERTION은 호출조차 안 한다
    assert sorted(p.name for p in (tmp_path / "raw").iterdir()) == ["daangn__prod-b", "next-step__mission-a"]


def test_fetch_writes_a_mission_repo_without_product_files(tmp_path):
    run_fetch(tmp_path, make_world())

    folder = raw_dir(tmp_path, "next-step/mission-a")
    assert sorted(p.name for p in folder.iterdir()) == ["issues.jsonl", "meta.json", "review_comments.jsonl"]
    meta = json.loads((folder / "meta.json").read_text(encoding="utf-8"))
    assert meta["repo"] == "next-step/mission-a" and meta["license"] == "MIT"
    assert meta["archived"] is True and meta["product"] is False and meta["complete"] is True
    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", meta["fetched_at"])
    assert meta["params"] == {"max_review_pages": 30, "product_min_issues": 20}
    assert meta["counts"] == {"issues": 3, "human_issues": 1, "review_comments": 5}


def test_fetch_writes_a_product_repo_with_all_files(tmp_path):
    run_fetch(tmp_path, make_world())

    folder = raw_dir(tmp_path, "daangn/prod-b")
    assert sorted(p.name for p in folder.iterdir()) == [
        "issue_comments.jsonl", "issues.jsonl", "meta.json", "pr_commits.jsonl", "pulls.jsonl", "review_comments.jsonl"]
    meta = json.loads((folder / "meta.json").read_text(encoding="utf-8"))
    assert meta["product"] is True and meta["license"] == "Apache-2.0" and meta["archived"] is False
    assert meta["counts"] == {"issues": 28, "human_issues": 22, "review_comments": 1, "pulls": 5,
                              "issue_comments": 9, "pr_commits": 5}


def test_fetch_counts_only_human_non_pr_issues_toward_product(tmp_path):
    # 사람 이슈 19건 + 봇 이슈 5건 + PR 행 10건: 20건 기준에서 제품형이 아니다
    issues = [api_issue(n, f"이슈 {n}번 제목입니다") for n in range(1, 20)]
    issues += [api_issue(n, "봇 이슈입니다", user="zz-bot", kind="Bot") for n in range(20, 25)]
    issues += [api_issue(n, "풀 리퀘스트입니다", pr=True) for n in range(25, 35)]
    world = FakeGitHub()
    world.add_repo("next-step", api_repo("next-step/edge", "MIT"), issues=issues)

    run_fetch(tmp_path, world)
    meta = json.loads((raw_dir(tmp_path, "next-step/edge") / "meta.json").read_text(encoding="utf-8"))
    assert meta["product"] is False and meta["counts"]["human_issues"] == 19

    run_fetch(tmp_path, world, "--product-min-issues", "19", "--refresh")
    meta = json.loads((raw_dir(tmp_path, "next-step/edge") / "meta.json").read_text(encoding="utf-8"))
    assert meta["product"] is True


def test_fetch_downloads_commits_only_for_prs_linked_to_one_to_three_human_issues(tmp_path):
    world = make_world()

    run_fetch(tmp_path, world)

    commit_calls = sorted(c for c in world.calls if "/commits" in c)
    assert commit_calls == ["repos/daangn/prod-b/pulls/24/commits?per_page=100",
                            "repos/daangn/prod-b/pulls/25/commits?per_page=100"]
    # 26(릴리스, 4개 연결)·27(봇 PR)·28(연결 없음)은 받지 않는다
    rows = read_jsonl(raw_dir(tmp_path, "daangn/prod-b") / "pr_commits.jsonl")
    assert {r["pr_number"] for r in rows} == {24, 25}


def test_fetch_stores_only_the_commit_subject_line(tmp_path):
    run_fetch(tmp_path, make_world())

    rows = read_jsonl(raw_dir(tmp_path, "daangn/prod-b") / "pr_commits.jsonl")
    assert [r["subject"] for r in rows] == [
        "feat: 로그인 실패 시 메시지 노출", "Merge branch 'main' into feature/5-login", "wip",
        "fix: 결제 금액 계산 오류 수정", "refactor: 결제 모듈 분리 작업"]
    assert all(set(r) == {"pr_number", "subject", "is_bot"} for r in rows)
    assert "Co-authored-by" not in json.dumps(rows, ensure_ascii=False)


def test_fetch_flags_bots_copilot_and_apps_instead_of_storing_authors(tmp_path):
    run_fetch(tmp_path, make_world())

    reviews = {r["id"]: r for r in read_jsonl(raw_dir(tmp_path, "next-step/mission-a") / "review_comments.jsonl")}
    assert [reviews[i]["is_bot"] for i in (11, 12, 13, 14, 15)] == [False, False, True, True, False]
    assert [reviews[i]["is_copilot"] for i in (11, 12, 13, 14, 15)] == [False, False, False, True, False]
    assert reviews[15]["in_reply_to_id"] == 11 and reviews[11]["pr_number"] == 2 and reviews[11]["path"] == "src/A.java"

    folder = raw_dir(tmp_path, "daangn/prod-b")
    comments = {r["id"]: r for r in read_jsonl(folder / "issue_comments.jsonl")}
    assert comments[304]["is_bot"] is True and comments[301]["is_bot"] is False and comments[307]["number"] == 24
    issues = {r["number"]: r for r in read_jsonl(folder / "issues.jsonl")}
    assert issues[23]["is_bot"] is True and issues[24]["is_pr"] is True and issues[5]["is_pr"] is False
    pulls = {r["number"]: r for r in read_jsonl(folder / "pulls.jsonl")}
    assert pulls[27]["is_bot"] is True and pulls[24]["head_ref"] == "feature/5-login"


def test_fetch_raw_files_have_no_author_keys_or_login_strings(tmp_path):
    run_fetch(tmp_path, make_world())

    texts = []
    for folder in (raw_dir(tmp_path, "next-step/mission-a"), raw_dir(tmp_path, "daangn/prod-b")):
        for path in folder.iterdir():
            text = path.read_text(encoding="utf-8")
            texts.append(text)
            rows = json.loads(text) if path.name == "meta.json" else read_jsonl(path)
            assert_no_author_keys(rows)
    joined = "\n".join(texts).lower()
    for login in ("zz-pm", "zz-stu1", "zz-stu2", "zz-rev", "zz-dev", "zz-bot", "zz-depbot", "zz-secbot", "copilot"):
        assert login not in joined.replace("is_copilot", "")
    assert "corp.example.com" not in joined


def test_fetch_masks_mentions_of_known_logins_and_emails_but_keeps_annotations(tmp_path):
    run_fetch(tmp_path, make_world())

    reviews = {r["id"]: r for r in read_jsonl(raw_dir(tmp_path, "next-step/mission-a") / "review_comments.jsonl")}
    assert "@user 님은 어떻게 생각하세요? <EMAIL>" in reviews[11]["body"]
    assert "+// 문의: <EMAIL>" in reviews[11]["diff_hunk"]                      # diff_hunk 안의 이메일도
    assert "+@ManyToOne(fetch = LAZY)" in reviews[15]["diff_hunk"]               # 로그인이 아닌 애너테이션은 그대로
    folder = raw_dir(tmp_path, "daangn/prod-b")
    assert "@user" in {r["id"]: r for r in read_jsonl(folder / "issue_comments.jsonl")}[301]["body"]
    assert "문의: <EMAIL>" in {r["number"]: r for r in read_jsonl(folder / "pulls.jsonl")}[24]["body"]


def test_fetch_keeps_texts_that_build_needs_unmasked_otherwise(tmp_path):
    run_fetch(tmp_path, make_world())

    # #N·URL·해시 가림은 build의 몫이다 — fetch는 연결 규칙이 읽을 본문을 건드리지 않는다
    reviews = {r["id"]: r for r in read_jsonl(raw_dir(tmp_path, "next-step/mission-a") / "review_comments.jsonl")}
    assert "https://example.com/a" in reviews[15]["body"]
    pulls = {r["number"]: r for r in read_jsonl(raw_dir(tmp_path, "daangn/prod-b") / "pulls.jsonl")}
    assert pulls[24]["body"].startswith("resolve #5")


def test_fetch_tolerates_deleted_users_and_null_bodies(tmp_path):
    # 탈퇴한 계정의 글은 user가 null일 수 있고, 본문 없는 글은 body가 null이다
    ghost_issue = api_issue(1, "본문 없는 이슈 제목입니다", body=None)
    ghost_issue["user"] = None
    ghost_review = api_review(7, 1, None, HUNK_EMAIL)
    ghost_review["user"] = None
    world = FakeGitHub()
    world.add_repo("next-step", api_repo("next-step/ghost", "MIT"), issues=[ghost_issue], reviews=[ghost_review])

    run_fetch(tmp_path, world)

    folder = raw_dir(tmp_path, "next-step/ghost")
    assert read_jsonl(folder / "issues.jsonl")[0]["body"] == "" and read_jsonl(folder / "issues.jsonl")[0]["is_bot"] is False
    review = read_jsonl(folder / "review_comments.jsonl")[0]
    assert review["body"] == "" and review["is_bot"] is False


def test_fetch_second_run_skips_complete_repos_and_refresh_downloads_again(tmp_path):
    world = make_world()
    run_fetch(tmp_path, world)
    first = len(world.calls)

    run_fetch(tmp_path, world)

    again = world.calls[first:]
    assert again and all(c.startswith("orgs/") or c == "rate_limit" for c in again)   # 목록 조회뿐, 저장소 데이터 호출 없음

    before = len(world.calls)
    run_fetch(tmp_path, world, "--refresh")
    assert any("issues?state=all" in c for c in world.calls[before:])


def test_fetch_incomplete_repo_is_downloaded_again_but_complete_ones_are_not(tmp_path):
    world = make_world()
    run_fetch(tmp_path, world)
    meta_path = raw_dir(tmp_path, "daangn/prod-b") / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    del meta["complete"]
    meta_path.write_text(json.dumps(meta), encoding="utf-8")
    before = len(world.calls)

    run_fetch(tmp_path, world)

    again = [c for c in world.calls[before:] if c.startswith("repos/")]
    assert again and all("daangn/prod-b" in c for c in again)
    assert json.loads(meta_path.read_text(encoding="utf-8"))["complete"] is True


def test_fetch_repos_option_checks_each_license_by_single_lookup(tmp_path):
    world = make_world()
    sleeps = []
    gh = cp.GhApi(runner=world, sleep=sleeps.append, clock=lambda: 0.0, log=lambda *_: None)

    cp.main(["fetch", "--data-dir", str(tmp_path), "--repos", "next-step/mission-a,next-step/gpl-thing,next-step/forked,"
             "nobody/missing"], gh=gh)

    assert not any(c.startswith("orgs/") for c in world.calls)
    assert [p.name for p in (tmp_path / "raw").iterdir()] == ["next-step__mission-a"]


def test_fetch_rejects_a_malformed_repos_value(tmp_path):
    with pytest.raises(SystemExit):
        cp.main(["fetch", "--data-dir", str(tmp_path), "--repos", "not-a-full-name"], gh=cp.GhApi(runner=FakeGitHub()))


def test_fetch_skips_a_repo_that_returns_404_and_continues(tmp_path):
    world = make_world()
    world.errors["next-step/mission-a/pulls/comments"] = "gh: Not Found (HTTP 404)"

    run_fetch(tmp_path, world)                                      # 종료 코드 0 — 404는 경고일 뿐이다

    assert [p.name for p in (tmp_path / "raw").iterdir()] == ["daangn__prod-b"]


def test_fetch_missing_org_is_skipped_with_a_warning(tmp_path):
    world = make_world()
    sleeps = []
    gh = cp.GhApi(runner=world, sleep=sleeps.append, clock=lambda: 0.0, log=lambda *_: None)

    cp.main(["fetch", "--data-dir", str(tmp_path), "--orgs", "no-such-org,daangn"], gh=gh)

    assert [p.name for p in (tmp_path / "raw").iterdir()] == ["daangn__prod-b"]


def test_fetch_retries_a_flaky_502_on_review_comments(tmp_path):
    world = make_world()
    real = world.__call__
    state = {"left": 2}

    def flaky(path):
        if "pulls/comments" in path and state["left"]:
            state["left"] -= 1
            return _err("gh: Bad Gateway (HTTP 502)")
        return real(path)

    sleeps = []
    gh = cp.GhApi(runner=flaky, sleep=sleeps.append, clock=lambda: 0.0, log=lambda *_: None)

    cp.main(["fetch", "--data-dir", str(tmp_path), "--orgs", "next-step,daangn"], gh=gh)

    assert sleeps == [2, 4]
    assert is_complete(tmp_path, "next-step/mission-a")


def is_complete(data_dir, full):
    return cp.is_complete(str(raw_dir(data_dir, full)))


def test_fetch_failure_leaves_no_partial_repo_and_other_repos_still_complete(tmp_path):
    world = make_world()
    world.errors["daangn/prod-b/issues/comments"] = "gh: Internal Server Error (HTTP 500)"

    with pytest.raises(SystemExit) as exit_info:
        run_fetch(tmp_path, world)

    assert exit_info.value.code == 1
    assert [p.name for p in (tmp_path / "raw").iterdir()] == ["next-step__mission-a"]       # .tmp-* 도 남지 않는다
    assert is_complete(tmp_path, "next-step/mission-a")

    del world.errors["daangn/prod-b/issues/comments"]
    run_fetch(tmp_path, world)                                       # 다시 실행하면 실패한 저장소만 이어 받는다
    assert is_complete(tmp_path, "daangn/prod-b")


def test_fetch_interrupted_download_leaves_no_complete_marker_or_temp_dir(tmp_path):
    world = make_world()
    real = world.__call__

    def crash(path):
        if "pulls/24/commits" in path:
            raise KeyboardInterrupt
        return real(path)

    gh = cp.GhApi(runner=crash, sleep=lambda s: None, clock=lambda: 0.0, log=lambda *_: None)

    with pytest.raises(KeyboardInterrupt):
        cp.main(["fetch", "--data-dir", str(tmp_path), "--orgs", "daangn"], gh=gh)

    assert [p.name for p in (tmp_path / "raw").iterdir()] == []


def test_write_raw_repo_removes_its_temp_dir_when_writing_fails(tmp_path, monkeypatch):
    raw_root = tmp_path / "raw"
    raw_root.mkdir()
    meta = {"repo": "o/r", "license": "MIT", "archived": False, "product": False}
    cp.write_raw_repo(str(raw_root), "o__r", {"issues.jsonl": [{"number": 1}]}, meta)
    before = (raw_root / "o__r" / "issues.jsonl").read_text(encoding="utf-8")

    def broken(path, doc):
        raise OSError("디스크가 가득 찼다")

    monkeypatch.setattr(cp, "write_json", broken)
    with pytest.raises(OSError):
        cp.write_raw_repo(str(raw_root), "o__r", {"issues.jsonl": [{"number": 2}]}, meta)

    assert sorted(p.name for p in raw_root.iterdir()) == ["o__r"]                  # .tmp-o__r 이 남지 않는다
    assert (raw_root / "o__r" / "issues.jsonl").read_text(encoding="utf-8") == before


def test_write_raw_repo_replaces_a_previous_directory_and_leaves_no_leftovers(tmp_path):
    raw_root = tmp_path / "raw"
    raw_root.mkdir()
    meta = {"repo": "o/r", "license": "MIT", "archived": False, "product": False}
    cp.write_raw_repo(str(raw_root), "o__r", {"old_only.jsonl": [{"a": 1}]}, meta)

    cp.write_raw_repo(str(raw_root), "o__r", {"issues.jsonl": [{"number": 2}]}, meta)

    assert sorted(p.name for p in raw_root.iterdir()) == ["o__r"]
    assert sorted(p.name for p in (raw_root / "o__r").iterdir()) == ["issues.jsonl", "meta.json"]
    assert json.loads((raw_root / "o__r" / "meta.json").read_text(encoding="utf-8"))["complete"] is True


def test_write_raw_repo_cannot_leave_a_complete_marker_on_a_half_deleted_directory(tmp_path, monkeypatch):
    raw_root = tmp_path / "raw"
    raw_root.mkdir()
    meta = {"repo": "o/r", "license": "MIT", "archived": False, "product": False}
    cp.write_raw_repo(str(raw_root), "o__r", {"a.jsonl": [{"n": 1}], "b.jsonl": [{"n": 2}]}, meta)
    real_rmtree = cp.shutil.rmtree

    def dies_midway(path, ignore_errors=False, **kwargs):
        if os.path.isdir(path):
            # rmtree가 파일을 일부만 지우고 죽은 상태 — 지우는 순서는 보장되지 않아 meta.json이 남을 수 있다
            victims = [f for f in sorted(os.listdir(path)) if f != "meta.json"]
            if victims:
                os.remove(os.path.join(path, victims[0]))
            raise KeyboardInterrupt
        real_rmtree(path, ignore_errors=ignore_errors, **kwargs)

    monkeypatch.setattr(cp.shutil, "rmtree", dies_midway)
    with pytest.raises(KeyboardInterrupt):
        cp.write_raw_repo(str(raw_root), "o__r", {"issues.jsonl": [{"n": 3}]}, meta)

    # 완료 표시가 있는 디렉터리는 파일이 빠짐없이 있어야 한다 (옛 것이든 새 것이든)
    final = raw_root / "o__r"
    if final.exists() and cp.is_complete(str(final)):
        assert sorted(p.name for p in final.iterdir()) in (["a.jsonl", "b.jsonl", "meta.json"],
                                                           ["issues.jsonl", "meta.json"])


def test_fetch_refresh_failure_keeps_the_previous_complete_data(tmp_path):
    world = make_world()
    run_fetch(tmp_path, world)
    before = (raw_dir(tmp_path, "daangn/prod-b") / "meta.json").read_text(encoding="utf-8")
    world.errors["daangn/prod-b/pulls?"] = "gh: Internal Server Error (HTTP 500)"

    with pytest.raises(SystemExit):
        run_fetch(tmp_path, world, "--refresh")

    assert (raw_dir(tmp_path, "daangn/prod-b") / "meta.json").read_text(encoding="utf-8") == before
    assert is_complete(tmp_path, "daangn/prod-b")


def test_fetch_treats_a_repo_with_issues_disabled_as_having_no_issues(tmp_path):
    world = make_world()
    world.errors["next-step/mission-a/issues?"] = "gh: Issues are disabled for this repo (HTTP 410)"

    run_fetch(tmp_path, world)

    meta = json.loads((raw_dir(tmp_path, "next-step/mission-a") / "meta.json").read_text(encoding="utf-8"))
    assert meta["product"] is False and meta["counts"]["issues"] == 0 and meta["counts"]["review_comments"] == 5


def test_fetch_limits_review_comment_pages(tmp_path):
    reviews = [api_review(n, 2, LONG, HUNK_EMAIL) for n in range(1, 251)]
    world = FakeGitHub()
    world.add_repo("next-step", api_repo("next-step/big", "MIT"), issues=[], reviews=reviews)

    run_fetch(tmp_path, world, "--max-review-pages", "2")

    meta = json.loads((raw_dir(tmp_path, "next-step/big") / "meta.json").read_text(encoding="utf-8"))
    assert meta["counts"]["review_comments"] == 200 and meta["params"]["max_review_pages"] == 2
    assert [c for c in world.calls if "pulls/comments" in c] == [
        "repos/next-step/big/pulls/comments?per_page=100&sort=created&direction=asc&page=1",
        "repos/next-step/big/pulls/comments?per_page=100&sort=created&direction=asc&page=2"]


# ─── build — 원자료 도우미 ──────────────────────────────────────────────────────

HUNK_OK = "@@ -1,2 +1,3 @@\n ctx\n-old()\n+newCall()"


def talk(n):
    """20자를 넘고 서로 다른 대화 글 (중복 제거에 걸리지 않는다)."""
    return f"{n}번째 의견: 이 부분은 서비스 계층에서 트랜잭션 경계를 잡는 편이 좋겠습니다"


def r_issue(number, title="이슈 제목입니다 로그인", body="", is_pr=False, is_bot=False):
    return {"number": number, "title": title, "body": body, "created_at": T, "is_pr": is_pr, "is_bot": is_bot}


def r_pull(number, title, body="", head_ref="", is_bot=False):
    return {"number": number, "title": title, "body": body, "head_ref": head_ref, "created_at": T, "is_bot": is_bot}


def r_review(id_, pr, body, hunk=HUNK_OK, is_bot=False, is_copilot=False):
    return {"id": id_, "pr_number": pr, "body": body, "diff_hunk": hunk, "path": "A.java", "in_reply_to_id": None,
            "created_at": T, "is_bot": is_bot, "is_copilot": is_copilot}


def r_icomment(id_, number, body, is_bot=False):
    return {"id": id_, "number": number, "body": body, "created_at": T, "is_bot": is_bot}


def r_commit(pr, subject, is_bot=False):
    return {"pr_number": pr, "subject": subject, "is_bot": is_bot}


def write_raw(data_dir, full, *, license="MIT", product=False, issues=(), pulls=(), reviews=(), icomments=(),
              commits=(), complete=True, shuffle_seed=None):
    folder = raw_dir(data_dir, full)
    folder.mkdir(parents=True)
    files = {"issues": issues, "review_comments": reviews}
    if product:
        files.update({"pulls": pulls, "issue_comments": icomments, "pr_commits": commits})
    for name, rows in files.items():
        rows = list(rows)
        if shuffle_seed is not None:
            random.Random(shuffle_seed).shuffle(rows)
        (folder / f"{name}.jsonl").write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows),
                                              encoding="utf-8")
    meta = {"repo": full, "license": license, "archived": False, "product": product, "fetched_at": T,
            "counts": {}, "params": {}}
    if complete:
        meta["complete"] = True
    (folder / "meta.json").write_text(json.dumps(meta), encoding="utf-8")


def run_build(data_dir, *extra, val=""):
    cp.main(["build", "--data-dir", str(data_dir), "--val-repos", val, *extra])
    return (read_jsonl(data_dir / "pairs-train.jsonl"), read_jsonl(data_dir / "pairs-val.jsonl"),
            json.loads((data_dir / "pairs-manifest.json").read_text(encoding="utf-8")))


def kinds(pairs):
    counts = {}
    for p in pairs:
        key = f"{p['type']}/{p['anchor_kind']}"
        counts[key] = counts.get(key, 0) + 1
    return counts


def product_raw():
    """다섯 가지 (type, anchor_kind)가 모두 나오는 제품형 저장소의 단순한 원자료."""
    return dict(
        product=True,
        issues=[r_issue(5, "로그인 실패 시 오류 메시지가 보이지 않음", "안내가 없습니다"),
                r_issue(6, "결제 금액 계산 오류", "합계가 틀립니다"), r_issue(7, "결제 모듈 구조 개선", "모듈이 너무 큽니다"),
                r_issue(24, "로그인 실패 시 오류 메시지를 보여준다", is_pr=True),
                r_issue(25, "결제 모듈 리팩터링 작업 진행", is_pr=True)],
        pulls=[r_pull(24, "로그인 실패 시 오류 메시지를 보여준다", "resolve #5", "feature/5-login"),
               r_pull(25, "결제 모듈 리팩터링 작업 진행", "#6 #7 관련")],
        reviews=[r_review(401, 24, talk(1))],
        icomments=[r_icomment(301, 5, talk(2)), r_icomment(302, 5, talk(3)),
                   r_icomment(307, 24, talk(4)), r_icomment(308, 24, talk(5))],
        commits=[r_commit(24, "feat: 로그인 실패 시 메시지 노출"), r_commit(25, "fix: 결제 금액 계산 오류 수정"),
                 r_commit(25, "refactor: 결제 모듈 분리 작업")],
    )


# ─── build — 쌍 종류와 내용 ─────────────────────────────────────────────────────


def test_build_makes_every_pair_type_from_a_product_repo(tmp_path):
    write_raw(tmp_path, "o/prod", **product_raw())

    train, val, manifest = run_build(tmp_path)

    assert val == []
    assert kinds(train) == {
        "change_conversation/diff_hunk": 1,       # 리뷰 댓글 401
        "change_conversation/pr_title": 2,        # PR 24의 대화 댓글 둘
        "change_issue/pr_title": 3,               # PR 24→#5, PR 25→#6·#7
        "change_issue/commit_message": 5,         # PR 24 커밋 1개×이슈 1개 + PR 25 커밋 2개×이슈 2개
        "issue_conversation/issue": 2,            # 이슈 #5의 댓글 둘
    }
    assert {(p["type"], p["anchor_kind"], p["positive_kind"]) for p in train} == {
        ("change_conversation", "diff_hunk", "review_comment"), ("change_conversation", "pr_title", "pr_comment"),
        ("change_issue", "pr_title", "issue"), ("change_issue", "commit_message", "issue"),
        ("issue_conversation", "issue", "issue_comment")}


def test_build_pair_contents_for_each_type(tmp_path):
    write_raw(tmp_path, "o/prod", **product_raw())

    train, _, _ = run_build(tmp_path)

    review = next(p for p in train if p["positive_kind"] == "review_comment")
    assert review["anchor"] == "[제거] `old()`\n[추가] `newCall()`" and review["positive"] == talk(1)
    assert review["source"] == {"kind": "pr", "number": 24} and review["created_at"] == T

    pr_comment = sorted((p for p in train if p["positive_kind"] == "pr_comment"), key=lambda p: p["positive"])
    assert [p["positive"] for p in pr_comment] == [talk(4), talk(5)]
    assert {p["anchor"] for p in pr_comment} == {"로그인 실패 시 오류 메시지를 보여준다"}
    assert {p["source"]["number"] for p in pr_comment} == {24}

    issue_five = "로그인 실패 시 오류 메시지가 보이지 않음\n\n안내가 없습니다"
    from_title = [p for p in train if p["type"] == "change_issue" and p["positive"] == issue_five]
    assert {p["anchor"] for p in from_title} == {"로그인 실패 시 오류 메시지를 보여준다", "feat: 로그인 실패 시 메시지 노출"}
    assert {p["source"]["number"] for p in from_title} == {24}

    thread = [p for p in train if p["positive_kind"] == "issue_comment"]
    assert {p["anchor"] for p in thread} == {issue_five}
    assert {p["positive"] for p in thread} == {talk(2), talk(3)}
    assert {p["source"]["kind"] for p in thread} == {"issue"} and {p["source"]["number"] for p in thread} == {5}


def test_build_mission_repo_only_makes_code_to_review_pairs(tmp_path):
    write_raw(tmp_path, "o/mission", product=False, issues=[r_issue(1, "버그 제보입니다 확인 부탁", "내용"),
                                                           r_issue(2, "1단계 리뷰 요청드립니다", is_pr=True)],
              reviews=[r_review(1, 2, talk(1)), r_review(2, 2, talk(2))])

    train, _, _ = run_build(tmp_path)

    assert kinds(train) == {"change_conversation/diff_hunk": 2}


# ─── build — 걸러내기와 탈락 집계 ───────────────────────────────────────────────


def filter_raw():
    return dict(
        product=True,
        issues=[r_issue(1, "정상적인 사람의 이슈 제목입니다", "본문입니다"),
                r_issue(2, "봇이 만든 이슈 제목입니다", "봇 본문", is_bot=True),
                r_issue(10, "정상적인 풀 리퀘스트 제목", is_pr=True),
                r_issue(11, "봇이 올린 풀 리퀘스트 제목", is_pr=True, is_bot=True),
                r_issue(12, "짧은제목", is_pr=True)],
        pulls=[r_pull(10, "정상적인 풀 리퀘스트 제목", "resolve #1"),
               r_pull(11, "봇이 올린 풀 리퀘스트 제목", "#1", is_bot=True),
               r_pull(12, "짧은제목", "#1")],                      # 4자 — 앵커로 쓰기엔 짧다
        reviews=[r_review(1, 10, talk(1)),
                 r_review(2, 10, talk(2), is_bot=True),
                 r_review(3, 10, talk(3), is_copilot=True),
                 r_review(4, 10, "LGTM"),
                 r_review(5, 10, talk(5), hunk="@@ -1,2 +1,2 @@\n ctx a\n ctx b")],
        icomments=[r_icomment(1, 1, talk(11)), r_icomment(2, 1, talk(12), is_bot=True),
                   r_icomment(3, 1, "넵 감사합니다"),
                   r_icomment(4, 2, talk(14)),                      # 봇이 연 이슈의 댓글
                   r_icomment(5, 10, talk(15)), r_icomment(6, 10, "LGTM"),
                   r_icomment(7, 11, talk(17)),                     # 봇이 연 PR의 댓글
                   r_icomment(8, 12, talk(18)),                     # 앵커(PR 제목)가 짧은 PR의 댓글
                   r_icomment(9, 999, talk(19))],                   # 어디에도 없는 번호 — 세지 않고 버린다
        commits=[r_commit(10, "Merge branch 'x'"), r_commit(10, "wip"), r_commit(10, "리뷰 반영"),
                 r_commit(10, "fix: a1"), r_commit(10, "feat: 정상적인 커밋 제목입니다"),
                 r_commit(10, "chore: 봇 커밋 제목입니다", is_bot=True)],
    )


def test_build_excludes_bot_copilot_short_and_unusable_anchors_and_counts_each_reason(tmp_path):
    write_raw(tmp_path, "o/filters", **filter_raw())

    train, _, manifest = run_build(tmp_path)

    assert kinds(train) == {
        "change_conversation/diff_hunk": 1, "change_conversation/pr_title": 1,
        "change_issue/pr_title": 1, "change_issue/commit_message": 1, "issue_conversation/issue": 1}
    assert manifest["repos"]["o/filters"]["dropped"] == {
        "license": 0,
        "bot": 6,               # 리뷰 1 + 댓글 셋(봇 댓글·봇 이슈·봇 PR) + 봇 PR 제목 1 + 봇 커밋 1
        "copilot": 1,
        "machine": 0,
        "short_or_ack": 3,      # 리뷰 "LGTM", 이슈 댓글 "넵 감사합니다", PR 댓글 "LGTM"
        "anchor_short": 3,      # 짧은 PR 제목 아래 댓글 1 + 짧은 PR 제목의 이슈 연결 1 + 커밋 "fix: a1"
        "commit_filtered": 3,   # Merge · wip · 리뷰 반영
        "no_changed_lines": 1,
        "per_pr_cap": 0, "per_issue_cap": 0, "duplicate": 0, "repo_type_cap": 0}


def test_build_never_uses_text_from_bot_opened_prs_or_issues(tmp_path):
    write_raw(tmp_path, "o/filters", **filter_raw())

    train, _, _ = run_build(tmp_path)

    joined = "\n".join(p["anchor"] + "\n" + p["positive"] for p in train)
    for forbidden in ("봇이 만든 이슈", "봇 본문", "봇이 올린 풀 리퀘스트", "봇 커밋", talk(2), talk(3), talk(12), talk(14),
                      talk(17)):
        assert forbidden not in joined


def test_build_trusts_the_product_flag_in_meta_and_ignores_product_files_of_a_mission_repo(tmp_path):
    write_raw(tmp_path, "o/prod", **product_raw())
    meta_path = raw_dir(tmp_path, "o/prod") / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    meta["product"] = False                       # PR·댓글·커밋 파일이 디렉터리에 있어도 미션형이면 쓰지 않는다
    meta_path.write_text(json.dumps(meta), encoding="utf-8")

    train, _, _ = run_build(tmp_path)

    assert kinds(train) == {"change_conversation/diff_hunk": 1}


def test_build_a_pr_linking_four_or_more_issues_makes_no_pairs_and_no_drops(tmp_path):
    raw = product_raw()
    raw["issues"] += [r_issue(n, f"{n}번 기능 요청 이슈 제목") for n in (1, 2, 3, 4)]
    raw["pulls"] = [r_pull(30, "2.2.0 릴리스 배포 준비 작업", "#1 #2 #3 #4", "release/2.2.0")]
    raw["commits"] = [r_commit(30, "chore: 릴리스 버전을 올린다")]
    raw["icomments"], raw["reviews"] = [], []
    write_raw(tmp_path, "o/release", **raw)

    train, _, manifest = run_build(tmp_path)

    assert train == []
    assert not any(manifest["repos"]["o/release"]["dropped"].values())


# ─── build — 기계 글 ────────────────────────────────────────────────────────────


def raw_of(product, **rows):
    """후보 함수에 바로 넣는 저장소 하나의 원자료 (파일을 거치지 않는다)."""
    return {"meta": {"repo": "o/r", "product": product}, "issues": [], "review_comments": [], "pulls": [],
            "issue_comments": [], "pr_commits": [], **rows}


def candidates_of(raw):
    drops = dict.fromkeys(cp.DROP_REASONS, 0)
    caps = {"review": 3, "conversation": 3, "issue_comment": 3, "commit": 3, "repo_type": 2000}
    return cp.generate_candidates(raw, 42, caps, drops), drops


def positives(candidates):
    return [c["positive"] for c in candidates]


def test_review_comments_that_are_machine_text_are_counted_as_machine_before_any_later_reason():
    raw = raw_of(False, review_comments=[
        r_review(1, 10, talk(1)),                                                       # 사람 글 — 남는다
        r_review(2, 10, CODECOV_REPORT),
        r_review(3, 10, REVIEW_PASS),                                                   # 19자라 짧은 글이기도 하다
        r_review(4, 10, FOOTER_AT_THE_END, hunk="@@ -1,2 +1,2 @@\n ctx a\n ctx b"),    # 변경 줄이 없기도 하다
        r_review(5, 10, REVIEW_PASS_FULL, is_bot=True),                                 # 글쓴이 플래그가 먼저다
        r_review(6, 10, REVIEW_PASS_FULL, is_copilot=True)])

    by_type, drops = candidates_of(raw)

    assert positives(by_type["change_conversation"]) == [talk(1)]
    assert (drops["machine"], drops["bot"], drops["copilot"]) == (3, 1, 1)
    assert (drops["short_or_ack"], drops["no_changed_lines"]) == (0, 0)


def test_pr_comments_that_are_machine_text_are_counted_as_machine_before_any_later_reason():
    raw = raw_of(True,
                 pulls=[r_pull(10, "정상적인 풀 리퀘스트 제목"), r_pull(11, "짧은제목"),
                        r_pull(12, "봇이 올린 풀 리퀘스트 제목", is_bot=True)],
                 issue_comments=[
                     r_icomment(1, 10, talk(1)),                      # 사람 글 — 남는다
                     r_icomment(2, 10, GATSBY_REPORT),
                     r_icomment(3, 10, REVIEW_PASS),                  # 19자라 짧은 글이기도 하다
                     r_icomment(4, 11, DEPLOY_READY),                 # 앵커(PR 제목)가 짧기도 하다
                     r_icomment(5, 10, DEPLOY_FAILED, is_bot=True),   # 글쓴이 플래그가 먼저다
                     r_icomment(6, 12, CODECOV_REPORT)])              # 봇이 연 PR의 댓글도 봇이 먼저다

    by_type, drops = candidates_of(raw)

    assert positives(by_type["change_conversation"]) == [talk(1)]
    assert (drops["machine"], drops["bot"]) == (3, 2)
    assert (drops["short_or_ack"], drops["anchor_short"]) == (0, 0)


def test_issue_comments_that_are_machine_text_are_counted_as_machine_before_any_later_reason():
    raw = raw_of(True,
                 issues=[r_issue(5), r_issue(6, is_bot=True)],
                 issue_comments=[
                     r_icomment(1, 5, talk(1)),                       # 사람 글 — 남는다
                     r_icomment(2, 5, CODECOV_WELCOME),
                     r_icomment(3, 5, REVIEW_PASS),                   # 19자라 짧은 글이기도 하다
                     r_icomment(4, 5, REVIEW_FAIL, is_bot=True),      # 글쓴이 플래그가 먼저다
                     r_icomment(5, 6, FOOTER_AT_THE_END)])            # 봇이 연 이슈의 댓글도 봇이 먼저다

    by_type, drops = candidates_of(raw)

    assert positives(by_type["issue_conversation"]) == [talk(1)]
    assert (drops["machine"], drops["bot"], drops["short_or_ack"]) == (2, 2, 0)


def test_machine_filter_does_not_touch_anchors_such_as_issue_bodies_and_pr_titles():
    raw = raw_of(True,
                 issues=[r_issue(5, "로그인 실패 시 오류 메시지가 보이지 않음", CODECOV_REPORT),
                         r_issue(24, DEPLOY_READY, is_pr=True)],
                 pulls=[r_pull(24, DEPLOY_READY, "resolve #5")],
                 issue_comments=[r_icomment(1, 5, talk(1)), r_icomment(2, 24, talk(2))])

    by_type, drops = candidates_of(raw)

    assert [len(by_type[type_]) for type_ in cp.TYPE_ORDER] == [1, 1, 1]
    assert drops["machine"] == 0


def test_build_counts_machine_texts_in_the_manifest_and_the_dropped_line_and_keeps_only_the_human_pairs(tmp_path, capsys):
    noisy_raw = product_raw()
    noisy_raw["reviews"] = noisy_raw["reviews"] + [r_review(402, 24, FOOTER_AT_THE_END)]
    noisy_raw["icomments"] = noisy_raw["icomments"] + [r_icomment(310, 24, CODECOV_REPORT),
                                                       r_icomment(311, 5, GATSBY_REPORT)]
    write_raw(tmp_path / "plain", "o/prod", **product_raw())
    write_raw(tmp_path / "noisy", "o/prod", **noisy_raw)

    plain, _, plain_manifest = run_build(tmp_path / "plain")
    capsys.readouterr()
    noisy, _, noisy_manifest = run_build(tmp_path / "noisy")

    assert plain_manifest["repos"]["o/prod"]["dropped"]["machine"] == 0
    assert noisy_manifest["repos"]["o/prod"]["dropped"]["machine"] == 3       # 리뷰 1 + PR 댓글 1 + 이슈 댓글 1
    assert noisy == plain                                                       # 기계 글 짝이 빠져 사람 글만 있던 때와 같다
    assert re.search(r"탈락:.*\bmachine 3\b", capsys.readouterr().out)


# ─── build — 상한 ───────────────────────────────────────────────────────────────


def test_build_caps_review_pairs_per_pr_then_counts_the_rest(tmp_path):
    reviews = [r_review(n, 1, talk(n)) for n in range(1, 9)] + [r_review(n, 2, talk(n)) for n in range(9, 11)]
    write_raw(tmp_path, "o/mission", issues=[], reviews=reviews)

    train, _, manifest = run_build(tmp_path)

    by_pr = {}
    for p in train:
        by_pr.setdefault(p["source"]["number"], []).append(p)
    assert {k: len(v) for k, v in by_pr.items()} == {1: 3, 2: 2}
    assert manifest["repos"]["o/mission"]["dropped"]["per_pr_cap"] == 5

    train, _, manifest = run_build(tmp_path, "--per-pr-review-cap", "8")
    assert len(train) == 10 and manifest["repos"]["o/mission"]["dropped"]["per_pr_cap"] == 0


def test_build_caps_conversation_issue_comment_and_commit_anchors(tmp_path):
    raw = product_raw()
    raw["icomments"] = ([r_icomment(n, 24, talk(n)) for n in range(100, 106)]          # PR 24 대화 댓글 6개
                        + [r_icomment(n, 5, talk(n)) for n in range(200, 207)])         # 이슈 5 댓글 7개
    raw["commits"] = [r_commit(24, "feat: 로그인 실패 시 메시지 노출")] + [
        r_commit(25, f"{n}번째 커밋: 결제 모듈을 작게 나누었다") for n in range(5)]       # PR 25(이슈 둘) 커밋 5개
    write_raw(tmp_path, "o/prod", **raw)

    train, _, manifest = run_build(tmp_path)

    counts = kinds(train)
    assert counts["change_conversation/pr_title"] == 3
    assert counts["issue_conversation/issue"] == 3
    # PR 25: 커밋 5개 중 3개 × 이슈 2개 = 6쌍. PR 24: 커밋 1개 × 이슈 1개
    assert counts["change_issue/commit_message"] == 6 + 1
    dropped = manifest["repos"]["o/prod"]["dropped"]
    assert dropped["per_pr_cap"] == 3 + (2 * 2)       # 대화 3쌍 + 버려진 커밋 2개 × 이슈 2개
    assert dropped["per_issue_cap"] == 4

    train, _, manifest = run_build(tmp_path, "--per-pr-conversation-cap", "2", "--per-issue-comment-cap", "1",
                                   "--per-pr-commit-cap", "1")
    counts = kinds(train)
    assert (counts["change_conversation/pr_title"], counts["issue_conversation/issue"],
            counts["change_issue/commit_message"]) == (2, 1, 2 + 1)


def test_build_commit_cap_zero_turns_off_commit_message_anchors_and_keeps_every_other_pair(tmp_path):
    write_raw(tmp_path / "default", "o/prod", **product_raw())
    write_raw(tmp_path / "off", "o/prod", **product_raw())

    default, _, _ = run_build(tmp_path / "default")
    off, _, manifest = run_build(tmp_path / "off", "--per-pr-commit-cap", "0")

    assert kinds(default)["change_issue/commit_message"] == 5
    assert "change_issue/commit_message" not in kinds(off)
    assert kinds(off)["change_issue/pr_title"] == 3                                  # PR 제목 → 이슈 쌍은 그대로 남는다
    assert off == [p for p in default if p["anchor_kind"] != "commit_message"]
    # 상한 0은 커밋 제목을 전부 버리고 per_pr_cap으로 센다: PR 24의 커밋 1개 × 이슈 1개 + PR 25의 커밋 2개 × 이슈 2개
    assert manifest["repos"]["o/prod"]["dropped"]["per_pr_cap"] == 1 * 1 + 2 * 2
    assert manifest["args"]["per_pr_commit_cap"] == 0


def test_build_applies_the_repo_type_cap_after_the_per_pr_cap_and_per_type(tmp_path):
    reviews = [r_review(100 * pr + n, pr, talk(100 * pr + n)) for pr in range(1, 4) for n in range(8)]   # PR 3개 × 8개
    raw = product_raw()
    raw["reviews"] = reviews
    write_raw(tmp_path, "o/prod", **raw)

    train, _, manifest = run_build(tmp_path, "--per-repo-type-cap", "4")

    counts = kinds(train)
    # change_conversation은 후보(리뷰 3×3=9 + PR 대화 2)가 11쌍 → 4쌍. 다른 type은 자기 상한을 따로 쓴다
    assert counts.get("change_conversation/diff_hunk", 0) + counts.get("change_conversation/pr_title", 0) == 4
    assert counts.get("change_issue/pr_title", 0) + counts.get("change_issue/commit_message", 0) == 4
    assert counts["issue_conversation/issue"] == 2
    dropped = manifest["repos"]["o/prod"]["dropped"]
    assert dropped["per_pr_cap"] == 3 * 5               # 리뷰 PR당 8개 중 5개씩. 먼저 PR당 상한이 적용된다
    assert dropped["repo_type_cap"] == (11 - 4) + (8 - 4)


# ─── build — 결정성 ─────────────────────────────────────────────────────────────


def many_reviews_raw():
    return dict(issues=[], reviews=[r_review(100 * pr + n, pr, talk(100 * pr + n)) for pr in range(1, 7) for n in range(10)])


def test_build_is_deterministic_and_the_seed_changes_the_selection(tmp_path):
    for name in ("a", "b", "c"):
        write_raw(tmp_path / name, "o/mission", **many_reviews_raw())

    run_build(tmp_path / "a")
    run_build(tmp_path / "b")
    run_build(tmp_path / "c", "--seed", "7")

    first = (tmp_path / "a" / "pairs-train.jsonl").read_bytes()
    assert first == (tmp_path / "b" / "pairs-train.jsonl").read_bytes()
    assert first != (tmp_path / "c" / "pairs-train.jsonl").read_bytes()
    assert len(read_jsonl(tmp_path / "a" / "pairs-train.jsonl")) == len(read_jsonl(tmp_path / "c" / "pairs-train.jsonl")) == 18


def test_build_selection_does_not_depend_on_row_order_in_the_raw_files(tmp_path):
    write_raw(tmp_path / "a", "o/mission", **many_reviews_raw())
    write_raw(tmp_path / "b", "o/mission", shuffle_seed=1, **many_reviews_raw())

    run_build(tmp_path / "a")
    run_build(tmp_path / "b")

    assert (tmp_path / "a" / "pairs-train.jsonl").read_bytes() == (tmp_path / "b" / "pairs-train.jsonl").read_bytes()


def test_build_commit_selection_does_not_depend_on_commit_order(tmp_path):
    def raw(order):
        data = product_raw()
        data["commits"] = [r_commit(25, f"{n}번째 커밋: 결제 모듈을 작게 나누었다") for n in order]
        return data

    write_raw(tmp_path / "a", "o/prod", **raw(range(6)))
    write_raw(tmp_path / "b", "o/prod", **raw(reversed(range(6))))

    run_build(tmp_path / "a")
    run_build(tmp_path / "b")

    assert (tmp_path / "a" / "pairs-train.jsonl").read_bytes() == (tmp_path / "b" / "pairs-train.jsonl").read_bytes()


def test_build_one_repos_selection_does_not_change_when_other_repos_are_added(tmp_path):
    other = dict(issues=[], reviews=[r_review(n, 1, f"다른 저장소의 {n}번째 의견: 테스트 코드를 먼저 작성해 주세요") for n in range(1, 30)])
    write_raw(tmp_path / "alone", "m/mission", **many_reviews_raw())
    write_raw(tmp_path / "both", "m/mission", **many_reviews_raw())
    write_raw(tmp_path / "both", "a/before", **other)         # 이름 순으로 앞에 오는 저장소
    write_raw(tmp_path / "both", "z/after", **other)

    alone, _, _ = run_build(tmp_path / "alone")
    both, _, _ = run_build(tmp_path / "both")

    assert [p for p in both if p["repo"] == "m/mission"] == alone


# ─── build — 중복 제거 ──────────────────────────────────────────────────────────

SAME_TALK = "Please extract this logic into a separate service layer class"


def test_build_drops_repeated_conversation_texts_normalized_across_prs_and_repos(tmp_path):
    first = dict(issues=[], reviews=[r_review(1, 1, SAME_TALK), r_review(2, 2, "  please  EXTRACT this logic into a "
                                                                                 "separate service layer class\n")])
    second = dict(issues=[], reviews=[r_review(3, 1, SAME_TALK.upper()), r_review(4, 1, talk(4))])
    write_raw(tmp_path, "a/one", **first)
    write_raw(tmp_path, "b/two", **second)

    train, _, manifest = run_build(tmp_path)

    assert sorted((p["repo"], p["source"]["number"]) for p in train) == [("a/one", 1), ("b/two", 1)]
    kept_in_one = next(p for p in train if p["repo"] == "a/one")
    assert kept_in_one["positive"] == SAME_TALK                       # 저장소 이름 순 → PR 번호 순으로 먼저 나온 것
    assert manifest["repos"]["a/one"]["dropped"]["duplicate"] == 1
    assert manifest["repos"]["b/two"]["dropped"]["duplicate"] == 1


def test_build_drops_a_repeated_text_across_conversation_kinds_keeping_the_review_side(tmp_path):
    raw = product_raw()
    raw["reviews"] = [r_review(1, 24, SAME_TALK)]
    raw["icomments"] = [r_icomment(1, 24, SAME_TALK), r_icomment(2, 5, SAME_TALK), r_icomment(3, 5, talk(3))]
    write_raw(tmp_path, "o/prod", **raw)

    train, _, manifest = run_build(tmp_path)

    talks = sorted(p["positive_kind"] for p in train if p["positive"] == SAME_TALK)
    assert talks == ["review_comment"]
    assert manifest["repos"]["o/prod"]["dropped"]["duplicate"] == 2


def test_build_change_issue_drops_only_identical_anchor_and_positive_pairs(tmp_path):
    raw = dict(
        product=True,
        issues=[r_issue(5, "로그인 오류 수정 요청", "본문 하나"), r_issue(6, "결제 오류 수정 요청", "본문 둘"),
                r_issue(20, "로그인 화면 개선 요청", "본문 셋")],
        pulls=[r_pull(30, "Fix login error handling flow", "#5 #6"),             # 앵커 하나가 이슈 둘과 짝지어진다
               r_pull(31, "로그인 오류를 다른 방식으로 수정", "#5")],            # 같은 이슈 #5에 다른 앵커
        commits=[r_commit(30, "FIX  login error   handling flow"),               # PR 제목과 정규화하면 같다
                 r_commit(30, "refactor: 로그인 검증을 분리했다"), r_commit(30, "refactor: 로그인 검증을 분리했다"),
                 r_commit(31, "fix: 로그인 오류를 다른 방식으로 수정")],
    )
    write_raw(tmp_path, "o/prod", **raw)

    train, _, manifest = run_build(tmp_path)

    pairs = {(p["anchor_kind"], p["anchor"], p["positive"].split("\n")[0]) for p in train}
    assert len(train) == len(pairs)                                                   # 같은 (앵커, 짝)은 하나뿐
    # PR 30 제목 → #5·#6 (2쌍), 분리 커밋 → #5·#6 (2쌍, 같은 커밋 둘 중 하나는 중복), PR 31 제목·커밋 → #5 (2쌍)
    assert kinds(train) == {"change_issue/pr_title": 3, "change_issue/commit_message": 3}
    assert sum(1 for p in train if p["positive"].startswith("로그인 오류 수정 요청")) == 4     # 같은 짝이 앵커만 달라 여럿
    dropped = manifest["repos"]["o/prod"]["dropped"]
    # 대소문자·공백만 다른 커밋 제목 → #5·#6 에서 2쌍, 같은 분리 커밋 → #5·#6 에서 2쌍
    assert dropped["duplicate"] == 4


# ─── build — 분할 · 라이선스 · 완료 표시 ────────────────────────────────────────


def test_build_sends_val_repos_to_val_ignoring_case_and_the_rest_to_train(tmp_path, capsys):
    # GitHub 이름은 대소문자를 구분하지 않는다 — 원자료의 이름과 --val-repos의 대소문자가 달라도 같은 저장소다
    for full in ("a/one", "Woowa-Org/Two", "c/three"):
        write_raw(tmp_path, full, issues=[], reviews=[r_review(1, 1, f"{full} 저장소의 의견: 테스트 코드를 먼저 작성해 주세요")])

    train, val, manifest = run_build(tmp_path, val="woowa-ORG/two,nobody/missing")

    assert sorted(p["repo"] for p in train) == ["a/one", "c/three"]
    assert [p["repo"] for p in val] == ["Woowa-Org/Two"] and val[0]["split"] == "val"
    assert {p["split"] for p in train} == {"train"}
    assert [manifest["repos"][r]["split"] for r in ("a/one", "Woowa-Org/Two", "c/three")] == ["train", "val", "train"]
    assert manifest["val_repos"] == ["woowa-ORG/two", "nobody/missing"]
    assert "nobody/missing" in capsys.readouterr().out                 # 원자료가 없는 검증 저장소는 알린다


def test_build_default_val_repos_are_the_two_team_projects():
    assert cp.DEFAULT_VAL_REPOS.split(",") == ["woowacourse-teams/2022-dallog", "woowacourse-teams/2023-hang-log"]


@pytest.mark.parametrize("spdx", ["GPL-3.0", None, "NOASSERTION", "AGPL-3.0"])
def test_build_rechecks_the_license_recorded_in_the_raw_meta(tmp_path, spdx):
    write_raw(tmp_path, "o/bad", license=spdx, issues=[], reviews=[r_review(1, 1, talk(1))])
    write_raw(tmp_path, "o/good", license="BSD-3-Clause", issues=[], reviews=[r_review(1, 1, talk(2))])

    train, _, manifest = run_build(tmp_path)

    assert [p["repo"] for p in train] == ["o/good"]
    assert manifest["repos"]["o/bad"]["dropped"]["license"] == 1 and manifest["repos"]["o/bad"]["pairs"] == {}
    assert manifest["repos"]["o/bad"]["license"] == spdx
    assert manifest["repos"]["o/good"]["dropped"]["license"] == 0


def test_build_ignores_raw_dirs_without_a_complete_marker_and_work_dirs(tmp_path):
    write_raw(tmp_path, "o/done", issues=[], reviews=[r_review(1, 1, talk(1))])
    write_raw(tmp_path, "o/half", complete=False, issues=[], reviews=[r_review(1, 1, talk(2))])
    # fetch가 --refresh 도중 죽으면 옛 데이터가 .old-* 로 남을 수 있다. 완료 표시와 파일이 다 있어도 읽으면 안 된다
    leftover = tmp_path / "raw" / ".old-o__stale"
    leftover.mkdir()
    (leftover / "meta.json").write_text(json.dumps({"repo": "o/stale", "license": "MIT", "complete": True}), encoding="utf-8")
    (leftover / "review_comments.jsonl").write_text(json.dumps(r_review(1, 1, talk(3))) + "\n", encoding="utf-8")
    (leftover / "issues.jsonl").write_text("", encoding="utf-8")

    train, _, manifest = run_build(tmp_path)

    assert [p["repo"] for p in train] == ["o/done"]
    assert manifest["incomplete_repos"] == ["o__half"] and sorted(manifest["repos"]) == ["o/done"]


def test_build_without_any_complete_raw_data_exits(tmp_path):
    with pytest.raises(SystemExit):
        cp.main(["build", "--data-dir", str(tmp_path)])
    write_raw(tmp_path, "o/half", complete=False, issues=[], reviews=[])
    with pytest.raises(SystemExit):
        cp.main(["build", "--data-dir", str(tmp_path)])


# ─── build — 텍스트 처리와 출력 모양 ────────────────────────────────────────────


def test_build_masks_natural_text_but_masks_only_urls_in_code_anchors(tmp_path):
    body = "#123 이슈와 HT-12 그리고 https://x.io/a 와 40640b2 커밋을 UTF-8 로 확인했습니다 모두"
    hunk = "@@ -1 +1,2 @@\n+// #123 see https://x.io/b 40640b2 HT-12\n+int x = 1;"
    write_raw(tmp_path, "o/mission", issues=[], reviews=[r_review(1, 1, body, hunk=hunk)])

    train, _, _ = run_build(tmp_path)

    assert train[0]["positive"] == "#N 이슈와 KEY-N 그리고 <URL> 와 <HASH> 커밋을 UTF-8 로 확인했습니다 모두"
    assert train[0]["anchor"] == "[추가] `// #123 see <URL> 40640b2 HT-12`\n[추가] `int x = 1;`"


def test_has_korean_ignores_the_fixed_hunk_labels_but_reads_the_code_itself():
    assert not cp.has_korean("diff_hunk", "[제거] `old()`\n[추가] `newCall()`", "Please rename this")
    assert cp.has_korean("diff_hunk", "[추가] `// 한글 주석`", "Please rename this")
    assert cp.has_korean("diff_hunk", "[추가] `x`", "이름을 바꿔 주세요")
    # 다른 앵커의 대괄호 글자는 우리가 붙인 표지가 아니라 제목의 일부다
    assert cp.has_korean("pr_title", "[추가] add login", "english")
    assert not cp.has_korean("pr_title", "Add login", "english")


def test_build_has_korean_is_true_when_anchor_or_positive_has_hangul(tmp_path):
    write_raw(tmp_path, "o/mission", issues=[], reviews=[
        r_review(1, 1, SAME_TALK),                                                                   # 영어 + 영어 코드
        r_review(2, 2, "Another english review comment about naming", hunk="@@ @@\n+// 한글 주석\n"),    # 앵커에만 한글
        r_review(3, 3, "한글로만 쓴 리뷰 댓글입니다 이름을 바꿔 주세요")])                              # 짝에만 한글

    train, _, manifest = run_build(tmp_path)

    assert {p["source"]["number"]: p["has_korean"] for p in train} == {1: False, 2: True, 3: True}
    assert manifest["has_korean_ratio"] == {"train": round(2 / 3, 4), "val": None}


def test_build_pair_records_have_the_exact_fields_and_no_author_keys(tmp_path):
    write_raw(tmp_path, "o/prod", **product_raw())

    train, _, manifest = run_build(tmp_path)

    expected = ["id", "type", "anchor_kind", "anchor", "positive_kind", "positive", "repo", "split", "has_korean",
                "source", "created_at"]
    for pair in train:
        assert list(pair) == expected and list(pair["source"]) == ["kind", "number"]
        assert re.fullmatch(r"[0-9a-f]{16}", pair["id"])
    assert len({p["id"] for p in train}) == len(train)
    assert [(p["repo"], p["type"], p["id"]) for p in train] == sorted((p["repo"], p["type"], p["id"]) for p in train)
    assert_no_author_keys(train)
    assert_no_author_keys(manifest)


def test_pair_id_is_stable_and_changes_with_every_field():
    base = dict(repo="o/r", type_="change_issue", anchor_kind="pr_title", positive_kind="issue",
                source={"kind": "pr", "number": 1}, anchor="앵커", positive="짝")
    original = cp.pair_id(**base)

    assert original == cp.pair_id(**base) and re.fullmatch(r"[0-9a-f]{16}", original)
    changed = [dict(base, repo="o/x"), dict(base, type_="change_conversation"), dict(base, anchor_kind="commit_message"),
               dict(base, positive_kind="pr_comment"), dict(base, source={"kind": "issue", "number": 1}),
               dict(base, source={"kind": "pr", "number": 2}), dict(base, anchor="앵커2"), dict(base, positive="짝2")]
    assert len({original, *(cp.pair_id(**c) for c in changed)}) == 1 + len(changed)


# ─── build — 매니페스트 ─────────────────────────────────────────────────────────


def test_manifest_counts_match_the_lines_written_to_each_file(tmp_path):
    write_raw(tmp_path, "o/prod", **product_raw())
    write_raw(tmp_path, "o/mission", issues=[], reviews=[r_review(n, 1, talk(100 + n)) for n in range(1, 3)])

    train, val, manifest = run_build(tmp_path, val="o/mission")

    assert manifest["totals"]["train"]["pairs"] == len(train) and manifest["totals"]["val"]["pairs"] == len(val) == 2
    for split, rows in (("train", train), ("val", val)):
        for type_, count in manifest["totals"][split]["by_type"].items():
            assert count == sum(1 for p in rows if p["type"] == type_)
    per_repo = {repo: sum(n for by_kind in info["pairs"].values() for n in by_kind.values())
                for repo, info in manifest["repos"].items()}
    assert per_repo == {"o/prod": len(train), "o/mission": len(val)}
    assert manifest["repos"]["o/prod"]["pairs"]["change_issue"] == {"pr_title": 3, "commit_message": 5}


def test_manifest_records_run_settings_and_every_drop_reason(tmp_path):
    write_raw(tmp_path, "o/mission", issues=[], reviews=[r_review(1, 1, talk(1))])

    _, _, manifest = run_build(tmp_path, "--seed", "5", "--per-pr-review-cap", "2")

    assert re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ", manifest["created_at"])
    assert manifest["seed"] == 5
    assert manifest["args"] == {"per_pr_review_cap": 2, "per_pr_conversation_cap": 3, "per_issue_comment_cap": 3,
                                "per_pr_commit_cap": 3, "per_repo_type_cap": 2000}
    assert set(manifest["repos"]["o/mission"]["dropped"]) == {
        "license", "bot", "copilot", "machine", "short_or_ack", "anchor_short", "commit_filtered", "no_changed_lines",
        "per_pr_cap", "per_issue_cap", "duplicate", "repo_type_cap"}
    assert manifest["repos"]["o/mission"]["product"] is False and manifest["repos"]["o/mission"]["license"] == "MIT"


def test_build_rewrites_outputs_without_leaving_temp_files(tmp_path):
    write_raw(tmp_path, "o/mission", issues=[], reviews=[r_review(1, 1, talk(1))])

    run_build(tmp_path)
    run_build(tmp_path)

    assert sorted(p.name for p in tmp_path.iterdir()) == ["pairs-manifest.json", "pairs-train.jsonl", "pairs-val.jsonl", "raw"]


# ─── fetch → build 왕복 ─────────────────────────────────────────────────────────


def test_fetch_then_build_round_trip_with_a_val_repo(tmp_path):
    run_fetch(tmp_path, make_world())

    train, val, manifest = run_build(tmp_path, val="daangn/prod-b")

    assert kinds(train) == {"change_conversation/diff_hunk": 2}
    assert {p["repo"] for p in train} == {"next-step/mission-a"}
    assert kinds(val) == {
        "change_conversation/diff_hunk": 1, "change_conversation/pr_title": 2, "change_issue/pr_title": 3,
        "change_issue/commit_message": 5, "issue_conversation/issue": 3}
    assert {p["split"] for p in val} == {"val"}
    dropped = manifest["repos"]["daangn/prod-b"]["dropped"]
    assert (dropped["bot"], dropped["short_or_ack"], dropped["commit_filtered"]) == (4, 1, 2)
    dropped = manifest["repos"]["next-step/mission-a"]["dropped"]
    assert (dropped["bot"], dropped["copilot"], dropped["short_or_ack"]) == (1, 1, 1)


def test_fetch_then_build_output_has_no_author_info_and_masked_personal_text(tmp_path):
    run_fetch(tmp_path, make_world())

    train, val, manifest = run_build(tmp_path, val="daangn/prod-b")

    assert_no_author_keys(train), assert_no_author_keys(val), assert_no_author_keys(manifest)
    text = json.dumps(train + val, ensure_ascii=False)
    assert "@zz" not in text and "corp.example.com" not in text and "https://" not in text
    positives = [p["positive"] for p in train]
    assert any("@user 님은 어떻게 생각하세요? <EMAIL>" in p for p in positives)
    assert any("<URL>" in p for p in positives)
    assert any("+@ManyToOne" in "" or "@ManyToOne" in p["anchor"] for p in train)        # 애너테이션 앵커는 그대로


def test_fetch_then_build_links_the_same_prs_that_fetch_downloaded_commits_for(tmp_path):
    run_fetch(tmp_path, make_world())

    _, val, _ = run_build(tmp_path, val="daangn/prod-b")

    commit_sources = {p["source"]["number"] for p in val if p["anchor_kind"] == "commit_message"}
    assert commit_sources == {24, 25}


# ─── sample ─────────────────────────────────────────────────────────────────────

STRATA = [("change_conversation", "diff_hunk"), ("change_conversation", "pr_title"), ("change_issue", "commit_message"),
          ("change_issue", "pr_title"), ("issue_conversation", "issue")]


def write_pairs(data_dir, split, per_stratum):
    rows = []
    for index, ((type_, kind), count) in enumerate(zip(STRATA, per_stratum)):
        for n in range(count):
            rows.append({"id": f"s{index}-{n:03d}", "type": type_, "anchor_kind": kind, "anchor": "a",
                         "positive_kind": "x", "positive": "p", "repo": "o/r", "split": split, "has_korean": True,
                         "source": {"kind": "pr", "number": n}, "created_at": T})
    cp.write_jsonl(str(data_dir / f"pairs-{split}.jsonl"), rows)
    return rows


def run_sample(data_dir, *extra):
    cp.main(["sample", "--data-dir", str(data_dir), *extra])
    return read_jsonl(data_dir / f"pairs-sample{extra[extra.index('--n') + 1] if '--n' in extra else 50}.jsonl")


def by_stratum(rows):
    counts = {}
    for r in rows:
        counts[(r["type"], r["anchor_kind"])] = counts.get((r["type"], r["anchor_kind"]), 0) + 1
    return [counts.get(s, 0) for s in STRATA]


def test_sample_splits_n_evenly_across_strata_with_the_remainder_to_the_first_ones(tmp_path):
    write_pairs(tmp_path, "train", [30] * 5)

    assert by_stratum(run_sample(tmp_path, "--n", "50")) == [10] * 5
    assert by_stratum(run_sample(tmp_path, "--n", "12")) == [3, 3, 2, 2, 2]
    assert by_stratum(run_sample(tmp_path, "--n", "3")) == [1, 1, 1, 0, 0]


def test_sample_takes_what_exists_when_a_stratum_is_short(tmp_path):
    write_pairs(tmp_path, "train", [30, 2, 30, 30, 30])

    rows = run_sample(tmp_path, "--n", "50")

    assert by_stratum(rows) == [10, 2, 10, 10, 10]


def test_sample_is_deterministic_and_the_seed_changes_the_choice(tmp_path):
    write_pairs(tmp_path, "train", [40] * 5)

    first = run_sample(tmp_path, "--n", "50")
    again = run_sample(tmp_path, "--n", "50")
    other = run_sample(tmp_path, "--n", "50", "--seed", "7")

    assert [r["id"] for r in first] == [r["id"] for r in again]
    assert {r["id"] for r in first} != {r["id"] for r in other}
    assert len({r["id"] for r in first}) == len(first)


def test_sample_reads_the_requested_split_and_writes_whole_pair_records(tmp_path):
    train = write_pairs(tmp_path, "train", [10] * 5)
    val = write_pairs(tmp_path, "val", [10] * 5)

    rows = run_sample(tmp_path, "--n", "10", "--split", "val")

    assert {r["split"] for r in rows} == {"val"} and all(r in val for r in rows) and not any(r in train for r in rows)


def test_sample_defaults_to_50_from_the_train_split(tmp_path):
    write_pairs(tmp_path, "train", [30] * 5)

    cp.main(["sample", "--data-dir", str(tmp_path)])

    assert len(read_jsonl(tmp_path / "pairs-sample50.jsonl")) == 50


def test_sample_without_a_pairs_file_exits(tmp_path):
    with pytest.raises(SystemExit):
        cp.main(["sample", "--data-dir", str(tmp_path)])
