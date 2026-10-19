"""rate_limiter token-bucket 단위 테스트 (오프라인).

핵심 결정 로직(_try_acquire/reconcile)은 가짜 시계로 결정론적으로 검증하고,
acquire의 대기→해제는 실시계로 한 번만 가볍게 확인한다 (타이밍 flaky 회피).
"""

import asyncio
import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock, patch

import httpx

import openai_client
import rate_limiter
from rate_limiter import (
    Priority,
    _ModelLimiter,
    _env_cap,
    estimate_chat_tokens,
    estimate_embed_tokens,
    truncate_to_tokens,
)


class _FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    def advance(self, seconds):
        self.t += seconds


class TokenBucketTest(unittest.TestCase):
    def test_rpm_blocks_when_request_slots_drained(self):
        clock = _FakeClock()
        limiter = _ModelLimiter(rpm=2, tpm=10**9, clock=clock)

        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 1))
        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 1))
        self.assertFalse(limiter._try_acquire(Priority.BACKGROUND, 1))  # 슬롯 소진

        clock.advance(30)  # 2 RPM → 30초에 1슬롯 충전
        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 1))

    def test_tpm_blocks_when_token_budget_insufficient(self):
        clock = _FakeClock()
        limiter = _ModelLimiter(rpm=10**9, tpm=100, clock=clock)

        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 100))  # 예산 소진
        self.assertFalse(limiter._try_acquire(Priority.BACKGROUND, 1))

        clock.advance(60)  # 100 TPM → 60초에 100토큰 충전
        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 100))

    def test_background_yields_to_waiting_interactive(self):
        limiter = _ModelLimiter(rpm=10**9, tpm=10**9)
        limiter._interactive_waiters = 1  # INTERACTIVE 대기자 존재

        self.assertFalse(limiter._try_acquire(Priority.BACKGROUND, 1))   # 양보
        self.assertTrue(limiter._try_acquire(Priority.INTERACTIVE, 1))   # 우선 통과

    def test_cost_is_clamped_to_tpm(self):
        # est가 tpm보다 커도 데드락 없이 tpm 한도까지만 예약.
        limiter = _ModelLimiter(rpm=10**9, tpm=100)
        self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 100))

    def test_reconcile_charges_token_underestimate(self):
        limiter = _ModelLimiter(rpm=10**9, tpm=1000)
        before = limiter._tok
        limiter._tok -= 100                  # acquire가 100 예약한 효과 모사
        limiter.reconcile(reserved=100, actual_tokens=150)
        self.assertAlmostEqual(limiter._tok, before - 150, places=6)

    def test_reconcile_refunds_token_overestimate(self):
        limiter = _ModelLimiter(rpm=10**9, tpm=1000)
        before = limiter._tok
        limiter._tok -= 100
        limiter.reconcile(reserved=100, actual_tokens=40)
        self.assertAlmostEqual(limiter._tok, before - 40, places=6)


_LIMIT_ENV = ("OPENAI_RPM_CHAT", "OPENAI_TPM_CHAT", "OPENAI_RPM_EMBED", "OPENAI_TPM_EMBED")


