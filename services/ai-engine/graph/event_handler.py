import logging

logger = logging.getLogger(__name__)


async def handle(event: dict) -> None:
    """NormalizedEvent를 nodeType에 따라 분기 처리한다."""
    node_type = event.get("nodeType", "unknown")
    source    = event.get("source", "unknown")
    actor_id  = (event.get("actor") or {}).get("id")

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
    props = event.get("properties") or {}
    logger.debug("ChangeSet 수신: hash=%s", props.get("hash"))


async def _handle_pull_request(event: dict) -> None:
    props = event.get("properties") or {}
    logger.debug("PullRequest 수신: pr_number=%s", props.get("pr_number"))


async def _handle_issue(event: dict) -> None:
    props = event.get("properties") or {}
    logger.debug("Issue 수신: jira_key=%s", props.get("jira_key"))


async def _handle_communication(event: dict) -> None:
    props = event.get("properties") or {}
    logger.debug("Communication 수신: channel=%s", props.get("channel"))
