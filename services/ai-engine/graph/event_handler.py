import asyncio
import logging

from graph import builder
from graph.actor_resolver import resolve_actor
from graph.builder import make_neo4j_actor_store
from graph.embedder import embed_text
from graph.path_filter import should_skip
from graph.slack_filter import should_skip_slack
from graph.summarizer import summarize_diff

logger = logging.getLogger(__name__)


def _is_bot_actor(actor_id: str) -> bool:
    """GitHub 봇 계정 판별. login이 [bot] 접미사로 끝나는 App bot을 거른다.
    예: dependabot[bot], renovate[bot], github-actions[bot]
    """
    return bool(actor_id) and actor_id.endswith("[bot]")


async def handle(event: dict) -> None:
    """NormalizedEvent를 nodeType에 따라 분기 처리한다."""
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

    # GitHub 봇 커밋/PR은 그래프에서 제외 (의사결정 맥락 노이즈)
    if source == "GITHUB" and node_type in ("ChangeSet", "PullRequest") and _is_bot_actor(actor_id):
        logger.debug("봇 이벤트 건너뜀: actor=%s nodeType=%s", actor_id, node_type)
        return

    if node_type == "ChangeSet":
        await _handle_changeset(event)
    elif node_type == "PullRequest":
        await _handle_pull_request(event)
    elif node_type == "Issue":
        await _handle_issue(event)
    elif node_type == "Communication":
        await _handle_communication(event)
    else:
        logger.warning("알 수 없는 nodeType: %s", node_type)


async def _handle_changeset(event: dict) -> None:
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
    )

    # Layer 2: refs 기반 엣지
    if refs.get("jiraKey"):
        await builder.link_changeset_to_issue(project_id, hash_, refs["jiraKey"])
    if refs.get("prNumber"):
        pr_num = int(refs["prNumber"])
        await builder.link_pr_to_changeset(project_id, pr_num, hash_)
        # CONTAINS 직후 PR.jira_keys 전파를 다시 호출 — PR이 이미 jira_keys와 함께 도착했다면
        # 이 새 ChangeSet도 같은 이슈에 text TRIGGERED_BY로 연결된다 (idempotent).
        # PR이 아직 안 도착했으면 PR의 _handle_pull_request에서 처리됨.
        await builder.link_pr_changesets_to_issues(project_id, pr_num)

    # Layer 3: 파일별 diff 요약 + 임베딩 → MODIFIED 엣지 (병렬 처리)
    files = [f for f in (props.get("files") or []) if not should_skip(f.get("path", ""))]

    async def process_file(file: dict) -> None:
        path      = file.get("path", "")
        diff      = file.get("diff", "")
        additions = file.get("additions", 0)
        deletions = file.get("deletions", 0)
        try:
            diff_summary = await asyncio.to_thread(summarize_diff, path, diff, additions, deletions, message)
            embedding    = await embed_text(diff_summary)
            await builder.upsert_file_with_modified_edge(
                project_id=project_id,
                changeset_hash=hash_,
                file_path=path,
                diff_summary=diff_summary,
                embedding=embedding,
            )
            logger.debug("ChangeSet 파일 처리 완료: path=%s", path)
        except Exception:
            logger.exception("ChangeSet 파일 처리 실패 (건너뜀): hash=%s path=%s", hash_, path)

    await asyncio.gather(*[process_file(f) for f in files])


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

    # PR 제목/본문에서 추출된 Jira 키 목록. pipeline-worker의 RefsExtractor가 jiraKeys로 전달.
    # 단일 jiraKey만 있고 jiraKeys가 없는 구버전 이벤트도 호환 (단일 키만이라도 전파에 사용).
    jira_keys = refs.get("jiraKeys")
    if jira_keys is None and refs.get("jiraKey"):
        jira_keys = [refs["jiraKey"]]

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
        jira_keys=jira_keys,
    )

    # Layer 2 전파: PR에 등록된 jira_keys를 그 PR이 머지한 모든 CONTAINS 커밋에 text TRIGGERED_BY로 적용.
    # PR이 commits보다 늦게 도착하는 케이스도 _handle_changeset 쪽에서 다시 호출되어 동일하게 처리됨.
    if pr_number is not None and jira_keys:
        propagated = await builder.link_pr_changesets_to_issues(project_id, int(pr_number))
        if propagated:
            logger.info("PR #%s text TRIGGERED_BY 전파: %d개 갱신", pr_number, propagated)


async def _handle_issue(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    project_id  = event.get("projectId", "")
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")
    title       = props.get("title", "")
    body        = props.get("body", "")

    logger.debug("Issue 수신: jira_key=%s", props.get("jira_key"))

    resolved  = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)
    embedding = await embed_text(f"{title}\n\n{body}")

    await builder.upsert_issue(
        project_id=project_id,
        jira_key=props.get("jira_key", ""),
        title=title,
        body=body,
        status=props.get("status", ""),
        issue_type=props.get("issue_type", ""),
        priority=props.get("priority", ""),
        assignee=props.get("assignee", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        # pipeline-worker는 status가 terminal일 때만 closed_at을 보낸다.
        # None + non-terminal status → builder가 i.closedAt을 null로 클리어 (재오픈 케이스).
        # None + terminal status     → 기존 i.closedAt 보존 (구버전 호환).
        closed_at=props.get("closed_at"),
        source=source,
        actor_uuid=resolved["uuid"],
        embedding=embedding,
    )

    # Layer 2: Jira parent → CHILD_OF
    if refs.get("parentJiraKey"):
        await builder.link_issue_to_parent(project_id, props["jira_key"], refs["parentJiraKey"])

    # Layer 2: Jira assignee → ASSIGNED_TO
    if refs.get("assigneeId"):
        await builder.link_issue_to_assignee(project_id, props["jira_key"], refs["assigneeId"])


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

    # Layer 2: refs.jiraKey → DISCUSSED_IN
    if refs.get("jiraKey"):
        await builder.link_issue_to_communication(project_id, refs["jiraKey"], url)