class ObserveHeadersTest(unittest.TestCase):
    def setUp(self):
        rate_limiter.reset()
        self.addCleanup(rate_limiter.reset)
        env = patch.dict(os.environ)
        env.start()
        self.addCleanup(env.stop)
        for name in _LIMIT_ENV:
            os.environ.pop(name, None)

    def test_env_cap_none_when_unset_empty_invalid_or_below_one(self):
        os.environ.pop("X_CAP", None)
        self.assertIsNone(_env_cap("X_CAP"))
        for raw in ("", "abc", "0", "-3"):
            os.environ["X_CAP"] = raw
            self.assertIsNone(_env_cap("X_CAP"), raw)
        os.environ["X_CAP"] = "250"
        self.assertEqual(_env_cap("X_CAP"), 250)

    def test_env_cap_warns_when_set_but_invalid_and_stays_silent_otherwise(self):
        for raw in ("abc", "0", "-3"):
            os.environ["X_CAP"] = raw
            with self.assertLogs("rate_limiter", level="WARNING") as logs:
                self.assertIsNone(_env_cap("X_CAP"))
            self.assertEqual(len(logs.output), 1, raw)
            self.assertIn("X_CAP=", logs.output[0])
            self.assertIn(raw, logs.output[0])
        for raw in ("", "250"):
            os.environ["X_CAP"] = raw
            with self.assertNoLogs("rate_limiter", level="WARNING"):
                _env_cap("X_CAP")
        os.environ.pop("X_CAP", None)
        with self.assertNoLogs("rate_limiter", level="WARNING"):
            self.assertIsNone(_env_cap("X_CAP"))

    def test_observe_headers_learns_limits_up_and_down(self):
        limiter = _ModelLimiter(rpm=500, tpm=200_000, clock=_FakeClock())
        limiter.observe_headers({
            "x-ratelimit-limit-requests": "10000",
            "x-ratelimit-limit-tokens": "10000000",
        })
        self.assertEqual((limiter._rpm, limiter._tpm), (10000, 10_000_000))

        limiter.observe_headers({"x-ratelimit-limit-requests": "3000"})
        self.assertEqual(limiter._rpm, 3000)
        self.assertEqual(limiter._tpm, 10_000_000)

    def test_observe_headers_applies_env_cap(self):
        limiter = _ModelLimiter(rpm=500, tpm=200_000, clock=_FakeClock(), rpm_cap=500)
        limiter.observe_headers({
            "x-ratelimit-limit-requests": "10000",
            "x-ratelimit-limit-tokens": "10000000",
        })
        self.assertEqual(limiter._rpm, 500)
        self.assertEqual(limiter._tpm, 10_000_000)

    def test_observe_headers_accepts_httpx_headers_case_insensitively(self):
        # 실제 응답 헤더 타입(대소문자 무시 매핑)에서 소문자 키 .get 가정이 성립함을 고정한다
        limiter = _ModelLimiter(rpm=500, tpm=200_000, clock=_FakeClock())
        limiter.observe_headers(httpx.Headers({
            "X-RateLimit-Limit-Requests": "10",
            "X-RateLimit-Remaining-Tokens": "5",
        }))
        self.assertEqual(limiter._rpm, 10)
        self.assertEqual(limiter._tok, 5)

    def test_observe_headers_ignores_invalid_or_nonpositive_limits(self):
        limiter = _ModelLimiter(rpm=500, tpm=200_000, clock=_FakeClock())
        for bad in ("0", "-5", "abc"):
            limiter.observe_headers({
                "x-ratelimit-limit-requests": bad,
                "x-ratelimit-limit-tokens": bad,
            })
            self.assertEqual((limiter._rpm, limiter._tpm), (500, 200_000), bad)
        limiter.observe_headers({})
        self.assertEqual((limiter._rpm, limiter._tpm), (500, 200_000))

    def test_observe_headers_remaining_only_lowers(self):
        limiter = _ModelLimiter(rpm=1000, tpm=1_000_000, clock=_FakeClock())
        limiter._req = 400.0
        limiter.observe_headers({"x-ratelimit-remaining-requests": "100"})
        self.assertEqual(limiter._req, 100)
        limiter.observe_headers({"x-ratelimit-remaining-requests": "900"})
        self.assertEqual(limiter._req, 100)
        limiter.observe_headers({"x-ratelimit-remaining-tokens": "0"})
        self.assertEqual(limiter._tok, 0)

    def test_observe_headers_refills_before_sync(self):
        clock = _FakeClock()
        limiter = _ModelLimiter(rpm=60, tpm=10**9, clock=clock)
        for _ in range(60):
            self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 1))
        clock.advance(30)  # 충전되면 30슬롯
        limiter.observe_headers({"x-ratelimit-remaining-requests": "10"})
        self.assertEqual(limiter._req, 10)
        # 시계가 그대로면 과거 구간을 다시 충전하지 않는다
        limiter._try_acquire(Priority.BACKGROUND, 1)
        self.assertEqual(limiter._req, 9)

    def test_learned_capacity_changes_refill_rate(self):
        clock = _FakeClock()
        limiter = _ModelLimiter(rpm=60, tpm=10**9, clock=clock)
        for _ in range(60):
            limiter._try_acquire(Priority.BACKGROUND, 1)
        limiter.observe_headers({"x-ratelimit-limit-requests": "6000"})
        before = limiter._req
        clock.advance(1)
        limiter._refill()
        self.assertAlmostEqual(limiter._req - before, 100, delta=1)
        self.assertLessEqual(limiter._req, 6000)

    def test_limit_learned_is_logged_on_first_observe_and_on_change_only(self):
        limiter = _ModelLimiter(rpm=500, tpm=200_000, clock=_FakeClock(), name="chat/m")
        same = {"x-ratelimit-limit-requests": "500", "x-ratelimit-limit-tokens": "200000"}
        with self.assertLogs("rate_limiter", level="INFO") as cm:
            limiter.observe_headers(same)
        self.assertEqual(len(cm.output), 1)
        with self.assertNoLogs("rate_limiter", level="INFO"):
            limiter.observe_headers(same)
        with self.assertLogs("rate_limiter", level="INFO") as cm:
            limiter.observe_headers({"x-ratelimit-limit-requests": "900"})
        self.assertEqual(len(cm.output), 1)

    def test_get_chat_limiter_is_keyed_per_model_and_cached(self):
        a = rate_limiter.get_chat_limiter("gpt-4o-mini")
        self.assertIs(a, rate_limiter.get_chat_limiter("gpt-4o-mini"))
        self.assertIsNot(a, rate_limiter.get_chat_limiter("gpt-5.4-mini"))
        self.assertIsNot(a, rate_limiter.get_embed_limiter("gpt-4o-mini"))
        self.assertIs(rate_limiter.get_chat_limiter(), rate_limiter.get_chat_limiter())

    def test_bootstrap_uses_env_as_value_and_cap_when_set_else_default_without_cap(self):
        os.environ["OPENAI_RPM_CHAT"] = "123"
        limiter = rate_limiter.get_chat_limiter("m1")
        self.assertEqual(limiter._rpm, 123)
        self.assertEqual(limiter._rpm_cap, 123)

        os.environ.pop("OPENAI_RPM_CHAT")
        rate_limiter.reset()
        limiter = rate_limiter.get_chat_limiter("m1")
        self.assertEqual(limiter._rpm, 500)
        self.assertIsNone(limiter._rpm_cap)


