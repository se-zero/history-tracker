"""evidence → 서브그래프 변환의 순수 헬퍼 단위 테스트 (오프라인).

Neo4j 없이 도는 부분만 검증한다 — evidence를 타입별 키로 그룹핑하는
`_group_evidence_keys`, 조회된 노드 행을 evidence 순서대로 elementId에
정렬하는 `_resolve_seed_ids`, 같은 매칭 로직(`_match_evidence_row`)을 공유해
source를 정렬하는 `_resolve_seed_sources`, 그리고 노드 행을 프론트 GraphNode로
변환하며 focus 질의용 `ref`(도메인 키)를 방출하는 `_to_graph_node`. 실제 Cypher
resolve는 live Neo4j(integration) 영역.
"""

from graph.overview import (
    _CONTENT_TYPE_PREDICATES,
    _actor_source_summary,
    _group_evidence_keys,
    _normalize_evidence,
    _resolve_seed_ids,
    _resolve_seed_sources,
    _source_label,
    _to_graph_node,
)


def test_normalize_evidence_rules():
    # 정상 — type별 정규화된 (type, key)
    assert _normalize_evidence({"type": "commit", "id": "abc1234"}) == ("commit", "abc1234")
    assert _normalize_evidence({"type": "pull_request", "id": "#42"}) == ("pull_request", "42")
    assert _normalize_evidence({"type": "pull_request", "id": "99"}) == ("pull_request", "99")
    assert _normalize_evidence({"type": "issue", "id": " HT-37 "}) == ("issue", "HT-37")
    # document는 issue와 같은 규칙 — id가 곧 external_id.
    assert _normalize_evidence({"type": "document", "id": " page-1 "}) == ("document", "page-1")
    # message(Slack)는 숫자 ts 정규형(점 제거)으로 — 저장된 conversation_id와 점 제거 비교한다.
    assert _normalize_evidence({"type": "message", "id": "1700000000.123"}) == ("message", "1700000000123")
    # 무효 — None (group/resolve가 동일 규칙을 공유하므로 drift 불가)
    assert _normalize_evidence({"type": "commit", "id": "  "}) is None
    assert _normalize_evidence({"type": "pull_request", "id": "#notanum"}) is None
    assert _normalize_evidence({"type": "weird", "id": "x"}) is None
    assert _normalize_evidence({"type": "message", "id": "hello"}) is None


def test_normalize_evidence_slack_id_formats_converge():
    # LLM이 conversation_id 대신 퍼머링크 URL이나 점 없는 ts를 넣어도 같은 키로 정규화된다.
    canonical = ("message", "1781600092933319")
    assert _normalize_evidence(
        {"type": "message", "id": "https://slack.com/archives/C0AKGMXN8A1/p1781600092933319"}
    ) == canonical
    assert _normalize_evidence({"type": "message", "id": "1781600092933319"}) == canonical
    assert _normalize_evidence({"type": "message", "id": "1781600092.933319"}) == canonical
    # 퍼머링크에 쿼리 파라미터가 붙어도 /p 뒤 숫자만 취한다
    assert _normalize_evidence(
        {"type": "message",
         "id": "https://slack.com/archives/C0/p1781600092933319?thread_ts=1781594858.799809"}
    ) == canonical


def test_group_evidence_keys_buckets_by_type():
    evidence = [
        {"type": "commit", "id": "abc1234"},
        {"type": "pull_request", "id": "#42"},
        {"type": "pull_request", "id": "99"},
        {"type": "issue", "id": "HT-37"},
        {"type": "document", "id": "page-1"},
        {"type": "message", "id": "1700000000.123"},
    ]
    keys = _group_evidence_keys(evidence)
    assert keys["commit_prefixes"] == ["abc1234"]
    assert keys["pr_numbers"] == [42, 99]
    assert keys["issue_keys"] == ["HT-37"]
    assert keys["document_external_ids"] == ["page-1"]
    assert keys["conv_ids"] == ["1700000000123"]


def test_group_evidence_keys_skips_blank_and_unknown():
    evidence = [
        {"type": "commit", "id": "  "},
        {"type": "pull_request", "id": "#notanum"},
        {"type": "weird", "id": "x"},
        {"type": "issue", "id": ""},
        {"type": "document", "id": ""},
    ]
    keys = _group_evidence_keys(evidence)
    assert keys == {
        "commit_prefixes": [],
        "pr_numbers": [],
        "issue_keys": [],
        "document_external_ids": [],
        "conv_ids": [],
    }


