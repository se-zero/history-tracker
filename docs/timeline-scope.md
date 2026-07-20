# 시간축 조회 설계 (get_timeline 스코프 일반화)

"언제 무슨 일이 있었나"를 이슈 단위를 넘어 **파일·사람·기간** 기준으로도 조회할 수 있게 한다.
핵심 설계 원칙 두 가지:

1. **그래프 스키마를 바꾸지 않는다.** 시간은 이미 모든 도메인 노드에 네이티브 `datetime`으로
   저장돼 있다. 순수 시간순서는 타임스탬프에서 유도 가능하므로 노드·엣지로 재료화할 이유가 없다.
2. **노드가 아니라 이벤트를 나열한다.** 한 노드는 시간 이벤트를 여러 개 낳는다(Issue는 생성·종료,
   PR은 오픈·머지). 이 펼치기를 빠뜨리면 타임라인 절반이 사라지고 라벨이 뒤집힌다.

---

## 왜 그래프가 아니라 도구인가

Day/Week 노드 + `NEXT` 체인(time-tree)은 Neo4j에 네이티브 temporal 인덱스가 없던 시절의 패턴이다.
이 프로젝트에는 두 가지 이유로 맞지 않는다.

- **유도 가능하다.** `ORDER BY occurredAt`이 같은 일을 한다. 체인은 정보를 더하지 않는다.
- **증분 수집과 충돌한다.** 웹훅 기반이라 이벤트가 늦게·순서 뒤바뀌어 도착한다. `NEXT` 체인은
  삽입마다 앞뒤를 끊고 다시 이어야 하고, 백필·재수집에서 깨진다.

반대로 Sprint·Release처럼 **타임스탬프에서 유도되지 않는 기간 개념**은 그래프 노드가 맞다.
이 문서의 스코프는 전자(순수 시간순서)이며, 후자는 도입하지 않는다.

## 이벤트 펼치기 (노드 ≠ 이벤트)

시간축 조회의 공통 기반. 이 표가 단일 출처다.

| 노드 | 속성 | event_meaning |
|------|------|---------------|
| Issue | `createdAt` | `issue_created` |
| Issue | `closedAt` (terminal status일 때만 존재) | `issue_closed` |
| PullRequest | `createdAt` | `pr_opened` |
| PullRequest | `occurredAt` (= merged_at) | `pr_merged` |
| ChangeSet | `occurredAt` | `commit_authored` |
| Communication | `occurredAt` | `message_posted` |

`Issue.occurredAt`(최종 업데이트 시각)은 생성도 종료도 아니라 의미가 모호하므로 **이벤트로
만들지 않는다**. 이 규칙은 `agent/orchestrator.py`의 타임스탬프 의미 사전과 일치한다.

현재 `get_timeline`은 이 펼치기를 인라인으로 수행하고, `get_recent_activity`는 `n.occurredAt`
하나만 본다. 그래서 후자는 이슈를 "업데이트 시각"에 한 번 찍고, PR이 열린 시점을 통째로 잃는다.
`docs/query-quality-issues.md`의 "3월 24일에 이슈가 생성되었고 — 실제로는 완료일" 오류가
이 경로에서 나온다. 펼치기를 `tools/queries/_common.py`로 올려 두 도구가 공유한다.

## 도구 계약

```
get_timeline(jira_key?, path?, actor?, from_time?, to_time?)
```

| 파라미터 | 설명 |
|---|---|
| `jira_key` | 이슈 생명주기 (현행 동작) |
| `path` | 그 파일을 변경한 커밋과 연결 이슈·PR |
| `actor` | 그 사람의 활동 (이름·alias·email) |
| `from_time` / `to_time` | ISO-8601 기간 한정. 스코프와 조합 가능 |

- 스코프 우선순위: `jira_key` > `path` > `actor` > 프로젝트 전체. 전부 생략하면 전 기간.
- `jira_key` **필수 제약 해제**가 이번 변경의 본체다. 지금은 이슈 하나 단위로만 나온다.
- 반환: `{scope, window, events[], truncated}`. `events[]` 항목 구조는 현행 유지
  (`{type, event_meaning, occurredAt, data}`), occurredAt 오름차순, null occurredAt 제외.
- `window`는 **실제로 커버한 시간 구간**이다. 요청 구간과 다를 수 있다(아래 잘림 규칙).

`get_recent_activity`는 이번 범위에서 **남긴다**. 프롬프트가 모호 질문의 폴백 경로로 명시하고
있어, 같이 걷어내면 변경이 두 개가 되어 회귀 원인 분리가 안 된다. 통합은 후속 건으로 다룬다.

## 잘림 규칙

시간축은 순서가 답 자체라 잘림이 다른 도구보다 위험하다.