class AcquireAsyncTest(unittest.IsolatedAsyncioTestCase):
    async def test_acquire_returns_immediately_when_capacity_available(self):
        limiter = _ModelLimiter(rpm=500, tpm=200_000)
        reserved = await asyncio.wait_for(limiter.acquire(Priority.INTERACTIVE, 50), timeout=1.0)
        self.assertEqual(reserved, 50)

    async def test_acquire_unblocks_after_refill(self):
        # rpm=60 → 1초당 1슬롯 충전. 슬롯을 모두 소진한 뒤 acquire가 충전 후 풀리는지.
        limiter = _ModelLimiter(rpm=60, tpm=10**9)
        for _ in range(60):
            self.assertTrue(limiter._try_acquire(Priority.BACKGROUND, 0))
        self.assertFalse(limiter._try_acquire(Priority.BACKGROUND, 0))

        reserved = await asyncio.wait_for(limiter.acquire(Priority.BACKGROUND, 0), timeout=3.0)
        self.assertEqual(reserved, 0)


class EstimateTest(unittest.TestCase):
    def test_estimate_chat_tokens_positive_with_reserve(self):
        n = estimate_chat_tokens([{"role": "user", "content": "안녕하세요 테스트"}], "gpt-4o-mini")
        self.assertGreater(n, 0)

    def test_estimate_chat_tokens_skips_non_str_content(self):
        n = estimate_chat_tokens(
            [{"role": "assistant", "content": None}, "not-a-dict"], "gpt-4o-mini"
        )
        self.assertGreater(n, 0)  # 응답 예약분만 남아도 양수

    def test_estimate_embed_tokens_sums_list(self):
        n = estimate_embed_tokens(["foo", "bar baz"], "text-embedding-3-small")
        self.assertGreater(n, 0)

    def test_estimate_embed_tokens_accepts_str(self):
        n = estimate_embed_tokens("single string", "text-embedding-3-small")
        self.assertGreater(n, 0)


