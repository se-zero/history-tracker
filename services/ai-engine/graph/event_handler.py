import asyncio
import logging
from dataclasses import dataclass
from typing import Awaitable, Callable

from graph import builder
from graph.actor_resolver import resolve_actor
from graph.builder import make_neo4j_actor_store
from graph.document_chunker import chunk_document
from graph.embed_batcher import embed_text_batched
from graph.embedder import embed_batch, embed_text
from graph.path_filter import should_skip
from graph.slack_filter import should_skip_slack
from graph.summarizer import summarize_diff

logger = logging.getLogger(__name__)


def _is_bot_actor(actor_id: str) -> bool:
    """GitHub 봇 계정 판별. login이 [bot] 접미사로 끝나는 App bot을 거른다.
    예: dependabot[bot], renovate[bot], github-actions[bot]
    """
    return bool(actor_id) and actor_id.endswith("[bot]")


def _encode_prefixed_refs(refs: dict, key: str) -> list[str] | None:
    """refs[key](=[{source, externalId}, ...])를 "SOURCE:externalId" 문자열 목록으로
    인코딩한다(graph.writes._parse_prefixed_refs가 역파싱) — PullRequest.issue_external_ids·
    document_external_ids가 공유하는 인코딩. 키 자체가 없으면 None을 돌려줘 upsert가
    기존 값을 보존하게 한다.
    """
    raw = refs.get(key)
    if raw is None:
        return None
    return [
        f"{ref['source']}:{ref['externalId']}"
        for ref in raw
        if ref.get("source") and ref.get("externalId")
    ]


async def _link_external_refs(
    refs: dict, key: str, link: Callable[[str, str], Awaitable[None]],
) -> None:
    """refs[key](=[{source, externalId}, ...]) 각 원소마다 link(source, externalId)를 건다.
    이슈 키가 없는 소스(Asana 등)의 태스크 URL이나 Notion 문서 URL처럼, source·external_id를
    이미 아는 실키 참조를 실노드/__stub__ 폴백 없이 곧바로 연결하는 호출부가 공유한다.
    """
    for external_ref in refs.get(key) or []:
        ref_source = external_ref.get("source")
        ref_external_id = external_ref.get("externalId")
        if ref_source and ref_external_id:
            await link(ref_source, ref_external_id)


async def handle(event: dict, *, prepared: "ChangesetPrepared | None" = None) -> None:
    """NormalizedEvent를 nodeType에 따라 분기 처리한다.

    prepared: ChangeSet 프리페치(LLM 준비 단계 look-ahead) 결과. ChangeSet 분기에만 전달되고
    다른 분기는 무시한다. None이면 _handle_changeset이 인라인으로 재계산한다
    (routers/admin.py 등 prepared를 모르는 기존 호출부와 호환).
    """
    node_type = event.get("nodeType", "unknown")
    source    = event.get("source", "unknown")
    actor     = event.get("actor") or {}
    actor_id  = actor.get("id", "unknown")

    # projectId 없는 이벤트는 그래프에 쓰지 않는다 — 프로젝트 스코프 없는 노드는
    # 어떤 프로젝트 조회에도 속하지 못하고, 자연키 충돌로 다른 프로젝트와 병합될 수 있다.
    project_id = event.get("projectId") or ""
    if not project_id:
        logger.warning("projectId 없는 이벤트 — 건너뜀 (source=%s nodeType=%s)", source, node_type)
        return

    logger.info("[%s/%s] actor=%s 수신", source, node_type, actor_id)

    # GitHub 봇 커밋/PR/이슈는 그래프에서 제외 (의사결정 맥락 노이즈)
    if source == "GITHUB" and node_type in ("ChangeSet", "PullRequest", "Issue") and _is_bot_actor(actor_id):
        logger.debug("봇 이벤트 건너뜀: actor=%s nodeType=%s", actor_id, node_type)
        return

    if node_type == "ChangeSet":
        await _handle_changeset(event, prepared)
    elif node_type == "PullRequest":
        await _handle_pull_request(event)
    elif node_type == "Issue":
        await _handle_issue(event)
    elif node_type == "Communication":
        await _handle_communication(event)
    elif node_type == "Document":
        await _handle_document(event)
    else:
        logger.warning("알 수 없는 nodeType: %s", node_type)