- `tools/executor.py`의 `_trim_tiered_dict`는 `detail`/`context` 키만 처리한다. `events` 키를
  가진 dict는 문자열 컷으로 떨어져 **JSON이 깨진다**. dict 반환으로 바꾸는 변경과 반드시
  같은 PR에 들어가야 한다.
- 기존 잘림 마커는 "뒷부분 생략"인데, 최신순 정렬에서 뒷부분은 **기간의 시작**이다.
  `query-quality-issues.md`의 "시작 시점 없이 완료 시점만 나열"이 이 경로다.
- 따라서 잘렸을 때는 건수가 아니라 `window`(실제 커버 구간)를 고지한다. 모델이 잘린 머리를
  보고 "이때 시작됐다"고 단정하지 못하게 하는 것이 목적이다.

## 인덱스를 지금 만들지 않는 이유

측정 스냅샷 기준 이벤트 대상 노드는 662개다(ChangeSet 297 / Communication 213 / Issue 96 /
PullRequest 56). 이 규모에서 인덱스 유무는 측정되지 않는다.

`get_recent_activity`의 `MATCH (n) WHERE (n:ChangeSet OR n:PullRequest OR ...)` 패턴은 레이블
disjunction이라 인덱스가 있어도 타지 못하고, 인덱스를 태우려면 레이블별 `UNION`으로 재작성해야
한다. 인덱스를 만들지 않는 이상 UNION도 같은 스캔이므로 함께 미룬다.

**후속 조건**: 멀티 프로젝트가 한 Neo4j에 쌓이거나 레포가 커지면 `(project_id, occurredAt)`
복합 range 인덱스를 이벤트 속성마다 추가한다(PullRequest·Issue는 두 개씩). 그때는 독립적으로
측정 가능한 변경이다.

## 작업 순서

각 단계는 독립적으로 측정 가능하도록 끊는다. 한 번에 하나만 바꾼다.

| # | 단계 | 파일 | 측정 |
|---|------|------|------|
| 0 | 골든 케이스 보충 + baseline | `eval/golden/` | baseline 확보 |
| 1 | 이벤트 펼치기 공용화 | `tools/queries/_common.py` | 회귀 0 확인 |
| 2 | 스코프 일반화 + 잘림 처리 | `tools/queries/issue.py`, `tools/definitions.py`, `tools/executor.py` | 주 변경 |
| 3 | 프롬프트 라우팅 + 문서 | `agent/orchestrator.py`, `docs/tools.md` | 최종 |

1단계는 행동 변화 없는 리팩토링이므로 회귀가 0이어야 한다. 여기서 점수가 움직이면 펼치기
규칙을 잘못 옮긴 것이다.

3단계를 빠뜨리면 안 된다. `query-quality-issues.md`의 문제 4·6은 모델이 `get_timeline`을
**호출조차 하지 않는** 것이므로, 계약만 넓히고 라우팅을 그대로 두면 효과가 나지 않는다.

## 측정 — 골든셋 보충이 선행이다

현재 골든셋 40건에 시간축 질문이 사실상 없다.

| 케이스 | 성격 | 경유 도구 |
|---|---|---|
| case-08, case-40 | 파일 변경 이력 시간순 | `get_file_history` |
| case-11 | "그 과정" — 문제 4의 케이스 | — |
| case-27 | PR 설명 | — |

프로젝트 전체·사람 기준 타임라인 질문이 0건이라 **코드를 고쳐도 e2e 점수가 움직이지 않는다.**
스코프별로 케이스를 보충하고 `eval/validate_golden.py`로 검증한 뒤 baseline을 뜬다.

- 프로젝트 전체 기간 — "이 프로젝트가 어떤 순서로 만들어졌어?"
- 파일 스코프 — 기존 case-08/40이 `get_file_history`가 아닌 경로로도 답해지는지
- 사람 스코프 — "OO가 언제 무슨 작업을 했어?"
- 기간 한정 — "5월에 무슨 일이 있었어?"

판정은 `eval/compare.py`의 paired 비교로 노이즈 플로어를 넘는지 본다.

## 코드 위치

- `tools/queries/_common.py` — 이벤트 펼치기 공용 헬퍼 (신규)
- `tools/queries/issue.py` — `get_timeline` 스코프 일반화
- `tools/definitions.py` — 도구 스키마 (`jira_key` 필수 해제, 스코프 파라미터 추가)
- `tools/executor.py` — 디스패치 인자 전달, `events` dict 잘림 처리
- `agent/orchestrator.py` — 도구 사용 가이드에 스코프별 진입점 추가
- `tests/unit/test_import_surface.py` — 공개 심볼 목록 유지 확인

> 도구를 수정하면 `definitions.py` / `queries` / `executor.py` 세 곳의 이름이 정확히 일치해야
> 한다. 반환 구조 변경은 `docs/tools.md`에도 반영한다.