def test_resolve_seed_ids_aligns_to_evidence_order():
    rows = [
        {"id": "n1", "label": "ChangeSet", "hash": "abc1234def"},
        {"id": "n2", "label": "PullRequest", "pr_number": 42},
        {"id": "n3", "label": "Issue", "issue_key": "HT-37"},
        {"id": "n4", "label": "Communication", "conversation_id": "1700000000.123"},
        {"id": "n5", "label": "Actor", "name": "neighbor (무시)"},
        {"id": "n6", "label": "Document", "external_id": "page-1"},
    ]
    evidence = [
        {"type": "issue", "id": "HT-37"},
        {"type": "commit", "id": "abc1234"},
        {"type": "message", "id": "1700000000.123"},
        {"type": "pull_request", "id": "#42"},
        {"type": "document", "id": "page-1"},
    ]
    assert _resolve_seed_ids(evidence, rows) == ["n3", "n1", "n4", "n2", "n6"]


def test_resolve_seed_ids_document_matches_by_external_id():
    # get_document_context/get_issue_context.documents[]가 이미 external_id를 노출하므로
    # 그래프 패널의 인용 카드도 같은 키로 매칭돼야 한다(§8, 2번 항목에서 파생된 빈틈).
    rows = [
        {"id": "n1", "label": "Document", "external_id": "page-1", "title": "설계 문서"},
        {"id": "n2", "label": "Document", "external_id": "page-2", "title": "다른 문서"},
    ]
    evidence = [
        {"type": "document", "id": "page-2"},
        {"type": "document", "id": "page-missing"},
    ]
    assert _resolve_seed_ids(evidence, rows) == ["n2", None]


def test_resolve_seed_ids_message_matches_across_formats():
    # 저장된 conversation_id는 점 있는 ts. LLM이 URL/점없음/점있음 어느 형식으로 넣어도
    # 같은 Communication 노드로 resolve되고, 노드가 없는 ts는 None.
    rows = [
        {"id": "n1", "label": "Communication", "conversation_id": "1781600092.933319"},
    ]
    evidence = [
        {"type": "message", "id": "https://slack.com/archives/C0/p1781600092933319"},
        {"type": "message", "id": "1781600092933319"},
        {"type": "message", "id": "1781600092.933319"},
        {"type": "message", "id": "1778586053.322069"},
    ]
    assert _resolve_seed_ids(evidence, rows) == ["n1", "n1", "n1", None]


def test_resolve_seed_ids_message_matches_reply_own_ts_via_url():
    # 답글 노드: conversation_id는 스레드 ts, url에는 자신의 ts. LLM이 답글의 자기 ts/퍼머링크를
    # 인용해도 url의 p-번호로 resolve되고, 스레드 ts로 인용하면 conversation_id로 resolve된다.
    rows = [
        {"id": "n1", "label": "Communication",
         "conversation_id": "1780130283.409449",
         "url": "https://slack.com/archives/C0/p1780225314542709"},
    ]
    evidence = [
        {"type": "message", "id": "https://slack.com/archives/C0/p1780225314542709"},  # 자기 ts(URL)
        {"type": "message", "id": "1780225314.542709"},                                 # 자기 ts(점)
        {"type": "message", "id": "1780130283.409449"},                                 # 스레드 ts
    ]
    assert _resolve_seed_ids(evidence, rows) == ["n1", "n1", "n1"]


def test_resolve_seed_ids_excludes_stub_issue():
    # stub(source='__stub__')은 title/body 없는 센티널이라 evidence 클릭 타깃에서 제외한다 —
    # 실이슈(n3)가 있으면 그걸 잡고, 실이슈 없이 stub만 있으면 미해석(None) 처리.
    rows = [
        {"id": "n1", "label": "Issue", "issue_key": "HT-37", "source": "__stub__"},
        {"id": "n2", "label": "Issue", "issue_key": "HT-99", "source": "JIRA"},
    ]
    evidence = [
        {"type": "issue", "id": "HT-37"},
        {"type": "issue", "id": "HT-99"},
    ]
    assert _resolve_seed_ids(evidence, rows) == [None, "n2"]


def test_resolve_seed_ids_unresolved_is_none():
    rows = [{"id": "n1", "label": "ChangeSet", "hash": "abc1234def"}]
    evidence = [
        {"type": "issue", "id": "HT-99"},
        {"type": "commit", "id": "abc1234"},
    ]
    assert _resolve_seed_ids(evidence, rows) == [None, "n1"]


