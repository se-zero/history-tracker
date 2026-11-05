"""수집 백로그 스냅샷 — 디바운스(postprocess)가 "아직 처리할 게 남았나"를 판단하는 데 쓴다.

postprocess→consumer 순환을 피하려고 여기 둔다(consumer가 postprocess.mark_dirty를 import한다).
그래서 graph 패키지의 어떤 모듈도 import하지 않는다.
"""

from typing import Callable

# project_id → 그 프로젝트 큐의 messages(발견 틱이 관리 API로 갱신)
_snapshot: dict[str, int] = {}
# project_id → 디스패처의 대기+처리 중 수(consumer가 연결 수명 동안 등록)
_pending_provider: Callable[[str], int] | None = None


def update_snapshot(by_project: dict[str, int]) -> None:
    """통째로 교체한다 — 사라진 큐의 키가 남아 영원히 유휴가 안 되는 일을 막는다."""
    global _snapshot
    _snapshot = dict(by_project)


def set_pending_provider(fn: Callable[[str], int] | None) -> None:
    global _pending_provider
    _pending_provider = fn


def queue_backlog(project_id: str) -> int:
    return _snapshot.get(project_id, 0)


def dispatcher_pending(project_id: str) -> int:
    return _pending_provider(project_id) if _pending_provider is not None else 0


def is_drained(project_id: str) -> bool:
    return queue_backlog(project_id) == 0 and dispatcher_pending(project_id) == 0


def reset() -> None:
    """테스트용 — 모듈 전역을 초기화한다."""
    global _snapshot, _pending_provider
    _snapshot = {}
    _pending_provider = None
