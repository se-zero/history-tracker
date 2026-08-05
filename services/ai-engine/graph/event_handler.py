import asyncio
import logging

from graph import builder
from graph.actor_resolver import resolve_actor
from graph.builder import make_neo4j_actor_store
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


# 키 중립화(jira_key → issue_key) 이전 pipeline-worker가 발행한 이벤트의 키 이름.
# 브로커에 남아 있던 이벤트·retry 큐·DLQ replay가 옛 키로 도착할 수 있어 진입점에서
# 새 이름으로 정규화한다 — 핸들러들은 새 키만 안다. 옛 키가 더는 관측되지 않으면 지워도 된다.
_LEGACY_REF_KEYS = {"jiraKey": "issueKey", "jiraKeys": "issueKeys", "parentJiraKey": "parentIssueKey"}
_LEGACY_PROP_KEYS = {"jira_key": "issue_key"}


def _normalize_legacy_keys(event: dict) -> None:
    """옛 키를 새 키로 옮긴다(새 키가 이미 있으면 새 키 우선). event를 제자리 수정한다."""
    for mapping, field in ((_LEGACY_REF_KEYS, "refs"), (_LEGACY_PROP_KEYS, "properties")):
        section = event.get(field)
        if not isinstance(section, dict):
            continue
        for old_key, new_key in mapping.items():
            if old_key in section:
                section.setdefault(new_key, section[old_key])
                del section[old_key]


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

    # 옛 키(jira_key·jiraKey 계열)로 도착한 이벤트를 새 이름으로 정규화
    _normalize_legacy_keys(event)

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

    # 커밋 메시지 임베딩 — 이슈·Slack과 어휘가 가장 잘 맞는 텍스트라 시맨틱 링커의 비교 대상이 된다.
    # 실패 시 빈 리스트 → upsert가 기존 값을 보존하고, backfill이 나중에 채운다.
    message_embedding = await embed_text(message)

    await builder.upsert_changeset(
        project_id=project_id,
        hash=hash_,
        message=message,
        occurred_at=occurred_at,
        source=source,
        actor_uuid=resolved["uuid"],
        embedding=message_embedding,
    )

    # Layer 2: refs 기반 엣지
    if refs.get("issueKey"):
        await builder.link_changeset_to_issue(project_id, hash_, refs["issueKey"])
    if refs.get("prNumber"):
        pr_num = int(refs["prNumber"])
        await builder.link_pr_to_changeset(project_id, pr_num, hash_)
        # PR이 이미 issue_keys와 함께 도착했다면 이 커밋 '하나만' 같은 이슈에 text TRIGGERED_BY로 연결.
        # 커밋마다 PR 전체에 재전파하면 O(N²)라, 전체 전파는 PR 도착 시(_handle_pull_request)에만 한다.
        # PR이 아직 안 도착했으면(CONTAINS 없음) noop — PR 도착 시 전체 전파가 처리한다.
        await builder.link_changeset_to_pr_issues(project_id, pr_num, hash_)

    # Layer 3: 파일별 diff 요약 → 배치 임베딩 → UNWIND 배치 upsert (#2/#6)
    #   1) 요약: 입력만의 함수라 파일별 동시(gather) — LLM N콜은 불가피(파일별 요약 필수)
    #   2) 임베딩: 요약 N개를 embed_batch로 1콜 (단건 N콜 대비 요청·rate-limit 절감)
    #   3) 저장: UNWIND로 세션 1번 (단건 N세션·락 경합 제거)
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
        return

    # 임베딩·저장 실패는 이벤트 전체를 실패시키지 않는다(노드·Layer 2는 이미 기록됨).
    try:
        embeddings = await embed_batch([summary for _, summary in summarized])
        file_rows = [
            {"file_path": path, "diff_summary": summary, "embedding": embedding}
            for (path, summary), embedding in zip(summarized, embeddings)
        ]
        await builder.upsert_files_with_modified_edges(
            project_id=project_id, changeset_hash=hash_, files=file_rows,
        )
        logger.debug("ChangeSet 파일 %d개 배치 처리 완료: hash=%s", len(file_rows), hash_)
    except Exception:
        logger.exception("ChangeSet 파일 배치 임베딩/저장 실패 (건너뜀): hash=%s", hash_)


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
    )

    # Layer 2 전파: PR에 등록된 issue_keys를 그 PR이 머지한 모든 CONTAINS 커밋에 text TRIGGERED_BY로 적용.
    # PR이 commits보다 늦게 도착하는 케이스도 _handle_changeset 쪽에서 다시 호출되어 동일하게 처리됨.
    if pr_number is not None and issue_keys:
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

    logger.debug("Issue 수신: issue_key=%s", props.get("issue_key"))

    resolved  = await resolve_actor(actor, source, make_neo4j_actor_store(project_id), event)
    embedding = await embed_text(f"{title}\n\n{body}")

    await builder.upsert_issue(
        project_id=project_id,
        issue_key=props.get("issue_key", ""),
        title=title,
        body=body,
        status=props.get("status", ""),
        issue_type=props.get("issue_type", ""),
        priority=props.get("priority", ""),
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
    if refs.get("parentIssueKey"):
        await builder.link_issue_to_parent(project_id, props["issue_key"], refs["parentIssueKey"])

    # Layer 2: Jira assignee → ASSIGNED_TO
    # 담당자도 작성자와 동일하게 resolve_actor를 거쳐 Actor로 승격한다 (이름 문자열을
    # Issue 속성에 저장하지 않기 위함). 이미 아는 담당자면 Step 0 alias 조회로 끝나 LLM 비용이 없다.
    if refs.get("assigneeId"):
        assignee_actor = {
            "id": refs["assigneeId"],
            "name": refs.get("assigneeName"),
            "email": refs.get("assigneeEmail"),
        }
        assigned = await resolve_actor(assignee_actor, source, make_neo4j_actor_store(project_id), event)
        await builder.link_issue_to_assignee(project_id, props["issue_key"], assigned["uuid"])
    else:
        # 이슈 이벤트는 최신 스냅샷이므로 assigneeId가 없다는 건 담당자가 해제됐다는 뜻이다.
        # 이 분기는 handle()에서 nodeType == "Issue"일 때만 타는 _handle_issue 안에 있으므로,
        # 이슈를 참조만 하는 다른 이벤트(코멘트 등)가 잘못 해제를 트리거할 일은 없다.
        await builder.unlink_issue_assignees(project_id, props["issue_key"])


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
