"""임베딩 전용 클라이언트 분기 단위 테스트 (오프라인).

EMBEDDING_BASE_URL을 주면 embed()만 다른 OpenAI 호환 서버로 가고, 비우면 공용 클라이언트를
그대로 쓴다. chat은 어느 쪽이든 공용 클라이언트다 — 임베딩 모델을 갈아 끼우는 실험이 답변
LLM까지 옮겨 가면 안 되기 때문이다.
"""

import os
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

import openai_client
import rate_limiter
from rate_limiter import Priority

_EMBEDDING_ENV = (
    "EMBEDDING_BASE_URL",
    "EMBEDDING_API_KEY",
    "EMBEDDING_TIMEOUT_SECONDS",
    "OPENAI_MAX_RETRIES",
    "OPENAI_BACKGROUND_CONCURRENCY",
)
_SERVER = "http://embedding-host:8100/v1"


def _raw(resp):
    return SimpleNamespace(headers=httpx.Headers({}), parse=lambda: resp)


def _embed_resp():
    return SimpleNamespace(usage=SimpleNamespace(total_tokens=10), data=[SimpleNamespace(embedding=[0.1, 0.2])])


def _chat_resp():
    return SimpleNamespace(
        usage=SimpleNamespace(total_tokens=5),
        choices=[SimpleNamespace(message=SimpleNamespace(content="ok"))],
    )


class _EmbeddingClientTestBase(unittest.TestCase):
    def setUp(self):
        env = patch.dict(os.environ)
        env.start()
        self.addCleanup(env.stop)
        for name in _EMBEDDING_ENV:
            os.environ.pop(name, None)
        # 같은 인자면 같은 객체를 돌려주는 캐시라 테스트 사이에 비워야 앞 테스트의 클라이언트가 새지 않는다
        openai_client._embedding_client_for.cache_clear()
        self.addCleanup(openai_client._embedding_client_for.cache_clear)
        openai_client._gate = None
        openai_client._gate_loop = None