def test_resolve_seed_sources_aligns_to_evidence_order():
    # _resolve_seed_ids와 같은 매칭(_match_evidence_row)을 공유하므로 타입별 매칭도 동일하게
    # 확인한다 — commit·pull_request·issue·message·document 전부.
    rows = [
        {"id": "n1", "label": "ChangeSet", "hash": "abc1234def", "source": "GITHUB"},
        {"id": "n2", "label": "PullRequest", "pr_number": 42, "source": "GITHUB"},
        {"id": "n3", "label": "Issue", "issue_key": "HT-37", "source": "JIRA"},
        {"id": "n4", "label": "Communication", "conversation_id": "1700000000.123", "source": "SLACK"},
        {"id": "n5", "label": "Document", "external_id": "page-1", "source": "NOTION"},
    ]
    evidence = [
        {"type": "issue", "id": "HT-37"},
        {"type": "commit", "id": "abc1234"},
        {"type": "message", "id": "1700000000.123"},
        {"type": "pull_request", "id": "#42"},
        {"type": "document", "id": "page-1"},
    ]
    assert _resolve_seed_sources(evidence, rows) == ["JIRA", "GITHUB", "SLACK", "GITHUB", "NOTION"]


def test_resolve_seed_sources_unresolved_is_none():
    rows = [{"id": "n1", "label": "ChangeSet", "hash": "abc1234def", "source": "GITHUB"}]
    evidence = [
        {"type": "issue", "id": "HT-99"},
        {"type": "commit", "id": "abc1234"},
    ]
    assert _resolve_seed_sources(evidence, rows) == [None, "GITHUB"]


def test_resolve_seed_sources_excludes_stub_issue():
    # stub(source='__stub__')은 카드 매핑에서도 빠지는 노드이니 소스 라벨링에서도 매칭되지 않아야 한다.
    rows = [
        {"id": "n1", "label": "Issue", "issue_key": "HT-37", "source": "__stub__"},
        {"id": "n2", "label": "Issue", "issue_key": "HT-99", "source": "JIRA"},
    ]
    evidence = [
        {"type": "issue", "id": "HT-37"},
        {"type": "issue", "id": "HT-99"},
    ]
    assert _resolve_seed_sources(evidence, rows) == [None, "JIRA"]


def test_resolve_seed_sources_blank_source_is_none():
    # coalesce(n.source, '')가 빈 문자열을 낼 수 있는 레거시 노드 — 소스 없음은 None으로 정규화.
    rows = [{"id": "n1", "label": "ChangeSet", "hash": "abc1234def", "source": ""}]
    evidence = [{"type": "commit", "id": "abc1234"}]
    assert _resolve_seed_sources(evidence, rows) == [None]


def test_to_graph_node_ref_carries_query_key():
    # commit ref는 전체 해시 — get_changeset_context 정확 매칭용(meta의 [:7] 프리픽스와 구분).
    commit = _to_graph_node(
        {"id": "n1", "label": "ChangeSet", "hash": "abc1234def5678", "message": "fix"}
    )
    assert commit["ref"] == {"type": "commit", "id": "abc1234def5678"}
    assert commit["meta"] == "abc1234"

    pr = _to_graph_node({"id": "n2", "label": "PullRequest", "pr_number": 42, "title": "t"})
    assert pr["ref"] == {"type": "pull_request", "id": "42"}

    issue = _to_graph_node({"id": "n3", "label": "Issue", "issue_key": "HT-37", "title": "t"})
    assert issue["ref"] == {"type": "issue", "id": "HT-37"}

    # GitHub Issue Communication → message 도구 대상, conversation_id를 실어 보낸다.
    gh = _to_graph_node(
        {"id": "n4", "label": "Communication", "source": "GITHUB", "conversation_id": "77", "body": "b"}
    )
    assert gh["ref"] == {"type": "message", "id": "77"}

    # Slack Communication → conversation_id는 meta엔 없지만 ref로 표면화된다.
    slack = _to_graph_node(
        {"id": "n5", "label": "Communication", "source": "SLACK",
         "conversation_id": "1700000000.123", "channel": "dev"}
    )
    assert slack["ref"] == {"type": "message", "id": "1700000000.123"}

    # Document → external_id를 ref로, type은 "doc"(성좌 별성이 아니라 주변 노드 취급).
    doc = _to_graph_node(
        {"id": "n10", "label": "Document", "source": "NOTION",
         "external_id": "page-1", "title": "설계 문서", "body": "본문"}
    )
    assert doc["type"] == "doc"
    assert doc["ref"] == {"type": "document", "id": "page-1"}
    assert doc["source"] == "notion"


def test_to_graph_node_ref_none_for_non_query_targets():
    # actor/file은 질의 도구 대상이 아니라 ref 없음 — 프론트가 텍스트 폴백으로 처리한다.
    actor = _to_graph_node({"id": "n6", "label": "Actor", "name": "me"})
    assert actor["ref"] is None
    file = _to_graph_node({"id": "n7", "label": "File", "path": "src/a.py"})
    assert file["ref"] is None


