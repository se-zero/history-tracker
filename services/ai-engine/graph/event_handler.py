import asyncio
import logging

from graph import builder
from graph.embedder import embed_text
from graph.path_filter import should_skip
from graph.summarizer import summarize_diff

logger = logging.getLogger(__name__)


async def handle(event: dict) -> None:
    """NormalizedEvent를 nodeType에 따라 분기 처리한다."""
    node_type = event.get("nodeType", "unknown")
    source    = event.get("source", "unknown")
    actor     = event.get("actor") or {}
    actor_id  = actor.get("id", "unknown")

    logger.info("[%s/%s] actor=%s 수신", source, node_type, actor_id)

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
    hash_       = props.get("hash", "")
    message     = props.get("message", "")
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")

    logger.debug("ChangeSet 수신: hash=%s", hash_)

    await builder.upsert_changeset(
        hash=hash_,
        message=message,
        occurred_at=occurred_at,
        source=source,
        actor_id=actor.get("id", "unknown"),
        actor_name=actor.get("name", ""),
        actor_email=actor.get("email"),
    )

    # Layer 2: refs 기반 엣지
    if refs.get("jiraKey"):
        await builder.link_changeset_to_issue(hash_, refs["jiraKey"])
    if refs.get("prNumber"):
        await builder.link_pr_to_changeset(refs["prNumber"], hash_)

    # Layer 3: 파일별 diff 요약 + 임베딩 → MODIFIED 엣지
    for file in props.get("files") or []:
        path      = file.get("path", "")
        diff      = file.get("diff", "")
        additions = file.get("additions", 0)
        deletions = file.get("deletions", 0)

        if should_skip(path):
            continue

        diff_summary = await asyncio.to_thread(summarize_diff, path, diff, additions, deletions, message)
        embedding    = await embed_text(diff_summary)

        await builder.upsert_file_with_modified_edge(
            changeset_hash=hash_,
            file_path=path,
            diff_summary=diff_summary,
            embedding=embedding,
        )

        logger.debug("ChangeSet 파일 처리 완료: path=%s", path)


async def _handle_pull_request(event: dict) -> None:
    props       = event.get("properties") or {}
    actor       = event.get("actor") or {}
    occurred_at = event.get("occurredAt")
    source      = event.get("source", "")

    logger.debug("PullRequest 수신: pr_number=%s", props.get("pr_number"))

    await builder.upsert_pull_request(
        pr_number=props.get("pr_number"),
        title=props.get("title", ""),
        body=props.get("body", ""),
        state=props.get("state", ""),
        base_branch=props.get("base_branch", ""),
        url=props.get("url", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        source=source,
        actor_id=actor.get("id", "unknown"),
        actor_name=actor.get("name", ""),
        actor_email=actor.get("email"),
    )


async def _handle_issue(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")
    title       = props.get("title", "")
    body        = props.get("body", "")

    logger.debug("Issue 수신: jira_key=%s", props.get("jira_key"))

    embedding = await embed_text(f"{title}\n\n{body}")

    await builder.upsert_issue(
        jira_key=props.get("jira_key", ""),
        title=title,
        body=body,
        status=props.get("status", ""),
        issue_type=props.get("issue_type", ""),
        priority=props.get("priority", ""),
        assignee=props.get("assignee", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        source=source,
        actor_id=actor.get("id", "unknown"),
        actor_name=actor.get("name", ""),
        actor_email=actor.get("email"),
        embedding=embedding,
    )

    # Layer 2: Jira parent → CHILD_OF
    if refs.get("parentKey"):
        await builder.link_issue_to_parent(props["jira_key"], refs["parentKey"])


async def _handle_communication(event: dict) -> None:
    props       = event.get("properties") or {}
    refs        = event.get("refs") or {}
    actor       = event.get("actor") or {}
    occurred_at = event.get("occurredAt", "")
    source      = event.get("source", "")
    body        = props.get("body", "")
    url         = props.get("url", "")

    logger.debug("Communication 수신: channel=%s", props.get("channel"))

    if not url:
        logger.warning("Communication url 없음 — 건너뜀 (channel=%s)", props.get("channel"))
        return

    embedding = await embed_text(body)

    await builder.upsert_communication(
        url=url,
        body=body,
        channel=props.get("channel", ""),
        conversation_id=props.get("conversation_id", ""),
        occurred_at=occurred_at,
        created_at=props.get("created_at"),
        source=source,
        actor_id=actor.get("id", "unknown"),
        actor_name=actor.get("name", ""),
        actor_email=actor.get("email"),
        embedding=embedding,
    )

    # Layer 2: refs.jiraKey → DISCUSSED_IN
    if refs.get("jiraKey"):
        await builder.link_issue_to_communication(refs["jiraKey"], url)