class EmbeddingClientSelectionTest(_EmbeddingClientTestBase):
    def test_without_base_url_returns_shared_client(self):
        # 기존 테스트가 공용 클라이언트의 embeddings를 패치하므로 "같은 객체"여야 한다
        self.assertIs(openai_client.get_embedding_client(), openai_client.get_openai_client())

    def test_blank_base_url_is_treated_as_unset(self):
        # compose가 빈 값(${VAR:-})을 넘기므로 빈 문자열·공백은 미설정이다
        for blank in ("", "   "):
            with self.subTest(blank=blank):
                os.environ["EMBEDDING_BASE_URL"] = blank
                self.assertIs(openai_client.get_embedding_client(), openai_client.get_openai_client())

    def test_base_url_creates_separate_client(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER

        client = openai_client.get_embedding_client()

        self.assertIsNot(client, openai_client.get_openai_client())
        self.assertEqual(str(client.base_url).rstrip("/"), _SERVER)

    def test_base_url_is_stripped(self):
        os.environ["EMBEDDING_BASE_URL"] = f"  {_SERVER}  "

        self.assertEqual(str(openai_client.get_embedding_client().base_url).rstrip("/"), _SERVER)

    def test_api_key_defaults_to_placeholder(self):
        # 자체 서버는 키를 검증하지 않지만 SDK가 빈 키를 거부하므로 자리표시 값을 넣는다
        os.environ["EMBEDDING_BASE_URL"] = _SERVER

        self.assertEqual(openai_client.get_embedding_client().api_key, "unused")

    def test_api_key_follows_env(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        os.environ["EMBEDDING_API_KEY"] = "embed-secret"

        self.assertEqual(openai_client.get_embedding_client().api_key, "embed-secret")

    def test_blank_api_key_falls_back_to_placeholder(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        os.environ["EMBEDDING_API_KEY"] = ""

        self.assertEqual(openai_client.get_embedding_client().api_key, "unused")

    def test_does_not_send_openai_key_to_embedding_server(self):
        # 공용 OPENAI_API_KEY가 다른 서버로 새면 안 된다
        os.environ["EMBEDDING_BASE_URL"] = _SERVER

        self.assertNotEqual(openai_client.get_embedding_client().api_key, os.environ["OPENAI_API_KEY"])

    def test_timeout_defaults_to_sixty(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER

        self.assertEqual(openai_client.get_embedding_client().timeout, 60)

    def test_timeout_follows_env(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        os.environ["EMBEDDING_TIMEOUT_SECONDS"] = "180"

        self.assertEqual(openai_client.get_embedding_client().timeout, 180)

    def test_invalid_timeout_falls_back_to_sixty(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        for bad in ("abc", "0", "-5", "1.5", ""):
            with self.subTest(bad=bad):
                openai_client._embedding_client_for.cache_clear()
                os.environ["EMBEDDING_TIMEOUT_SECONDS"] = bad
                self.assertEqual(openai_client.get_embedding_client().timeout, 60)

    def test_max_retries_follows_openai_rule(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        self.assertEqual(openai_client.get_embedding_client().max_retries, 3)

        os.environ["OPENAI_MAX_RETRIES"] = "7"
        self.assertEqual(openai_client.get_embedding_client().max_retries, 7)

    def test_same_env_returns_same_client_and_changed_env_returns_new_one(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        first = openai_client.get_embedding_client()

        self.assertIs(openai_client.get_embedding_client(), first)

        os.environ["EMBEDDING_BASE_URL"] = "http://other-host:8100/v1"
        changed_url = openai_client.get_embedding_client()
        self.assertIsNot(changed_url, first)

        os.environ["EMBEDDING_API_KEY"] = "another"
        self.assertIsNot(openai_client.get_embedding_client(), changed_url)

        os.environ["EMBEDDING_TIMEOUT_SECONDS"] = "10"
        changed_timeout = openai_client.get_embedding_client()
        self.assertEqual(changed_timeout.timeout, 10)


class EmbeddingGatewayRoutingTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        rate_limiter.reset()
        self.addCleanup(rate_limiter.reset)
        env = patch.dict(os.environ)
        env.start()
        self.addCleanup(env.stop)
        for name in _EMBEDDING_ENV:
            os.environ.pop(name, None)
        openai_client._embedding_client_for.cache_clear()
        self.addCleanup(openai_client._embedding_client_for.cache_clear)
        openai_client._gate = None
        openai_client._gate_loop = None

    async def test_embed_goes_to_embedding_client_not_shared_client(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        shared = openai_client.get_openai_client()
        separate = openai_client.get_embedding_client()
        fake_resp = _embed_resp()

        with patch.object(shared.embeddings.with_raw_response, "create", new_callable=AsyncMock) as shared_create, \
             patch.object(
                 separate.embeddings.with_raw_response, "create",
                 new_callable=AsyncMock, return_value=_raw(fake_resp),
             ) as separate_create:
            resp = await openai_client.embed(
                model="qwen3-embedding", input=["hi"], priority=Priority.BACKGROUND, dimensions=1024
            )

        self.assertIs(resp, fake_resp)
        separate_create.assert_awaited_once()
        self.assertEqual(separate_create.await_args.kwargs["model"], "qwen3-embedding")
        self.assertEqual(separate_create.await_args.kwargs["dimensions"], 1024)
        shared_create.assert_not_called()

    async def test_embed_uses_shared_client_when_unset(self):
        shared = openai_client.get_openai_client()
        fake_resp = _embed_resp()

        with patch.object(
            shared.embeddings.with_raw_response, "create",
            new_callable=AsyncMock, return_value=_raw(fake_resp),
        ) as shared_create:
            resp = await openai_client.embed(
                model="text-embedding-3-large", input=["hi"], priority=Priority.BACKGROUND
            )

        self.assertIs(resp, fake_resp)
        shared_create.assert_awaited_once()

    async def test_chat_completion_keeps_using_shared_client(self):
        os.environ["EMBEDDING_BASE_URL"] = _SERVER
        shared = openai_client.get_openai_client()
        separate = openai_client.get_embedding_client()
        fake_resp = _chat_resp()

        with patch.object(
            shared.chat.completions.with_raw_response, "create",
            new_callable=AsyncMock, return_value=_raw(fake_resp),
        ) as shared_create, \
             patch.object(separate.chat.completions.with_raw_response, "create", new_callable=AsyncMock) as separate_create:
            resp = await openai_client.chat_completion(
                priority=Priority.BACKGROUND, model="gpt-4o-mini",
                messages=[{"role": "user", "content": "hi"}],
            )

        self.assertIs(resp, fake_resp)
        shared_create.assert_awaited_once()
        separate_create.assert_not_called()


if __name__ == "__main__":
    unittest.main()
