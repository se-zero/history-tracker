"""rate_limiter token-bucket 단위 테스트 (오프라인).

핵심 결정 로직(_try_acquire/reconcile)은 가짜 시계로 결정론적으로 검증하고,
acquire의 대기→해제는 실시계로 한 번만 가볍게 확인한다 (타이밍 flaky 회피).
"""

import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import openai_client
import rate_limiter
from rate_limiter import (
    Priority,
    _ModelLimiter,
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


class GatewayTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_completion_routes_through_client(self):
        fake_resp = SimpleNamespace(
            usage=SimpleNamespace(total_tokens=42),
            choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))],
        )
        client = openai_client.get_openai_client()
        with patch.object(client.chat.completions, "create", return_value=fake_resp) as create:
            resp = await openai_client.chat_completion(
                priority=Priority.BACKGROUND,
                model="gpt-4o-mini",
                messages=[{"role": "user", "content": "hi"}],
            )
        self.assertIs(resp, fake_resp)
        self.assertEqual(create.call_args.kwargs["model"], "gpt-4o-mini")

    async def test_embed_routes_through_client(self):
        fake_resp = SimpleNamespace(
            usage=SimpleNamespace(total_tokens=10),
            data=[SimpleNamespace(embedding=[0.1, 0.2])],
        )
        client = openai_client.get_openai_client()
        with patch.object(client.embeddings, "create", return_value=fake_resp) as create:
            resp = await openai_client.embed(
                model="text-embedding-3-small", input=["hi"], priority=Priority.BACKGROUND
            )
        self.assertIs(resp, fake_resp)
        self.assertEqual(create.call_args.kwargs["model"], "text-embedding-3-small")
        self.assertNotIn("dimensions", create.call_args.kwargs)  # 미지정이면 모델 기본 차원

    async def test_embed_passes_dimensions_to_sdk(self):
        # 차원 절삭(Matryoshka)은 SDK 인자로만 걸리므로 게이트웨이가 통과시켜야 한다
        fake_resp = SimpleNamespace(
            usage=SimpleNamespace(total_tokens=10),
            data=[SimpleNamespace(embedding=[0.1, 0.2])],
        )
        client = openai_client.get_openai_client()
        with patch.object(client.embeddings, "create", return_value=fake_resp) as create:
            await openai_client.embed(
                model="text-embedding-3-large",
                input=["hi"],
                priority=Priority.BACKGROUND,
                dimensions=1536,
            )
        self.assertEqual(create.call_args.kwargs["dimensions"], 1536)


if __name__ == "__main__":
    unittest.main()
