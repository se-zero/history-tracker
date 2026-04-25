import asyncio
import logging

from graph.embedder import embed_text
from graph.path_filter import should_skip
from graph.summarizer import summarize_diff

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
    props   = event.get("properties") or {}
    files   = props.get("files") or []
    message = props.get("message", "")
    logger.debug("ChangeSet 수신: hash=%s", props.get("hash"))

    # TODO: Neo4j - Actor resolve 후 ChangeSet 노드 MERGE, AUTHORED 엣지 생성

    for file in files:
        path      = file.get("path", "")
        diff      = file.get("diff", "")
        additions = file.get("additions", 0)
        deletions = file.get("deletions", 0)

        if should_skip(path):
            continue

        diff_summary = await asyncio.to_thread(summarize_diff, path, diff, additions, deletions, message)
        embedding    = await embed_text(diff_summary)

        logger.debug("ChangeSet 파일 처리: path=%s summary_len=%d", path, len(diff_summary))

        # TODO: Neo4j - File 노드 upsert, MODIFIED 엣지 생성 { diffSummary, embedding }


async def _handle_pull_request(event: dict) -> None:
    props = event.get("properties") or {}
    logger.debug("PullRequest 수신: pr_number=%s", props.get("pr_number"))

    # TODO: Neo4j - Actor resolve 후 PullRequest 노드 MERGE, AUTHORED 엣지 생성
    # TODO: Neo4j - refs.prNumber 있으면 CONTAINS 엣지 생성


async def _handle_issue(event: dict) -> None:
    props = event.get("properties") or {}
    title = props.get("title", "")
    body  = props.get("body", "")
    logger.debug("Issue 수신: jira_key=%s", props.get("jira_key"))

    # title + body 를 합쳐 임베딩 — 나중에 DISCUSSED_IN / TRIGGERED_BY 시맨틱 연결에 사용
    embedding = await embed_text(f"{title}\n\n{body}")

    logger.debug("Issue 임베딩 완료: jira_key=%s", props.get("jira_key"))

    # TODO: Neo4j - Actor resolve 후 Issue 노드 MERGE { embedding }, CREATED 엣지 생성
    # TODO: Neo4j - refs.jiraKey 있으면 DISCUSSED_IN, TRIGGERED_BY 엣지 생성


async def _handle_communication(event: dict) -> None:
    props = event.get("properties") or {}
    body  = props.get("body", "")
    logger.debug("Communication 수신: channel=%s", props.get("channel"))

    embedding = await embed_text(body)

    logger.debug("Communication 임베딩 완료: channel=%s", props.get("channel"))

    # TODO: Neo4j - Actor resolve 후 Communication 노드 MERGE { embedding }, WROTE 엣지 생성
    # TODO: Neo4j - refs.jiraKey 있으면 DISCUSSED_IN 엣지 생성