@dataclass
class ChangesetPrepared:
    """ChangeSet의 LLM 준비 단계(메시지 임베딩 + 파일별 diff 요약·임베딩) 결과.

    prepare_changeset()이 Neo4j 무관하게 미리 계산해두면 _handle_changeset()의 쓰기 단계가
    그대로 소비한다. 준비 단계의 실패는 이미 폴백 값으로 흡수돼 있어 쓰기 단계는
    별도 방어 없이 그대로 사용한다.
    """
    message_embedding: list[float]   # 실패 시 [] (upsert의 CASE가 기존 값 보존)
    file_rows: list[dict]            # [{"file_path","diff_summary","embedding"}] — 요약 성공분만


def is_prefetchable_changeset(event: dict) -> bool:
    """ChangeSet 프리페치(LLM 준비 단계 look-ahead) 대상인지 판별.

    handle()의 가드(projectId 필수, GitHub 봇 제외)를 미러링한다 — 어차피 건너뛸 이벤트를
    미리 준비해 LLM 호출을 낭비하지 않기 위해서다. handle() 자체의 가드는 이중 안전으로 남긴다.
    """
    if event.get("nodeType") != "ChangeSet":
        return False
    if not (event.get("projectId") or ""):
        return False
    actor = event.get("actor") or {}
    if event.get("source") == "GITHUB" and _is_bot_actor(actor.get("id", "unknown")):
        return False
    return True


async def prepare_changeset(event: dict) -> ChangesetPrepared:
    """ChangeSet의 LLM 준비 단계(메시지 임베딩 + 파일별 diff 요약·임베딩)만 실행한다.

    Neo4j 무관한 순수 함수 — 디스패처가 쓰기 순서와 무관하게 look-ahead로 미리 실행할 수 있다.
    어떤 실패도 폴백 값으로 흡수해 절대 raise하지 않는다(쓰기 단계가 실패를 방어할 필요가 없게).
    """
    props   = event.get("properties") or {}
    hash_   = props.get("hash", "")
    message = props.get("message", "")

    # 커밋 메시지 임베딩 — 이슈·Slack과 어휘가 가장 잘 맞는 텍스트라 시맨틱 링커의 비교 대상이 된다.
    # 실패 시 빈 리스트 → upsert가 기존 값을 보존하고, backfill이 나중에 채운다.
    # embed_text_batched: 커밋마다 단건 호출 대신 짧은 대기창 동안 코얼레싱해 1콜로 묶는다.
    message_embedding = await embed_text_batched(message)

    # Layer 3: 파일별 diff 요약 → 배치 임베딩 (#2/#6)
    #   1) 요약: 입력만의 함수라 파일별 동시(gather) — LLM N콜은 불가피(파일별 요약 필수)
    #   2) 임베딩: 요약 N개를 embed_batch로 1콜 (단건 N콜 대비 요청·rate-limit 절감)
    files = [f for f in (props.get("files") or []) if not should_skip(f.get("path", ""))]

    async def summarize_file(file: dict) -> tuple[str, str] | None:
        path = file.get("path", "")
        try:
            summary = await summarize_diff(
                path, file.get("diff", ""), file.get("additions", 0), file.get("deletions", 0), message,
            )
            return (path, summary)
        except Exception:
            logger.exception("ChangeSet 파일 요약 실패 (건너뜀): hash=%s path=%s", hash_, path)
            return None

    summarized = [r for r in await asyncio.gather(*[summarize_file(f) for f in files]) if r is not None]
    if not summarized:
        return ChangesetPrepared(message_embedding=message_embedding, file_rows=[])

    # embed_batch는 원래 실패해도 안 던지지만(빈 벡터로 채워 반환), 방어적으로 감싼다.
    try:
        embeddings = await embed_batch([summary for _, summary in summarized])
    except Exception:
        logger.exception("ChangeSet 파일 배치 임베딩 실패 (빈 임베딩으로 대체): hash=%s", hash_)
        embeddings = [[] for _ in summarized]

    file_rows = [
        {"file_path": path, "diff_summary": summary, "embedding": embedding}
        for (path, summary), embedding in zip(summarized, embeddings)
    ]
    return ChangesetPrepared(message_embedding=message_embedding, file_rows=file_rows)