class TruncateTest(unittest.TestCase):
    _MODEL = "text-embedding-3-large"

    def test_short_text_is_returned_unchanged(self):
        self.assertEqual(truncate_to_tokens("짧은 글 short", self._MODEL, 100), "짧은 글 short")

    def test_empty_text_is_returned_unchanged(self):
        self.assertEqual(truncate_to_tokens("", self._MODEL, 100), "")

    def test_long_text_is_cut_to_a_prefix_within_the_token_limit(self):
        text = "가나다라 " * 5000
        result = truncate_to_tokens(text, self._MODEL, 100)

        self.assertLess(len(result), len(text))
        self.assertTrue(text.startswith(result))
        self.assertLessEqual(rate_limiter._count_tokens(result, self._MODEL), 100)

    def test_cut_never_ends_with_a_broken_character(self):
        # 토큰 경계가 멀티바이트 글자 한가운데에 걸리는 혼합 문장 — 잘린 끝이 깨진 글자(U+FFFD)면 안 된다
        text = "한국어 and English mix 변경 사항 `foo_bar` 수정 " * 600
        for limit in range(50, 120):
            result = truncate_to_tokens(text, self._MODEL, limit)
            self.assertTrue(text.startswith(result), f"limit={limit}")

    def test_without_encoder_falls_back_to_character_cut(self):
        # 띄어쓰기 없는 한글은 실측 1.43토큰/자라 글자 max_tokens개를 남기면 상한을 넘는다 — 절반만 남긴다
        text = "가나다라 " * 5000
        with patch.object(rate_limiter, "_encoder", lambda model: None):
            result = truncate_to_tokens(text, self._MODEL, 100)

        self.assertEqual(len(result), 50)
        self.assertTrue(text.startswith(result))

    def test_special_token_strings_are_counted_as_plain_text(self):
        # tiktoken 기본 설정은 본문에 "<|endoftext|>" 같은 특수 토큰 문자열이 있으면 encode가 예외를 낸다.
        # OpenAI 서버는 그 문자열을 보통 글자로 세므로 우리도 그렇게 세어 토큰 컷을 유지한다(글자 폴백 아님)
        text = "<|endoftext|> " + "가나다라 " * 5000
        enc = rate_limiter._encoder(self._MODEL)
        if enc is None:
            self.skipTest("tiktoken 미가용 — 토큰 컷 자체를 검증할 수 없다")
        expected = enc.decode_bytes(enc.encode(text, disallowed_special=())[:100]).decode("utf-8", errors="ignore")

        self.assertEqual(truncate_to_tokens(text, self._MODEL, 100), expected)


def _raw(resp, headers=None):
    """SDK with_raw_response 반환(LegacyAPIResponse) 대역. headers는 실물 타입(httpx.Headers)으로 감싼다."""
    return SimpleNamespace(headers=httpx.Headers(headers or {}), parse=lambda: resp)


class _ApiErr(Exception):
    pass


def _chat_resp(total=42):
    return SimpleNamespace(
        usage=SimpleNamespace(total_tokens=total),
        choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))],
    )


def _embed_resp():
    return SimpleNamespace(
        usage=SimpleNamespace(total_tokens=10),
        data=[SimpleNamespace(embedding=[0.1, 0.2])],
    )


def _chat_create(**kw):
    client = openai_client.get_openai_client()
    return patch.object(
        client.chat.completions.with_raw_response, "create", new_callable=AsyncMock, **kw
    )


def _embed_create(**kw):
    client = openai_client.get_openai_client()
    return patch.object(
        client.embeddings.with_raw_response, "create", new_callable=AsyncMock, **kw
    )


async def _chat(priority=Priority.BACKGROUND, model="gpt-4o-mini"):
    return await openai_client.chat_completion(
        priority=priority, model=model, messages=[{"role": "user", "content": "hi"}]
    )


def _clean_env():
    env = patch.dict(os.environ)
    env.start()
    for name in (*_LIMIT_ENV, "OPENAI_BACKGROUND_CONCURRENCY"):
        os.environ.pop(name, None)
    # 게이트는 첫 생성 시점의 env로 굳으므로 테스트마다 비워 이전 테스트의 상한이 새지 않게 한다
    openai_client._gate = None
    openai_client._gate_loop = None
    return env


class GatewayTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        rate_limiter.reset()
        self.addCleanup(rate_limiter.reset)
        env = _clean_env()
        self.addCleanup(env.stop)

    async def test_chat_completion_routes_through_client(self):
        fake_resp = _chat_resp()
        with _chat_create(return_value=_raw(fake_resp)) as create:
            resp = await _chat()
        self.assertIs(resp, fake_resp)
        self.assertEqual(create.call_args.kwargs["model"], "gpt-4o-mini")

    async def test_embed_routes_through_client(self):
        fake_resp = _embed_resp()
        with _embed_create(return_value=_raw(fake_resp)) as create:
            resp = await openai_client.embed(
                model="text-embedding-3-small", input=["hi"], priority=Priority.BACKGROUND
            )
        self.assertIs(resp, fake_resp)
        self.assertEqual(create.call_args.kwargs["model"], "text-embedding-3-small")
        self.assertNotIn("dimensions", create.call_args.kwargs)  # 미지정이면 모델 기본 차원

    async def test_embed_passes_dimensions_to_sdk(self):
        # 차원 절삭(Matryoshka)은 SDK 인자로만 걸리므로 게이트웨이가 통과시켜야 한다
        fake_resp = _embed_resp()
        with _embed_create(return_value=_raw(fake_resp)) as create:
            resp = await openai_client.embed(
                model="text-embedding-3-large",
                input=["hi"],
                priority=Priority.BACKGROUND,
                dimensions=1536,
            )
        self.assertEqual(create.call_args.kwargs["dimensions"], 1536)
        self.assertIs(resp, fake_resp)  # parse() 결과가 그대로 반환된다

    async def test_chat_completion_learns_limits_from_response_headers(self):
        raw = _raw(_chat_resp(), {
            "X-RateLimit-Limit-Requests": "10000",
            "X-RateLimit-Limit-Tokens": "10000000",
        })
        with _chat_create(return_value=raw):
            await _chat()
        limiter = rate_limiter.get_chat_limiter("gpt-4o-mini")
        self.assertEqual(limiter._rpm, 10000)
        self.assertEqual(limiter._tpm, 10_000_000)

    async def test_chat_completion_uses_bucket_for_request_model(self):
        fake = MagicMock()
        fake.acquire = AsyncMock(return_value=5)
        with patch.object(openai_client, "get_chat_limiter", return_value=fake) as getter, \
             _chat_create(return_value=_raw(_chat_resp())):
            await _chat()
        getter.assert_called_once_with("gpt-4o-mini")

    async def test_embed_uses_bucket_for_model(self):
        fake = MagicMock()
        fake.acquire = AsyncMock(return_value=5)
        with patch.object(openai_client, "get_embed_limiter", return_value=fake) as getter, \
             _embed_create(return_value=_raw(_embed_resp())):
            await openai_client.embed(
                model="text-embedding-3-large", input=["hi"], priority=Priority.BACKGROUND
            )
        getter.assert_called_once_with("text-embedding-3-large")

    async def test_failure_syncs_remaining_from_error_response(self):
        exc = _ApiErr("429")
        exc.response = SimpleNamespace(headers={"x-ratelimit-remaining-requests": "0"})
        with _chat_create(side_effect=exc):
            with self.assertRaises(_ApiErr):
                await _chat()
        self.assertEqual(rate_limiter.get_chat_limiter("gpt-4o-mini")._req, 0)

    async def test_failure_without_response_attr_does_not_break(self):
        with _chat_create(side_effect=_ApiErr("conn")):
            with self.assertRaises(_ApiErr):
                await _chat()
        limiter = rate_limiter.get_chat_limiter("gpt-4o-mini")
        # 헤더 동기화는 일어나지 않고(학습 없음), 요청 슬롯은 acquire가 쓴 1개만 줄어든다(remaining 동기화 없음).
        self.assertFalse(limiter._learned)
        self.assertEqual(limiter._req, limiter._rpm - 1)

    async def test_background_gate_bounds_in_flight(self):
        os.environ["OPENAI_BACKGROUND_CONCURRENCY"] = "2"
        state = {"in_flight": 0, "peak": 0}

        async def slow(**kwargs):
            state["in_flight"] += 1
            state["peak"] = max(state["peak"], state["in_flight"])
            await asyncio.sleep(0.01)
            state["in_flight"] -= 1
            return _raw(_chat_resp())

        with _chat_create(side_effect=slow):
            await asyncio.gather(*(_chat() for _ in range(6)))
        self.assertEqual(state["peak"], 2)

    async def test_interactive_bypasses_background_gate(self):
        os.environ["OPENAI_BACKGROUND_CONCURRENCY"] = "1"
        entered = asyncio.Event()
        release = asyncio.Event()
        calls = {"n": 0}

        async def create(**kwargs):
            calls["n"] += 1
            if calls["n"] == 1:  # 첫 BACKGROUND 호출이 게이트를 점유한다
                entered.set()
                await release.wait()
            return _raw(_chat_resp())

        with _chat_create(side_effect=create):
            bg = asyncio.create_task(_chat(Priority.BACKGROUND))
            await entered.wait()
            try:
                await asyncio.wait_for(_chat(Priority.INTERACTIVE), timeout=0.5)
            finally:
                release.set()
                await bg


class GatewayLoopRebindTest(unittest.TestCase):
    def setUp(self):
        rate_limiter.reset()
        self.addCleanup(rate_limiter.reset)
        env = _clean_env()
        self.addCleanup(env.stop)

    def test_background_gate_is_rebound_per_event_loop(self):
        os.environ["OPENAI_BACKGROUND_CONCURRENCY"] = "1"

        async def slow(**kwargs):
            await asyncio.sleep(0.01)
            return _raw(_chat_resp())

        async def scenario():
            await asyncio.gather(_chat(), _chat())  # 게이트 경합 → 세마포어가 루프에 묶인다

        with _chat_create(side_effect=slow):
            asyncio.run(scenario())
            asyncio.run(scenario())  # 새 루프에서도 RuntimeError 없이 돌아야 한다


if __name__ == "__main__":
    unittest.main()
