"""
자동구축 — 임베딩 유사도 기반 Document 연결 (Layer 4 확장, 문서 아키타입).

  MODIFIED.embedding ↔ DocumentSection.embedding  → REFERENCE (ChangeSet → Document)
  Issue.embedding    ↔ DocumentSection.embedding  → DESCRIBED_IN (Issue → Document)

매칭은 섹션 단위지만 엣지는 문서에 건다 — 통짜 임베딩이 다주제 문서에서 의미가 평균화되는
문제를 피하려고 섹션으로 쪼갰을 뿐, 그래프 질의 단위는 여전히 문서다(docs/notion-integration.md §2-3).

시간 윈도우가 다른 Layer 4 빌더와 다르다 — 문서는 오래 산다. 하한만 두고 상한을 두지 않는다:
  [document.createdAt - DOCUMENT_PRE_BUFFER_DAYS, ∞)
문서가 쓰이기 전의 커밋/이슈를 그 문서가 설명한다고 보기 어려워 하한만 둔다. 상한을 두지 않는
대신 문서당 top-k 컷으로 후보 폭증을 막는다 — 반대편(커밋/이슈)은 열어 둔다. 문서 하나가 여러
변경의 근거인 게 정상이고, 커밋 하나가 여러 문서를 근거로 삼는 것도 정상이라 양쪽 다 닫으면
곱으로 터진다(§2-6).

text 우선(§2-7): source='text'인 쌍은 write 시점에 개별적으로 배제한다(reference_store의
_create_reference_edge와 동일한 `WHERE NOT (a)-[:REL {source:'text'}]->(b)` 가드) — TRIGGERED_BY처럼
후보 자체를 통째로 빼지 않는다. ChangeSet 하나가 문서 A엔 text로, 문서 B엔 semantic으로 동시에
연결될 수 있어(Issue도 동일) 노드 단위 배제가 아니라 쌍 단위 배제가 맞다.

실행 타이밍: 이벤트 처리와 별개로 수동 또는 스케줄 호출. Document/DocumentSection·ChangeSet·Issue
임베딩이 충분히 쌓인 뒤 실행.

LLM 검수(verify=true) 빌더는 Phase 1에 없다 — 자동구축(임베딩만) 경로만 있다. false positive
비율을 eval로 본 뒤 필터형 검수 도입 여부를 판단한다(§6-2).
"""

import logging
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Awaitable, Callable

import numpy as np

from graph.embedder import similarity_matrix

logger = logging.getLogger(__name__)


def _group_by_project(rows: list[dict]) -> dict[str, list[dict]]:
    """project_id 기준 그룹핑 — 프로젝트 간 임베딩 비교(크로스 테넌트 엣지)를 차단한다."""
    grouped: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        grouped[row.get("project_id") or ""].append(row)
    return grouped


# 문서가 쓰이기 전 커밋/이슈를 근거로 보지 않기 위한 하한 버퍼(회고성 문서를 위한 여유).
DOCUMENT_PRE_BUFFER_DAYS = 7
# 문서당 유지할 최대 매칭 수(fan-out 컷) — 반대편(ChangeSet/Issue)은 열어 둔다.
DOCUMENT_TOP_K = 5
# REFERENCE(ChangeSet↔Document)는 REFERENCE(ChangeSet↔Communication, 0.44)의 초기값을 물려받는다 —
# 둘 다 "diff 요약 대 텍스트" 비교라서다. 섹션 단위 비교가 통짜보다 점수가 높게 나오는 경향이 있어
# eval 재스윕 전까지는 잠정값이다(docs/notion-integration.md §2-6 — 값을 그대로 굳히지 않는다).
DOCUMENT_REFERENCE_THRESHOLD = 0.44
# DESCRIBED_IN(Issue↔Document)은 TRIGGERED_BY(0.34, 이슈-코드diff)가 아니라
# DISCUSSED_IN(0.48, 이슈-텍스트)의 초기값을 물려받는다 — 이슈-문서 비교도 텍스트 대 텍스트다.
DESCRIBED_IN_THRESHOLD = 0.48


def _document_window_start(document: dict, pre_days: int) -> datetime:
    return document["created_at"] - timedelta(days=pre_days)


