"""키 중립화(jira_key → issue_key) 이전 이벤트의 진입점 정규화 검증.

pipeline-worker가 새 키를 발행하기 시작해도 브로커·retry 큐·DLQ에는 옛 키 이벤트가
남아 있을 수 있다 — handle() 진입점의 _normalize_legacy_keys가 이를 새 이름으로
옮겨 핸들러들이 새 키만 알면 되게 한다.
"""

from graph.event_handler import _normalize_legacy_keys


def test_legacy_keys_moved_to_new_names():
    event = {
        "refs": {"jiraKey": "HT-1", "jiraKeys": ["HT-1", "HT-2"], "parentJiraKey": "HT-0"},
        "properties": {"jira_key": "HT-1"},
    }
    _normalize_legacy_keys(event)

    assert event["refs"] == {
        "issueKey": "HT-1",
        "issueKeys": ["HT-1", "HT-2"],
        "parentIssueKey": "HT-0",
    }
    assert event["properties"] == {"issue_key": "HT-1"}


def test_new_key_wins_when_both_present():
    event = {
        "refs": {"jiraKey": "OLD-1", "issueKey": "NEW-1"},
        "properties": {"jira_key": "OLD-1", "issue_key": "NEW-1"},
    }
    _normalize_legacy_keys(event)

    assert event["refs"] == {"issueKey": "NEW-1"}
    assert event["properties"] == {"issue_key": "NEW-1"}


def test_new_key_events_and_bare_events_pass_through():
    new_event = {"refs": {"issueKey": "HT-1"}, "properties": {"issue_key": "HT-1"}}
    _normalize_legacy_keys(new_event)
    assert new_event == {"refs": {"issueKey": "HT-1"}, "properties": {"issue_key": "HT-1"}}

    bare_event = {"nodeType": "ChangeSet"}
    _normalize_legacy_keys(bare_event)  # refs/properties 없음 — 예외 없이 no-op
    assert bare_event == {"nodeType": "ChangeSet"}
