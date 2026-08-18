"""Layer 4 시맨틱 링크 빌더(reference_builder·issue_linker·document_linker) 공용 헬퍼.

세 모듈 모두 "노드 쌍을 project_id로 나눠 프로젝트 내부에서만 비교"하는 동일한 전처리를
거친다 — 그 부분만 여기 모아 셋이 공유한다.
"""


def group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    """project_id 기준 그룹핑 — 프로젝트 간 임베딩 비교(크로스 테넌트 엣지)를 차단한다."""
    grouped: dict[str, list[dict]] = {}
    for row in rows:
        grouped.setdefault(row.get("project_id") or "", []).append(row)
    return grouped
