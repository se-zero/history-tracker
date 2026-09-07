"""alerts.py 단위 테스트 (오프라인) — 분류·억제·발송·스냅샷·관문 훅.

시간 의존 테스트는 동기로 두고 alerts.time.monotonic을 패치한다(asyncio.run 밖).
전송 경로(=_dispatch가 만드는 태스크)를 실제로 띄우는 테스트는 asyncio.run 안에서
alerts.drain()으로 태스크 완료를 기다린다. 각 테스트는 alerts.reset()으로 시작한다.
"""

import asyncio
import logging
from types import SimpleNamespace
from unittest.mock import patch
import unittest

import httpx
import pytest

import alerts
import openai_client
from rate_limiter import Priority


class _StubAPIError(Exception):
    """openai.APIStatusError 대역 — status_code/code/type 속성만 흉내낸다."""

    def __init__(self, status_code=None, code=None, type_=None, msg="stub"):
        super().__init__(msg)
        self.status_code = status_code
        self.code = code
        self.type = type_


# --- 분류 --------------------------------------------------------------------

def test_classify_quota_by_code():
    alerts.reset()
    exc = _StubAPIError(429, code="insufficient_quota")
    assert alerts.classify_openai_failure(exc) == alerts.KIND_QUOTA


def test_classify_quota_by_type_only():
    alerts.reset()
    exc = _StubAPIError(429, type_="insufficient_quota")
    assert alerts.classify_openai_failure(exc) == alerts.KIND_QUOTA


def test_classify_429_without_quota_code_is_transient():
    alerts.reset()
    exc = _StubAPIError(429, code="rate_limit_exceeded")
    assert alerts.classify_openai_failure(exc) == alerts.KIND_TRANSIENT


@pytest.mark.parametrize("status", [400, 401, 403, 404, 422])
def test_classify_unrecoverable_statuses(status):
    alerts.reset()
    exc = _StubAPIError(status)
    assert alerts.classify_openai_failure(exc) == alerts.KIND_UNRECOVERABLE


def test_classify_5xx_is_transient():
    alerts.reset()
    exc = _StubAPIError(503)
    assert alerts.classify_openai_failure(exc) == alerts.KIND_TRANSIENT


def test_classify_no_status_code_is_transient():
    alerts.reset()
    assert alerts.classify_openai_failure(RuntimeError("boom")) == alerts.KIND_TRANSIENT


# --- 쿼터 ----------------------------------------------------------------------

def test_quota_alerts_immediately_and_sets_exhausted_at(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    kind = alerts.record_openai_failure(
        _StubAPIError(429, code="insufficient_quota"), caller="chat", model="gpt-5.4-mini"
    )

    assert kind == alerts.KIND_QUOTA
    assert len(calls) == 1
    assert alerts._quota_exhausted_at is not None


def test_success_clears_quota_flag(monkeypatch):
    alerts.reset()
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: None)
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    alerts.record_openai_failure(_StubAPIError(429, code="insufficient_quota"), caller="chat")
    assert alerts._quota_exhausted_at is not None

    alerts.record_openai_success()
    assert alerts._quota_exhausted_at is None


# --- 본문 ----------------------------------------------------------------------