@dataclass
class DocumentLinkStore:
    """Neo4j 조회·생성 함수 묶음. 테스트 시 mock으로 교체 가능."""

    fetch_documents: Callable[[], Awaitable[list[dict]]]
    """Document 메타(시간 윈도우 계산용) 반환.
    Returns:
        [{"project_id": str, "id": str (복합 식별자 "{SOURCE}:{external_id}", Issue와 동일 관례),
          "created_at": datetime}, ...]
    """

    fetch_document_sections: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 DocumentSection 반환.
    Returns:
        [{"project_id": str, "document_id": str (소속 Document의 복합 식별자),
          "heading_path": str, "embedding": list[float]}, ...]
    """

    fetch_modified_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 MODIFIED 엣지 반환(reference_store와 동일 형태, 필터 없음 — text
    REFERENCE 배제는 이 목록이 아니라 write 시점에 쌍 단위로 한다. create_reference_edge 참고).
    Returns:
        [{"project_id": str, "changeset_id": str, "embedding": list[float], "occurred_at": datetime}, ...]
    """

    fetch_issue_embeddings: Callable[[], Awaitable[list[dict]]]
    """embedding이 있는 모든 Issue 노드 반환(issue_link_store와 동일 형태, 필터 없음).
    Returns:
        [{"project_id": str, "id": str (복합 식별자 "{SOURCE}:{external_id}"),
          "embedding": list[float], "occurred_at": datetime}, ...]
    """

    create_reference_edge: Callable[[str, str, str, float, str], Awaitable[None]]
    """REFERENCE (ChangeSet→Document, semantic) 엣지 생성/갱신.
    Args:
        project_id, changeset_id, document_id(복합 식별자), confidence, section(최고점 heading_path)
    """

    create_described_in_edge: Callable[[str, str, str, float, str], Awaitable[None]]
    """DESCRIBED_IN (Issue→Document, semantic) 엣지 생성/갱신.
    Args:
        project_id, issue_id(복합 식별자), document_id(복합 식별자), confidence, section
    """


def _select_document_pairs(
    documents: list[dict],
    sections: list[dict],
    source_rows: list[dict],
    source_id_key: str,
    threshold: float,
    top_k: int,
    pre_days: int,
) -> list[tuple[str, str, str, float, str]]:
    """REFERENCE·DESCRIBED_IN 공용 코어 — 섹션 단위로 비교하고 문서 단위로 집계·컷한다.

    엣지 생성과 분리된 순수 함수다(분리 이유는 reference_builder.select_reference_pairs 참고).
    ChangeSet용(source_id_key="changeset_id")과 Issue용(source_id_key="id") 둘 다 이 함수로
    계산한다 — 두 호출의 차이는 threshold·후보 행뿐이라 별도 함수로 중복시키지 않는다.

    Returns:
        [(project_id, source_id, document_id, score, section_heading_path), ...]
    """
    docs_by_project = _group_by_project(documents)
    sections_by_project = _group_by_project(sections)
    sources_by_project = _group_by_project(source_rows)

    selected: list[tuple[str, str, str, float, str]] = []
    for project_id, project_docs in docs_by_project.items():
        window_start = {d["id"]: _document_window_start(d, pre_days) for d in project_docs}

        # 빈 임베딩은 제외 — 유사도 0이라 어차피 임계값 미달이고, 행렬화도 깨진다.
        # 소속 Document를 못 찾은 섹션(윈도우 계산 불가)도 제외한다.
        p_sections = [
            s for s in sections_by_project.get(project_id, [])
            if s.get("embedding") and s["document_id"] in window_start
        ]
        p_sources = [s for s in sources_by_project.get(project_id, []) if s.get("embedding")]
        if not p_sections or not p_sources:
            continue

        # 전체 쌍 코사인 유사도를 numpy로 일괄 계산 (source × section)
        sim = similarity_matrix(
            [s["embedding"] for s in p_sources],
            [sec["embedding"] for sec in p_sections],
        )

        # 문서 하한 윈도우 — 섹션이 속한 문서의 [createdAt - pre_days, ∞) 안의 source만 허용.
        source_ts = np.array([row["occurred_at"].timestamp() for row in p_sources])
        section_start_ts = np.array([window_start[sec["document_id"]].timestamp() for sec in p_sections])
        in_window = source_ts[:, None] >= section_start_ts[None, :]
        valid = in_window & (sim >= threshold)

        # (source_id, document_id) 쌍의 대표 점수는 그 문서에 속한 섹션 중 최고점 — 매칭은
        # 섹션 단위지만 엣지는 문서에 건다. 최고점 섹션의 heading_path를 근거 위치로 남긴다.
        best_per_pair: dict[tuple[str, str], tuple[float, str]] = {}
        for i, j in np.argwhere(valid):
            i, j = int(i), int(j)
            pair = (p_sources[i][source_id_key], p_sections[j]["document_id"])
            score = float(sim[i, j])
            current = best_per_pair.get(pair)
            if current is None or score > current[0]:
                best_per_pair[pair] = (score, p_sections[j]["heading_path"])

        # 문서당 top-k 컷 — 반대편(source)은 열어 둔다(§2-6).
        by_document: dict[str, list[tuple[str, float, str]]] = defaultdict(list)
        for (source_id, document_id), (score, heading_path) in best_per_pair.items():
            by_document[document_id].append((source_id, score, heading_path))

        for document_id, candidates in by_document.items():
            top = sorted(candidates, key=lambda c: -c[1])[:top_k]
            for source_id, score, heading_path in top:
                selected.append((project_id, source_id, document_id, score, heading_path))

    return selected