async def _handle_changeset(event: dict, prepared: ChangesetPrepared | None = None) -> None:
    if prepared is None:
        prepared = await prepare_changeset(event)

    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    project_id  = event.get("projectId", "")
    hash_       = props.get("hash", "")
    message     = props.get("message", "")
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")

    logger.debug("ChangeSet 수신: hash=%s", hash_)

    resolved = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)

    await builder.upsert_changeset(
        project_id=project_id,
        hash=hash_,
        message=message,
        occurred_at=occurred_at,
        source=source,
        actor_uuid=resolved["uuid"],
        embedding=prepared.message_embedding,
    )

    # Layer 2: refs 기반 엣지
    if refs.get("issueKey"):
        await builder.link_changeset_to_issue(project_id, hash_, refs["issueKey"])
    # 이슈 키가 없는 소스(Asana 등)는 태스크 URL에서 추출한 (source, externalId) 참조를 쓴다.
    await _link_external_refs(
        refs, "issueExternalRefs",
        lambda s, e: builder.link_changeset_to_issue_external(project_id, hash_, s, e),
    )
    # 커밋 메시지의 Notion URL — REFERENCE(text). issueExternalRefs와 같은 인코딩(§2-5).
    await _link_external_refs(
        refs, "documentExternalRefs",
        lambda s, e: builder.link_changeset_to_document(project_id, hash_, s, e),
    )
    if refs.get("prNumber"):
        pr_num = int(refs["prNumber"])
        await builder.link_pr_to_changeset(project_id, pr_num, hash_)
        # PR이 이미 issue_keys와 함께 도착했다면 이 커밋 '하나만' 같은 이슈에 text TRIGGERED_BY로 연결.
        # 커밋마다 PR 전체에 재전파하면 O(N²)라, 전체 전파는 PR 도착 시(_handle_pull_request)에만 한다.
        # PR이 아직 안 도착했으면(CONTAINS 없음) noop — PR 도착 시 전체 전파가 처리한다.
        await builder.link_changeset_to_pr_issues(project_id, pr_num, hash_)
        await builder.link_changeset_to_pr_issue_externals(project_id, pr_num, hash_)
        await builder.link_changeset_to_pr_documents(project_id, pr_num, hash_)

    # 파일별 diff 요약·임베딩(prepared.file_rows)이 없으면 여기서 끝 — 노드·Layer 2는 이미 기록됨.
    if not prepared.file_rows:
        return

    # Layer 3 저장: UNWIND로 세션 1번 (단건 N세션·락 경합 제거). 저장 실패는 이벤트 전체를
    # 실패시키지 않는다(노드·Layer 2는 이미 기록됨).
    try:
        await builder.upsert_files_with_modified_edges(
            project_id=project_id, changeset_hash=hash_, files=prepared.file_rows,
        )
        logger.debug("ChangeSet 파일 %d개 배치 처리 완료: hash=%s", len(prepared.file_rows), hash_)
    except Exception:
        logger.exception("ChangeSet 파일 배치 저장 실패 (건너뜀): hash=%s", hash_)