def test_unrecoverable_alert_text_has_caller_model_status_code(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    alerts.record_openai_failure(
        _StubAPIError(401, code="invalid_api_key"), caller="chat", model="gpt-5.4-mini"
    )

    _, text = calls[0]
    assert "chat(gpt-5.4-mini)" in text
    assert "HTTP 401" in text
    assert "invalid_api_key" in text


# --- 억제 ----------------------------------------------------------------------

def test_same_kind_suppressed_within_window_and_counted(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    alerts.record_openai_failure(_StubAPIError(401), caller="chat")
    t[0] = 10.0
    alerts.record_openai_failure(_StubAPIError(401), caller="chat")

    assert len(calls) == 1
    assert alerts._suppressed[alerts.KIND_UNRECOVERABLE] == 1
    assert alerts._counters[alerts.KIND_UNRECOVERABLE] == 2


def test_alert_resumes_after_suppress_window_with_held_count(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    alerts.record_openai_failure(_StubAPIError(401), caller="chat")
    t[0] = 10.0
    alerts.record_openai_failure(_StubAPIError(401), caller="chat")  # 억제 (held=1)
    t[0] = 3601.0
    alerts.record_openai_failure(_StubAPIError(401), caller="chat")

    assert len(calls) == 2
    assert "1건 추가 발생" in calls[-1][1]


def test_different_kinds_not_suppressed_by_each_other(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    alerts.record_openai_failure(_StubAPIError(401), caller="chat")
    alerts.record_dlq_parked("p1")

    assert len(calls) == 2


# --- 일시 오류 창 -----------------------------------------------------------------

def test_transient_below_threshold_silent(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    for _ in range(4):
        alerts.record_openai_failure(_StubAPIError(503), caller="embed")
        t[0] += 1.0

    assert calls == []


def test_transient_alert_at_threshold(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    for _ in range(5):
        alerts.record_openai_failure(_StubAPIError(503), caller="embed", model="text-embedding-3-small")
        t[0] += 1.0

    assert len(calls) == 1


def test_transient_window_prunes_old_entries(monkeypatch):
    alerts.reset()
    calls = []
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: calls.append((kind, text)))
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    for _ in range(4):
        alerts.record_openai_failure(_StubAPIError(503), caller="embed")
    t[0] = 601.0
    alerts.record_openai_failure(_StubAPIError(503), caller="embed")

    assert calls == []
    assert len(alerts._transient_window) == 1


def test_transient_window_cleared_after_alert(monkeypatch):
    alerts.reset()
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: None)
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    for _ in range(5):
        alerts.record_openai_failure(_StubAPIError(503), caller="embed")

    assert len(alerts._transient_window) == 0


# --- 안전 ----------------------------------------------------------------------

def test_record_failure_never_raises(monkeypatch):
    alerts.reset()

    def boom(kind, text):
        raise RuntimeError("dispatch boom")

    monkeypatch.setattr(alerts, "_dispatch", boom)
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    kind = alerts.record_openai_failure(_StubAPIError(401), caller="chat")

    assert kind == alerts.KIND_UNRECOVERABLE


def test_dispatch_without_running_loop_counts_but_does_not_send(monkeypatch, caplog):
    alerts.reset()
    monkeypatch.setenv("ALERT_SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/x")
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)
    caplog.set_level(logging.ERROR, logger="alerts")

    kind = alerts.record_openai_failure(_StubAPIError(401), caller="chat")

    assert kind == alerts.KIND_UNRECOVERABLE
    assert alerts._counters[alerts.KIND_UNRECOVERABLE] == 1
    assert alerts._send_tasks == set()
    # _dispatch가 루프 부재로 RuntimeError를 던지면 record_openai_failure가 삼켜 위 단언은
    # 통과하지만 "알림 기록 실패" 예외 로그가 남는다 — 그 결함을 구분하기 위한 단언.
    assert "알림 기록 실패" not in caplog.text


def test_dispatch_with_empty_url_does_not_send(monkeypatch):
    alerts.reset()
    monkeypatch.delenv("ALERT_SLACK_WEBHOOK_URL", raising=False)

    async def scenario():
        alerts.record_dlq_parked("p1")
        await alerts.drain()

    asyncio.run(scenario())

    snap = alerts.snapshot()
    assert snap["webhook_configured"] is False
    assert alerts._send_tasks == set()


# --- 전송 ----------------------------------------------------------------------

class _FakeResponse:
    def raise_for_status(self):
        pass


class _FakeAsyncClient:
    instances = []

    def __init__(self, timeout=None):
        self.timeout = timeout
        self.posts = []
        _FakeAsyncClient.instances.append(self)

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def post(self, url, json=None):
        self.posts.append((url, json))
        return _FakeResponse()


class _BoomAsyncClient:
    def __init__(self, timeout=None):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def post(self, url, json=None):
        raise httpx.ConnectError("boom")


def test_post_sends_slack_payload(monkeypatch):
    alerts.reset()
    monkeypatch.setenv("ALERT_SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/x")
    _FakeAsyncClient.instances = []
    monkeypatch.setattr(alerts.httpx, "AsyncClient", _FakeAsyncClient)

    async def scenario():
        alerts.record_dlq_parked("alert-test")
        await alerts.drain()

    asyncio.run(scenario())

    client = _FakeAsyncClient.instances[0]
    url, payload = client.posts[0]
    assert url == "https://hooks.slack.com/services/x"
    assert payload["text"].startswith("[ai-engine] ")
    assert alerts._alerts_sent == 1


def test_post_failure_is_swallowed_and_counted(monkeypatch):
    alerts.reset()
    monkeypatch.setenv("ALERT_SLACK_WEBHOOK_URL", "https://hooks.slack.com/services/x")
    monkeypatch.setattr(alerts.httpx, "AsyncClient", _BoomAsyncClient)

    async def scenario():
        alerts.record_dlq_parked("p1")
        await alerts.drain()

    asyncio.run(scenario())  # 예외 없이 끝나야 한다(전송 실패는 삼킨다)

    assert alerts._send_failures == 1


# --- 형태 ----------------------------------------------------------------------

def test_snapshot_shape():
    alerts.reset()
    snap = alerts.snapshot()

    assert set(snap.keys()) == {
        "webhook_configured", "counters", "suppressed", "transient_in_window",
        "quota_exhausted_at", "last_alert_age_seconds", "alerts_sent", "send_failures",
    }
    assert set(snap["counters"].keys()) == set(alerts.KINDS)
    assert all(v == 0 for v in snap["counters"].values())
    assert set(snap["suppressed"].keys()) == set(alerts.KINDS)


def test_snapshot_prunes_stale_transient_window(monkeypatch):
    alerts.reset()
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: None)
    t = [0.0]
    monkeypatch.setattr(alerts.time, "monotonic", lambda: t[0])

    for _ in range(4):
        alerts.record_openai_failure(_StubAPIError(503), caller="embed")

    t[0] = 601.0
    assert alerts.snapshot()["transient_in_window"] == 0


def test_reset_clears_everything(monkeypatch):
    monkeypatch.setattr(alerts, "_dispatch", lambda kind, text: None)
    monkeypatch.setattr(alerts.time, "monotonic", lambda: 0.0)

    alerts.record_openai_failure(_StubAPIError(401), caller="chat")
    alerts.record_dlq_parked("p1")

    alerts.reset()

    snap = alerts.snapshot()
    assert all(v == 0 for v in snap["counters"].values())
    assert all(v == 0 for v in snap["suppressed"].values())
    assert snap["quota_exhausted_at"] is None
    assert snap["alerts_sent"] == 0
    assert snap["send_failures"] == 0


# --- 관문 ----------------------------------------------------------------------

class _FakeLimiter:
    def __init__(self, reserved=10):
        self.reserved = reserved
        self.acquire_calls = []
        self.reconcile_calls = []

    async def acquire(self, priority, est):
        self.acquire_calls.append((priority, est))
        return self.reserved

    def reconcile(self, reserved, actual):
        self.reconcile_calls.append((reserved, actual))


class GatewayAlertHookTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_failure_records_unrecoverable_alert(self):
        alerts.reset()
        client = openai_client.get_openai_client()
        limiter = _FakeLimiter()
        with patch.object(openai_client, "get_chat_limiter", return_value=limiter), \
             patch.object(client.chat.completions, "create",
                          side_effect=_StubAPIError(401, code="invalid_api_key")):
            with self.assertRaises(_StubAPIError):
                await openai_client.chat_completion(
                    priority=Priority.BACKGROUND, model="gpt-5.4-mini", messages=[]
                )

        self.assertEqual(alerts._counters[alerts.KIND_UNRECOVERABLE], 1)
        self.assertEqual(limiter.reconcile_calls, [(limiter.reserved, limiter.reserved)])

    async def test_embed_failure_recorded_with_embed_caller(self):
        alerts.reset()
        calls = []
        client = openai_client.get_openai_client()
        limiter = _FakeLimiter()
        with patch.object(openai_client, "get_embed_limiter", return_value=limiter), \
             patch.object(alerts, "_dispatch", lambda kind, text: calls.append((kind, text))), \
             patch.object(client.embeddings, "create",
                          side_effect=_StubAPIError(401, code="invalid_api_key")):
            with self.assertRaises(_StubAPIError):
                await openai_client.embed(
                    model="text-embedding-3-small", input=["hi"], priority=Priority.BACKGROUND
                )

        self.assertTrue(any("embed(text-embedding-3-small)" in text for _, text in calls))

    async def test_success_path_records_success(self):
        alerts.reset()
        alerts._quota_exhausted_at = "2026-01-01T00:00:00Z"
        client = openai_client.get_openai_client()
        limiter = _FakeLimiter()
        fake_resp = SimpleNamespace(usage=SimpleNamespace(total_tokens=5), choices=[])
        with patch.object(openai_client, "get_chat_limiter", return_value=limiter), \
             patch.object(client.chat.completions, "create", return_value=fake_resp):
            await openai_client.chat_completion(
                priority=Priority.BACKGROUND, model="gpt-5.4-mini", messages=[]
            )

        self.assertIsNone(alerts._quota_exhausted_at)


if __name__ == "__main__":
    unittest.main()
