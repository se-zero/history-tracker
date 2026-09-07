"""main.health() 단위 테스트 — /health가 alerts 카운터를 노출하는지 확인한다."""

import alerts
import main


def test_health_status_ok_with_alerts_snapshot():
    alerts.reset()

    result = main.health()

    assert result["status"] == "ok"
    assert set(result["alerts"]["counters"].keys()) == set(alerts.KINDS)


def test_health_reflects_parking_counter():
    alerts.reset()
    alerts.record_parking("event.test")

    result = main.health()

    assert result["alerts"]["counters"]["parking"] == 1