def select_document_reference_pairs(
    documents: list[dict],
    sections: list[dict],
    modified_rows: list[dict],
    threshold: float = DOCUMENT_REFERENCE_THRESHOLD,
    top_k: int = DOCUMENT_TOP_K,
    pre_days: int = DOCUMENT_PRE_BUFFER_DAYS,
) -> list[tuple[str, str, str, float, str]]:
    """REFERENCE(ChangeSet→Document) 후보 선별.

    Returns:
        [(project_id, changeset_id, document_id, score, section_heading_path), ...]
    """
    return _select_document_pairs(documents, sections, modified_rows, "changeset_id", threshold, top_k, pre_days)


def select_described_in_pairs(
    documents: list[dict],
    sections: list[dict],
    issue_rows: list[dict],
    threshold: float = DESCRIBED_IN_THRESHOLD,
    top_k: int = DOCUMENT_TOP_K,
    pre_days: int = DOCUMENT_PRE_BUFFER_DAYS,
) -> list[tuple[str, str, str, float, str]]:
    """DESCRIBED_IN(Issue→Document) 후보 선별.

    Returns:
        [(project_id, issue_id, document_id, score, section_heading_path), ...]
    """
    return _select_document_pairs(documents, sections, issue_rows, "id", threshold, top_k, pre_days)


async def build_document_reference_edges(
    store: DocumentLinkStore,
    threshold: float = DOCUMENT_REFERENCE_THRESHOLD,
    top_k: int = DOCUMENT_TOP_K,
    pre_days: int = DOCUMENT_PRE_BUFFER_DAYS,
) -> int:
    """MODIFIED.embedding ↔ DocumentSection.embedding 유사도로 REFERENCE(ChangeSet→Document) 엣지 생성.

    Returns:
        생성(또는 갱신)된 REFERENCE 엣지 수
    """
    documents = await store.fetch_documents()
    sections = await store.fetch_document_sections()
    modified = await store.fetch_modified_embeddings()

    if not documents or not sections or not modified:
        logger.info(
            "REFERENCE(Document) 생성 스킵: documents=%d, sections=%d, modified=%d",
            len(documents), len(sections), len(modified),
        )
        return 0

    created = 0
    for project_id, changeset_id, document_id, score, section in select_document_reference_pairs(
        documents, sections, modified, threshold, top_k, pre_days
    ):
        await store.create_reference_edge(project_id, changeset_id, document_id, score, section)
        created += 1
        logger.debug(
            "REFERENCE(Document) 생성: changeset=%s document=%s section=%s score=%.3f",
            changeset_id, document_id, section, score,
        )

    logger.info("REFERENCE(Document) 엣지 생성 완료: %d개 (threshold=%.2f, top_k=%d)",
                created, threshold, top_k)
    return created


async def build_described_in_document_edges(
    store: DocumentLinkStore,
    threshold: float = DESCRIBED_IN_THRESHOLD,
    top_k: int = DOCUMENT_TOP_K,
    pre_days: int = DOCUMENT_PRE_BUFFER_DAYS,
) -> int:
    """Issue.embedding ↔ DocumentSection.embedding 유사도로 DESCRIBED_IN(Issue→Document) 엣지 생성.

    Returns:
        생성(또는 갱신)된 DESCRIBED_IN 엣지 수
    """
    documents = await store.fetch_documents()
    sections = await store.fetch_document_sections()
    issues = await store.fetch_issue_embeddings()

    if not documents or not sections or not issues:
        logger.info(
            "DESCRIBED_IN(Document) 생성 스킵: documents=%d, sections=%d, issues=%d",
            len(documents), len(sections), len(issues),
        )
        return 0

    created = 0
    for project_id, issue_id, document_id, score, section in select_described_in_pairs(
        documents, sections, issues, threshold, top_k, pre_days
    ):
        await store.create_described_in_edge(project_id, issue_id, document_id, score, section)
        created += 1
        logger.debug(
            "DESCRIBED_IN(Document) 생성: issue=%s document=%s section=%s score=%.3f",
            issue_id, document_id, section, score,
        )

    logger.info("DESCRIBED_IN(Document) 엣지 생성 완료: %d개 (threshold=%.2f, top_k=%d)",
                created, threshold, top_k)
    return created