async def _handle_pull_request(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    project_id  = event.get("projectId", "")
    occurred_at = event.get("occurredAt")
    source      = event.get("source", "")
    pr_number   = props.get("pr_number")

    logger.debug("PullRequest 수신: pr_number=%s", pr_number)

    resolved = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)

    # PR 제목/본문에서 추출된 이슈 키 목록. pipeline-worker의 RefsExtractor가 issueKeys로 전달.
    # 단일 issueKey만 있고 issueKeys가 없는 구버전 이벤트도 호환 (단일 키만이라도 전파에 사용).
    issue_keys = refs.get("issueKeys")
    if issue_keys is None and refs.get("issueKey"):
        issue_keys = [refs["issueKey"]]

    # 이슈 키가 없는 소스(Asana 등)는 태스크 URL에서 추출한 (source, externalId) 참조를 인코딩해
    # 저장한다. 키 자체가 없으면(refs에 issueExternalRefs가 없으면) None을 넘겨 기존 값을 보존한다.
    issue_external_ids = _encode_prefixed_refs(refs, "issueExternalRefs")
    # PR 본문/제목의 Notion URL. issue_external_ids와 동일한 인코딩·보존 규칙(§2-5) —
    # PR 본문이 커밋 메시지보다 흔한 Notion 링크 유입로라 이 전파가 REFERENCE(text)의 주 경로다.
    document_external_ids = _encode_prefixed_refs(refs, "documentExternalRefs")

    await builder.upsert_pull_request(
        project_id=project_id,
        pr_number=pr_number,
        title=props.get("title", ""),
        body=props.get("body", ""),
        state=props.get("state", ""),
        base_branch=props.get("base_branch", ""),
        url=props.get("url", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        source=source,
        actor_uuid=resolved["uuid"],
        issue_keys=issue_keys,
        issue_external_ids=issue_external_ids,
        document_external_ids=document_external_ids,
    )

    # Layer 2 전파: PR에 등록된 issue_keys를 그 PR이 머지한 모든 CONTAINS 커밋에 text TRIGGERED_BY로 적용.
    # PR이 commits보다 늦게 도착하는 케이스도 _handle_changeset 쪽에서 다시 호출되어 동일하게 처리됨.
    if pr_number is not None and issue_keys:
        propagated = await builder.link_pr_changesets_to_issues(project_id, int(pr_number))
        if propagated:
            logger.info("PR #%s text TRIGGERED_BY 전파: %d개 갱신", pr_number, propagated)
    if pr_number is not None and issue_external_ids:
        propagated_external = await builder.link_pr_changesets_to_issue_externals(project_id, int(pr_number))
        if propagated_external:
            logger.info("PR #%s issueExternalRefs TRIGGERED_BY 전파: %d개 갱신", pr_number, propagated_external)
    if pr_number is not None and document_external_ids:
        propagated_documents = await builder.link_pr_changesets_to_documents(project_id, int(pr_number))
        if propagated_documents:
            logger.info("PR #%s documentExternalRefs REFERENCE 전파: %d개 갱신", pr_number, propagated_documents)


async def _handle_issue(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    project_id  = event.get("projectId", "")
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")
    title       = props.get("title", "")
    body        = props.get("body", "")

    # external_id(Jira issue id)는 불변 MERGE 키라 필수 — 없으면 이 이벤트로는 어떤 노드도
    # 특정할 수 없다. projectId 부재와 동일한 폐기 정책(구 형식 브로커 잔여 이벤트 자연 폐기).
    external_id = props.get("external_id")
    if not external_id:
        logger.warning("Issue external_id 없음 — 건너뜀 (source=%s)", source)
        return
    issue_key = props.get("issue_key")

    logger.debug("Issue 수신: external_id=%s issue_key=%s", external_id, issue_key)

    resolved  = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)
    embedding = await embed_text(f"{title}\n\n{body}")

    await builder.upsert_issue(
        project_id=project_id,
        source=source,
        external_id=external_id,
        issue_key=issue_key,
        title=title,
        body=body,
        status=props.get("status"),
        status_category=props.get("status_category", ""),
        issue_type=props.get("issue_type", ""),
        priority=props.get("priority", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        # pipeline-worker는 status_category가 closed일 때만 closed_at을 보낸다.
        # None + non-closed category → builder가 i.closedAt을 null로 클리어 (재오픈 케이스).
        # None + closed category     → 기존 i.closedAt 보존 (구버전 호환).
        closed_at=props.get("closed_at"),
        actor_uuid=resolved["uuid"],
        embedding=embedding,
    )

    # 텍스트 링크가 먼저 도착해 만든 __stub__ Issue가 있으면 이 실노드로 흡수한다.
    # stub은 issue_key(사람용 키)로만 찾을 수 있으므로 issue_key가 없으면 흡수할 stub도 없다.
    if issue_key:
        await builder.absorb_issue_stub(project_id, source, external_id, issue_key)

    # Layer 2: Jira parent → CHILD_OF
    parent_external_id = refs.get("parentExternalId")
    if parent_external_id:
        await builder.link_issue_to_parent(
            project_id, source, external_id, parent_external_id, refs.get("parentIssueKey"),
        )

    # Layer 2: Jira assignees → ASSIGNED_TO
    # 담당자도 작성자와 동일하게 resolve_actor를 거쳐 Actor로 승격한다 (이름 문자열을
    # Issue 속성에 저장하지 않기 위함). 순차 처리는 작성자 resolve와 같은 이유(Actor MERGE
    # 경합 회피) — consumer가 project 단위로 직렬 처리되므로 순차라도 병목이 되지 않는다.
    # 이슈 이벤트는 최신 스냅샷이므로 refs.assignees가 없거나 비어 있으면 담당자 전원 해제다.
    assignee_uuids = []
    for assignee in refs.get("assignees") or []:
        assigned = await resolve_actor(
            # bot을 함께 넘긴다 — Linear 봇의 주 유입로가 담당자(AI 에이전트 할당)라,
            # 여기서 떨어뜨리면 봇 담당자가 사람 동일인 매칭을 타 격리가 뚫린다.
            {"id": assignee.get("id"), "name": assignee.get("name"), "email": assignee.get("email"),
             "bot": assignee.get("bot")},
            source, make_neo4j_actor_store(project_id), event,
        )
        assignee_uuids.append(assigned["uuid"])
    await builder.set_issue_assignees(project_id, source, external_id, assignee_uuids)


async def _handle_document(event: dict) -> None:
    """Document 이벤트를 Document + DocumentSection 그래프로 소비한다."""
    props = event.get("properties") or {}
    refs = event.get("refs") or {}
    actor = event.get("actor") or {}
    project_id = event.get("projectId", "")
    source = event.get("source", "")
    external_id = props.get("external_id")

    # Document도 Issue와 같이 provider의 불변 ID 없이는 재수집 멱등성을 보장할 수 없다.
    if not external_id:
        logger.warning("Document external_id 없음 — 건너뜀 (source=%s)", source)
        return

    title = props.get("title", "")
    body = props.get("body", "")
    occurred_at = event.get("occurredAt", "")
    logger.debug("Document 수신: external_id=%s", external_id)

    resolved = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)
    await builder.upsert_document(
        project_id=project_id,
        source=source,
        external_id=external_id,
        title=title,
        body=body,
        # NotionNormalizer가 parent.type/url이 없거나 비정상이면 JSON null로 채워 보낼 수 있다 —
        # 키는 존재하고 값만 None이라 dict.get(key, default)의 default가 적용되지 않는다.
        url=props.get("url") or "",
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        parent_type=props.get("parent_type") or "",
        parent_external_id=props.get("parent_external_id"),
        actor_uuid=resolved["uuid"],
    )

    sections = chunk_document(title, body)
    # heading_path는 heading 하나만 지나도 문서 제목을 잃는다(예: "배경") — 그 표시 라벨은
    # 그대로 두고, 임베딩 입력에만 문서 제목을 매 섹션 앞에 별도로 보탠다. 그러지 않으면
    # "배경"·"개요" 같은 흔한 소제목이 문서 정체성 없이 벡터 공간에서 서로 뭉친다.
    # document_title 계산은 chunk_document 내부 규칙(공백 제목 → "제목 없는 문서")과 맞춘다.
    document_title = title.strip() or "제목 없는 문서"
    embeddings = await embed_batch([
        f"{document_title}\n\n{section.text}" if section.heading_path == document_title
        else f"{document_title}\n{section.heading_path}\n\n{section.text}"
        for section in sections
    ]) if sections else []
    await builder.replace_document_sections(
        project_id=project_id,
        source=source,
        document_external_id=external_id,
        sections=[
            {
                "ordinal": ordinal,
                "heading_path": section.heading_path,
                "text": section.text,
                "embedding": embedding,
            }
            for ordinal, (section, embedding) in enumerate(zip(sections, embeddings))
        ],
    )

    # editors는 마지막 편집자 스냅샷이지만 Document의 EDITED는 누적 관계다.
    editor_uuids = []
    for editor in refs.get("editors") or []:
        resolved_editor = await resolve_actor(
            {
                "id": editor.get("id"),
                "name": editor.get("name"),
                "email": editor.get("email"),
                "bot": editor.get("bot"),
            },
            source,
            make_neo4j_actor_store(project_id),
            event,
        )
        editor_uuids.append(resolved_editor["uuid"])
    await builder.set_document_editors(project_id, source, external_id, editor_uuids)

    parent_external_id = props.get("parent_external_id")
    if parent_external_id:
        await builder.link_document_to_parent(project_id, source, external_id, parent_external_id)

    # 문서에는 여러 이슈가 명시될 수 있다. 구 형식의 단수 issueKey도 읽어 마이그레이션 중
    # 이벤트를 잃지 않는다.
    issue_keys = refs.get("issueKeys") or ([] if not refs.get("issueKey") else [refs["issueKey"]])
    for issue_key in dict.fromkeys(key for key in issue_keys if key):
        await builder.link_issue_to_document(project_id, issue_key, source, external_id)
    await _link_external_refs(
        refs, "issueExternalRefs",
        lambda s, e: builder.link_issue_external_to_document(project_id, s, e, source, external_id),
    )


async def _handle_communication(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    project_id  = event.get("projectId", "")
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")
    body        = props.get("body", "")
    url         = props.get("url", "")

    logger.debug("Communication 수신: channel=%s", props.get("channel"))

    if not url:
        logger.warning("Communication url 없음 — 건너뜀 (channel=%s)", props.get("channel"))
        return

    if should_skip_slack(body):
        logger.debug("Communication 룰 필터 제거: url=%s", url)
        return

    resolved  = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)
    embedding = await embed_text(body)

    await builder.upsert_communication(
        project_id=project_id,
        url=url,
        body=body,
        channel=props.get("channel", ""),
        conversation_id=props.get("conversation_id", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        source=source,
        actor_uuid=resolved["uuid"],
        embedding=embedding,
        llm_filtered=False,
    )

    # Layer 2: refs.issueKey → DISCUSSED_IN
    if refs.get("issueKey"):
        await builder.link_issue_to_communication(project_id, refs["issueKey"], url)
    # 이슈 키가 없는 소스(Asana 등)는 태스크 URL에서 추출한 (source, externalId) 참조를 쓴다.
    await _link_external_refs(
        refs, "issueExternalRefs",
        lambda s, e: builder.link_issue_external_to_communication(project_id, s, e, url),
    )
    # 대화 본문의 Notion URL — DISCUSSED_IN(text). issueExternalRefs와 같은 인코딩(§2-5).
    await _link_external_refs(
        refs, "documentExternalRefs",
        lambda s, e: builder.link_document_to_communication(project_id, s, e, url),
    )