def test_to_graph_node_ref_none_when_key_missing():
    # 도메인 키가 없는 비정상 노드는 ref None — 잘못된 focus 타깃을 만들지 않는다.
    pr = _to_graph_node({"id": "n8", "label": "PullRequest"})
    assert pr["ref"] is None
    slack = _to_graph_node({"id": "n9", "label": "Communication", "source": "SLACK"})
    assert slack["ref"] is None
    doc = _to_graph_node({"id": "n11", "label": "Document", "source": "NOTION"})
    assert doc["ref"] is None


def test_source_label_derives_new_sources_without_registration():
    # 커넥터를 추가할 때 라벨 맵을 고치지 않아도 표기가 자연스러워야 한다.
    assert _source_label("LINEAR") == "Linear"
    assert _source_label("NOTION") == "Notion"
    # 대문자 snake 두 단어 → 단어별 대문자 (docs/normalized-event.md 「source · 표기 규칙」)
    assert _source_label("GOOGLE_CHAT") == "Google Chat"


def test_source_label_keeps_irregular_capitalization():
    # 유도로는 "Github"·"Clickup"이 되어 틀리는 이름만 맵에 등록돼 있다.
    assert _source_label("GITHUB") == "GitHub"
    assert _source_label("CLICKUP") == "ClickUp"
    # 유도로 맞는 이름은 맵에 없어도 그대로 나온다(기존 동작 유지)
    assert _source_label("JIRA") == "Jira"
    assert _source_label("SLACK") == "Slack"


def test_actor_source_summary_dedupes_and_keeps_order():
    # 계정ID는 노출하지 않고 소스 라벨만, 등장 순서대로 중복 제거한다.
    summary = _actor_source_summary(["GITHUB:se-zero", "LINEAR:u1", "GITHUB:dup", "GOOGLE_CHAT:u2"])
    assert summary == "GitHub · Linear · Google Chat"


def test_to_graph_node_carries_actual_source_not_archetype():
    # type은 프론트 렌더링 분류(색·범례)라 공용 값이지만, source는 실제 출처여야 한다 —
    # 커넥터가 늘어도 Linear 이슈가 "jira"로, Discord 대화가 "slack"으로 보이지 않게.
    linear = _to_graph_node(
        {"id": "n1", "label": "Issue", "source": "LINEAR", "issue_key": "ENG-42", "title": "t"}
    )
    assert linear["source"] == "linear"
    assert linear["type"] == "jira"  # 렌더링 분류는 이슈 트래커 공용

    discord = _to_graph_node(
        {"id": "n2", "label": "Communication", "source": "DISCORD", "conversation_id": "c1", "body": "b"}
    )
    assert discord["source"] == "discord"
    assert discord["type"] == "slack"  # 렌더링 분류는 대화 공용


def test_to_graph_node_chat_title_uses_source_when_channel_missing():
    # 채널 이름이 없을 때 "Slack 메시지"로 굳으면 다른 대화 소스가 Slack으로 오표시된다.
    discord = _to_graph_node({"id": "n1", "label": "Communication", "source": "DISCORD", "body": "b"})
    assert discord["title"] == "Discord 메시지"

    gchat = _to_graph_node({"id": "n2", "label": "Communication", "source": "GOOGLE_CHAT", "body": "b"})
    assert gchat["title"] == "Google Chat 메시지"

    # 채널이 있으면 채널 이름이 우선(기존 동작)
    slack = _to_graph_node(
        {"id": "n3", "label": "Communication", "source": "SLACK", "channel": "dev", "body": "b"}
    )
    assert slack["title"] == "#dev"


def test_chat_type_filter_matches_renderer_archetype():
    # 필터와 렌더러가 어긋나면 대화가 "slack"으로 그려지는데 "slack" 필터에서는 빠져 사라진다.
    # 렌더러(_to_graph_node)는 GITHUB이 아닌 Communication을 전부 대화로 보내므로 필터도 그래야 한다.
    chat_pred = _CONTENT_TYPE_PREDICATES["slack"]
    assert "'GITHUB'" in chat_pred and "<>" in chat_pred, "대화 필터는 'GitHub 아님' 기준이어야 한다"
    assert "'SLACK'" not in chat_pred, "특정 소스로 좁히면 Discord·Teams가 필터에서 빠진다"

    # 렌더러 쪽 기준도 함께 고정 — 둘이 같은 기준임을 이 테스트가 보증한다
    for source in ("DISCORD", "TEAMS", "GOOGLE_CHAT", "SLACK"):
        node = _to_graph_node({"id": "n", "label": "Communication", "source": source, "body": "b"})
        assert node["type"] == "slack", f"{source}는 대화 분류로 렌더돼야 한다"
    github = _to_graph_node(
        {"id": "n", "label": "Communication", "source": "GITHUB", "conversation_id": "7", "body": "b"}
    )
    assert github["type"] == "issue"
