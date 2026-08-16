# 데이터 수집 전략

pipeline-worker가 각 플랫폼에서 데이터를 수집하는 방법과 API 한도 관리 전략을 정리한다.

---

## 공통 원칙

- **projectId 전파**: 모든 `NormalizedEvent`는 최상위에 `projectId`(프로젝트 UUID)를 갖고 발행된다.
  ai-engine은 이 값을 Neo4j 노드의 `project_id`로 저장해 프로젝트 단위 그래프 격리의 기준으로 쓴다 — `projectId` 없는 이벤트는 그래프에 반영되지 않는다 (docs/graph-schema.md 「프로젝트 격리」참고).
- **증분 수집**: checkpoint에 기록된 마지막 수집 시각 이후 데이터만 가져온다. 재시작해도 누락을 방지하고 중복 발행을 최소화한다.
- **occurredAt 기준 checkpoint 갱신**: 수집 시각(`Instant.now()`)이 아닌 이벤트 실제 발생 시각을 기준으로 갱신한다. 발행하지 못한 이벤트가 있어도 checkpoint가 앞으로 이동하지 않아 누락이 없다.
- **DB checkpoint 저장**: checkpoint는 DB `checkpoints` 테이블에 `(project_id, provider, cursor_key)` 단위로 저장한다. cursor 갱신 시 기존 값과 새 값 중 더 최신 시각을 저장해 checkpoint가 과거로 되돌아가지 않게 한다.
- **webhook/초기수집 동일 checkpoint 사용**: webhook 수집과 초기 수집 트리거(`POST /api/v1/collect/{provider}`)는 모두 `ProjectCollectionContext.projectId`로 같은 DB checkpoint를 조회하고 갱신한다. (수동 normalize endpoint는 제거됨)
- **raw endpoint는 샘플 전용**: `/api/v1/raw/*` endpoint는 필드 확인용 1페이지 샘플이며 DB checkpoint를 조회하거나 갱신하지 않는다.

---

## GitHub

### 수집 대상

| 타입 | 엔드포인트 | DB checkpoint |
|------|-----------|---------------|
| Commit | `GET /repos/{owner}/{repo}/commits` | `github/github_commits` |
| Pull Request | `GET /repos/{owner}/{repo}/pulls?state=closed` | `github/github_pull_requests` |
| Issue | `GET /repos/{owner}/{repo}/issues?state=all` | `github/github_issues` |

타입별 checkpoint가 독립적이라 재시작 시 완료된 타입은 건너뛴다. 코드 내부에서는 GitHub checkpoint snapshot을 `commitsScannedAt`, `pullRequestsScannedAt`, `issuesScannedAt` 필드로 다룬다.

**브랜치 스코프**: integration에 브랜치가 지정되면 해당 단일 브랜치로 수집을 제한한다 — PR은 `&base={branch}`(타겟 브랜치 기준), commit은 `&sha={branch}`. 브랜치 미지정 시 전체 브랜치를 수집한다. (브랜치는 `RawFetchRequest.options["branch"]`로 전달된다.)

### 페이지네이션

- 공통으로 `per_page=100`을 사용한다 (GitHub API 최대값).
- Commit: `/commits?per_page=100&page=N`을 사용한다. 별도 `sort` 파라미터는 없고, raw `commit.committer.date`를 checkpoint와 비교한다.
- Pull Request: `/pulls?state=closed&sort=updated&direction=desc&per_page=100&page=N`을 사용한다. `updated_at` 기준 최신순 페이지를 받은 뒤 `merged_at != null`, `merged_at > checkpoint`를 클라이언트에서 필터링한다.
- Issue: `/issues?state=all&sort=updated&direction=desc&per_page=100&page=N`을 사용한다. `updated_at`을 checkpoint와 비교한다.
- checkpoint 이전 항목이 나오면 이후 페이지도 모두 이전이라고 보고 **조기 종료**한다.
- normalize 경로는 페이지 단위로 `fetch → normalize → publish → checkpoint 갱신`을 반복한다. raw 응답용 엔드포인트는 전체 수집이 아니라 1페이지만 반환하는 샘플/debug 용도다.

### PR 수집 상세

`/pulls?state=closed`로 closed PR 페이지를 가져온 뒤 클라이언트에서 `merged_at != null` 필터링한다.
(GitHub `/pulls` API는 `merged_at` 서버사이드 필터를 지원하지 않음)

각 PR에 속한 커밋 목록은 `/pulls/{pr}/commits`로 추가 수집해 `prNumber` 매핑을 구성한다 (PR당 1회 호출).
각 커밋의 변경 파일 목록(`files`)은 `/commits/{sha}`로 추가 수집한다 (커밋당 1회 호출).

**User 프로필 보강**: PR·Issue의 `user` 객체에는 email·name이 없어 `/users/{login}`을 호출해 보강한다 (login별 캐시, 고유 login당 1회). Actor 동일인 판단의 email 신호가 여기서 채워진다.

PR 페이지는 먼저 발행하지만, `pullRequestsScannedAt` checkpoint는 commit 페이지 처리가 끝난 뒤 갱신한다.
commit 처리 중 실패하면 PR checkpoint가 아직 이동하지 않아 다음 실행에서 PR 페이지를 다시 읽고 `sha → prNumber` 매핑을 재구성할 수 있다.
이 선택은 PR 이벤트 중복 발행 가능성을 감수하고 commit의 `prNumber` 누락을 피하기 위한 것이다.

### occurredAt 기준

| 타입 | occurredAt 기준 | fallback | properties 보존 |
|------|----------------|---------|----------------|
| ChangeSet | `commit.committer.date` | `commit.author.date` | — |
| PullRequest | `merged_at` | `created_at` | `created_at`만 보존 |
| Communication (Issue) | `updated_at` | `created_at` | `created_at`만 보존 |

### Rate Limiting

- 매 API 호출 후 300ms 고정 딜레이
- 응답 헤더 `X-RateLimit-Remaining` ≤ 10이면 `X-RateLimit-Reset` 시각까지 동적 대기

### Tradeoff & 예상 문제점

#### 커밋당 `/commits/{sha}` 개별 호출 (enrichCommits)

`files`(변경 파일 목록과 diff)는 `/commits` list API 응답에 포함되지 않는다. 커밋당 1회 개별 detail API 호출이 불가피하다.

- **문제**: 커밋 수가 많을수록 호출량과 시간이 선형 증가. 커밋 1,000개면 1,000번 호출 + 300ms × 1,000 ≒ 5분.
  초기 전체 수집 시 수천 개의 커밋이 있는 저장소에서는 수십 분이 걸릴 수 있다.
- **방법 선택 이유**: `files`의 diff는 LLM이 diffSummary를 생성하고 `MODIFIED` 관계를 구축하는 핵심 데이터다. 없으면 지식 그래프의 코어 기능이 동작하지 않는다.

#### PR 수집 — closed 페이지 수집 후 클라이언트 필터

GitHub Search API(`/search/issues?is:pr is:merged`)를 사용하면 서버사이드에서 merged PR만 필터할 수 있지만, 현재 환경에서 422 오류로 사용 불가. `/pulls` API에는 `merged_at` 서버사이드 필터가 없다.

- **문제**: `updated_at > checkpoint`이지만 `merged_at <= checkpoint`인 PR — 이미 수집했지만 이후에 코멘트·리뷰가 달려 updated된 경우 — 을 API로 받아왔다가 버린다.
  오래된 PR에 코멘트가 활발한 저장소에서 불필요한 API 호출이 증가할 수 있다.
- **방법 선택 이유**: 동작하는 유일한 방법. `closed` → `merged_at`으로 2단계 필터하므로 정확성은 보장된다.

#### PR-커밋 관계 구축 — `/pulls/{pr}/commits` (PR당 1회 호출)

- **문제**: PR 100개 = commits 엔드포인트 100번 추가 호출. 초기 전체 수집 시 PR이 많은 저장소에서 호출량 급증.
- **방법 선택 이유**: 커밋 메시지에 `PR #123` 텍스트가 없으면 `CONTAINS` 관계가 유실된다. API 기반으로 하면 squash/rebase merge를 포함해 100% 정확한 관계 구축이 가능하다. 증분 수집에서 수집 대상 PR이 적어 허용 범위.

#### Issues list가 PR 포함

GitHub `/issues` API는 PR도 반환한다. `GitHubNormalizer`에서 `pull_request` 키가 있는 항목을 필터링해 버린다.

- **문제**: PR 데이터를 이슈 엔드포인트에서도 받아오지만 정규화 시 폐기 → 불필요한 데이터 전송.
- **방법 선택 이유**: GitHub API에 `is_issue=true` 같은 서버사이드 필터가 없다. API 특성상 불가피.

#### GitHub — 실행 중 유지 데이터

PR, commit, issue를 페이지 단위로 처리해 raw·`NormalizedEvent` 전체를 한 번에 누적하지 않는다. 다만 PR-commit 관계 보강을 위해 `sha → prNumber` 맵은 실행 동안 유지한다 (PR raw 전체보다 작고 commit `refs.prNumber` 보강에 필요).

## Jira

### 수집 대상

Jira 이슈 전체를 JQL로 조회한다.

Jira checkpoint는 `checkpoints` 테이블에서 `provider=jira`, `cursor_key=jira_updated` row에 저장한다.

```
project = {key} AND updated >= "{checkpoint}" ORDER BY updated ASC
```

- `updated` 기준 필터: 생성 이후 한 번도 안 변경된 이슈는 수집 안 되지만, 상태·담당자·본문 변경 등 실질적 변경이 있는 이슈는 빠짐없이 수집된다.
- `ORDER BY updated ASC`: 오래된 변경부터 처리 → 상한 도달 시 최신 데이터 누락 최소화.

### 페이지네이션

- `nextPageToken` 기반 (Jira REST v3)
- 페이지당 처리 후 즉시 checkpoint 갱신 → 중단 재개 가능
- 한 번 실행에서 처리할 최대 페이지 수: `app.jira.max-pages-per-run` (기본값 50)
  - 상한 도달 시 `limitReached=true` 반환, 다음 호출에서 이어받음
  - 상한을 두는 이유: 장시간 블로킹 방지 (페이지당 API 호출 + 200ms 딜레이)

### 클라이언트 필터

JQL `updated >= checkpoint`는 Jira 서버에서 분 단위로 반올림될 수 있다. 응답에서 `updated < checkpoint`인 항목을 클라이언트에서 한 번 더 걸러낸다.

### occurredAt 기준

`updated` 시각 (fallback: `created`). `created_at`은 별도 property로 보존.

### Rate Limiting

호출당 200ms 고정 딜레이.

### Tradeoff & 예상 문제점

#### `updated >= checkpoint` 기준 — 이미 발행된 이슈의 재발행

이슈 상태가 바뀌면 동일 이슈가 다시 수집되어 재발행된다.

- **문제**: ai-engine이 동일 이슈(`(project_id, source, external_id)` 키)의 이벤트를 upsert로 처리하지 않으면 노드가 중복 생성된다.
- **방법 선택 이유**: `created` 기준으로만 수집하면 기존 이슈의 상태 변경이 그래프에 반영되지 않는다. 변경 이력 추적이 이 프로젝트의 핵심 목적이므로 중복 발행이 누락보다 낫다. ai-engine의 upsert 처리가 전제 조건.

#### `app.jira.max-pages-per-run` 상한 — 처리 지연

한 번 실행에서 처리 못 한 페이지는 다음 호출까지 발행이 미뤄진다.

- **문제**: 초기 전체 수집 또는 대규모 업데이트 배치 직후에 여러 번 호출해야 완료된다. 스케줄링 없이 수동 호출하면 "언제 다 처리되는지" 알기 어렵다.
- **방법 선택 이유**: 상한 없이 처리하면 페이지당 200ms × 수백 페이지 = 수 분 블로킹. 다른 타입(GitHub, Slack) 수집을 차단한다. 페이지 단위 checkpoint 갱신으로 중단 재개가 가능해 반복 호출로 완료할 수 있다.

#### changelog 확장 미사용

`JiraRawService.fetchSearchPage`는 changelog를 요청하지 않는다. `JiraNormalizer`가 사용하는 필드만 search body의 `fields`에 포함한다.

- **문제**: changelog에는 이슈 필드 변경 이력이 포함되어 페이로드가 수배 증가할 수 있다.
- **방법 선택 이유**: normalize 경로에서 changelog를 사용하지 않으므로 요청 payload에서 제외한다.

#### 클라이언트 이중 필터 — 서버에서 이미 필터했는데 다시 필터

JQL 서버 필터 + `filterIssuesByUpdated` 클라이언트 필터의 이중 구조.

- **이유**: Jira JQL의 날짜 비교가 분 단위 반올림될 수 있다. `"2024-01-01 10:30"` 기준으로 JQL을 날려도 `10:30:29`인 이슈가 결과에 포함될 수 있다. 클라이언트 필터로 초 단위 정밀도를 보완한다. 성능 비용은 이미 받아온 응답 내 필터링이므로 무시 가능한 수준.

## Linear

### 수집 대상

Linear 이슈를 GraphQL API로 조회한다. 코멘트는 수집하지 않고(이슈만), 삭제 이벤트도 수집하지 않는다.

Linear checkpoint는 `checkpoints` 테이블에서 `provider=linear`, `cursor_key=linear_updated` row에 저장한다.

GraphQL 엔드포인트는 `{base}/graphql` 하나뿐이다(REST처럼 리소스별 엔드포인트가 나뉘지 않는다). 연동 시 선택한 team으로 `team(id:)` 스코프를 걸고, 아래 형태로 조회한다.

```
team(id: $teamId) {
  issues(filter: {updatedAt: {gte: $since}}, orderBy: updatedAt, after: $cursor) { ... }
}
```

- `filter: {updatedAt: {gte: $since}}`: checkpoint 이후 변경분만 조회한다.
- `orderBy: updatedAt`: 최신 변경이 먼저 오는 내림차순 고정이다 — 방향을 제어하는 파라미터가 없다(Jira의 `ORDER BY updated ASC`와 반대). 이 정렬 방향이 checkpoint 전진 시점에 영향을 준다 (아래 checkpoint 갱신·Tradeoff 참고).
- Linear API 오류는 HTTP 200 + `errors` 배열로도 온다. `errors`가 있으면 `data`가 함께 와도 전체 응답을 거부한다.

### 페이지네이션

- Relay 커서(`after`) 기반.
- 한 번 실행에서 처리할 최대 페이지 수: `app.linear.max-pages-per-run` (기본값 50)
  - 상한 도달 시 `limitReached=true` 반환, checkpoint는 전진시키지 않는다 (아래 참고).

### checkpoint 갱신

Slack과 동일하게 페이지 단위가 아니라 **전체 실행 성공 후 1회** 갱신한다 — 이번 실행에서 관측한 최대 `occurredAt`을 한 번만 반영한다. `limitReached=true`(페이지 상한 도달)면 이번 실행에서는 전진시키지 않는다. 초기 수집은 `since=EPOCH`로 시작한다.

### occurredAt 기준

이슈 `updatedAt` 시각.

### Rate Limiting

호출당 720ms 고정 딜레이 (Linear API 한도 5,000 req/h 기준).

### 수집 트리거

webhook·스케줄러 없이, 연동 직후 1회 초기 수집과 GitHub PR 머지 웹훅 처리에 편승한 증분 수집만 있다(Jira·Slack과 동일한 패턴 — `services/pipeline-worker/CLAUDE.md` 「Webhook 수집 흐름」 참고).

### Tradeoff & 예상 문제점

#### `orderBy: updatedAt` 내림차순 고정 — 페이지 단위 checkpoint 전진 불가

Linear GraphQL API의 `orderBy`에는 방향 제어 파라미터가 없어 항상 최신 변경이 먼저 온다.

- **문제**: GitHub PR·Jira처럼 페이지 단위로 checkpoint를 전진시키면, 중간에 중단(truncation)됐을 때 아직 받지 못한 과거 페이지의 변경분이 checkpoint 이후로 밀려 영구 누락된다.
- **방법 선택 이유**: Slack과 동일하게 전체 실행 성공 후 최대 occurredAt을 한 번만 전진시켜 truncation 시 재시도가 처음부터 다시 돌게 한다. 이미 처리한 항목의 재발행(중복) 가능성을 감수하고 누락을 막는다.

#### `app.linear.max-pages-per-run` 상한 — 처리 지연

한 번 실행에서 처리 못 한 페이지는 checkpoint가 전진하지 않아 다음 호출에서 처음부터 다시 받는다.

- **문제**: 초기 전체 수집 또는 대규모 업데이트 배치 직후에는 여러 번 호출해야 완료될 수 있다.
- **방법 선택 이유**: 상한 없이 처리하면 페이지당 720ms 딜레이가 누적돼 장시간 블로킹된다. Jira의 `app.jira.max-pages-per-run`과 같은 이유다.

## Asana

### 수집 대상

Asana 태스크를 REST API로 조회한다. 코멘트(story)는 수집하지 않고(태스크만), 삭제 이벤트도 수집하지 않는다.

Asana checkpoint는 `checkpoints` 테이블에서 `provider=asana`, `cursor_key=asana_updated` row에 저장한다.

```
GET {base}/tasks?project={gid}&modified_since={checkpoint}&limit=100&opt_fields=name,notes,completed,completed_at,created_at,modified_at,created_by.name,created_by.email,assignee.name,assignee.email,parent
```

- `modified_since`: checkpoint 이후 변경분만 조회한다.
- 연동 시 선택한 project로 스코프한다 — `/tasks?project=`는 해당 project에 직속(multi-home 포함)된 태스크만 반환하고 서브태스크는 포함하지 않는다.

### 페이지네이션

- `next_page.offset` 토큰 기반.
- 한 번 실행에서 처리할 최대 페이지 수: `app.asana.max-pages-per-run` (기본값 50)
  - 상한 도달 시 `limitReached=true` 반환, checkpoint는 전진시키지 않는다 (아래 참고).

### checkpoint 갱신

Slack·Linear와 동일하게 페이지 단위가 아니라 **전체 실행 성공 후 1회** 갱신한다 — 이번 실행에서 관측한 최대 `occurredAt`을 한 번만 반영한다. `limitReached=true`(페이지 상한 도달)면 이번 실행에서는 전진시키지 않는다. 초기 수집은 `EPOCH`부터 시작한다.

### occurredAt 기준

태스크 `modified_at` 시각. `created_at`은 별도 property로 보존한다.

### Rate Limiting

호출당 400ms 고정 딜레이 (무료 워크스페이스 한도 150 req/min 기준 — 유료 워크스페이스는 1,500 req/min이라 여유가 있다).

### 수집 트리거

webhook·스케줄러 없이, 연동 직후 1회 초기 수집과 GitHub PR 머지 웹훅 처리에 편승한 증분 수집만 있다(Jira·Slack·Linear와 동일한 패턴 — `services/pipeline-worker/CLAUDE.md` 「Webhook 수집 흐름」 참고).

### Tradeoff & 예상 문제점

#### multi-home 태스크의 `modified_since` 맹점

Asana 태스크는 여러 project에 동시에 속할 수 있다(multi-home). 기존 태스크를 나중에 수집 대상 project에 추가해도 `modified_at`이 그 시점에 갱신된다는 보장이 없다 — Asana 공식 문서에 이 갱신 규약이 명시돼 있지 않다.

- **문제**: 이미 checkpoint를 지난 시각에 생성된 태스크가 이후 수집 대상 project로 옮겨져도 `modified_since` 필터에 걸리지 않으면 다음 증분 수집에서 영구히 누락될 수 있다.
- **방법 선택 이유**: webhook 없는 설계에서는 project 멤버십 변경을 별도로 감지할 방법이 없다. 다른 provider와 마찬가지로 API가 제공하는 필터에 의존하는 트레이드오프를 감수한다.

#### 서브태스크 미수집

`/tasks?project=`는 project에 직속된 태스크만 반환하고 서브태스크는 포함하지 않는다.

- **문제**: 서브태스크를 수집하려면 태스크당 `/tasks/{gid}/subtasks` 추가 호출이 필요하다 — 태스크 수만큼 호출이 선형 증가해 400ms 고정 딜레이 기준 rate 예산이 붕괴한다.
- **방법 선택 이유**: 서브태스크는 범위 밖으로 두고 수집하지 않는다. 서브태스크의 `parent` refs는 발행하되(수집 대상 프로젝트 밖의 부모라도), 범위 밖 부모 태스크는 pre-node로 잔존하는 것을 감수한다.

#### 이슈 키 부재 → URL 참조 의존

Asana 태스크에는 Jira `HT-7`·Linear `ENG-42` 같은 사람용 표시 키가 없다(`gid` 불변 ID만 있다).

- **문제**: 커밋·PR·Slack 텍스트에서 태스크를 참조하려면 키 매칭이 불가능하다.
- **방법 선택 이유**: Asana 태스크 URL(`app.asana.com/0/.../{task}`, `app.asana.com/1/.../{task}`)에서 gid를 추출하는 `refs.issueExternalRefs` 경로로 대체한다 (`docs/normalized-event.md` 참고). 이슈 키 형식 참조가 아니라 URL 형식 참조에 전적으로 의존하는 첫 사례다.

---

## ClickUp

### 수집 대상

ClickUp 태스크를 Filtered Team Tasks 단일 엔드포인트로 조회한다. 연동 스코프는 List 단위(4단 선택의 마지막 단계)라 `list_ids[]`로 필터한다.

ClickUp checkpoint는 `checkpoints` 테이블에서 `provider=clickup`, `cursor_key=clickup_updated` row에 저장한다.

```
GET {base}/team/{workspace_id}/task?page={page}&date_updated_gt={checkpoint_epoch_ms}&list_ids[]={list_id}&include_closed=true&subtasks=true
```

- `date_updated_gt`: checkpoint 이후 변경분만 조회한다.
- `include_closed=true`·`subtasks=true`는 **반드시 명시해야 한다** — ClickUp API 기본값이 각각 종료 상태 태스크와 서브태스크를 응답에서 제외하므로, 빠뜨리면 Done/Closed 태스크와 서브태스크가 통째로 누락된다.

### 페이지네이션

- `page` 번호 기반(0부터 시작) — offset 토큰이 아니다.
- 페이지당 100건. API 응답에 마지막 페이지 표시가 없어 **응답 태스크 건수가 100건 미만이면 마지막 페이지로 판정**한다.
- 한 번 실행에서 처리할 최대 페이지 수: `app.clickup.max-pages-per-run` (기본값 50)
  - 상한 도달 시 `limitReached=true` 반환, checkpoint는 전진시키지 않는다 (아래 참고).

### checkpoint 갱신

Slack·Linear·Asana와 동일하게 페이지 단위가 아니라 **전체 실행 성공 후 1회** 갱신한다 — API 응답에 정렬 보장이 문서화돼 있지 않아 페이지 단위 전진이 불가능하기 때문이다(Asana와 같은 이유). 이번 실행에서 관측한 최대 `occurredAt`을 한 번만 반영하며, `limitReached=true`(페이지 상한 도달)이거나 발행이 실패하면 이번 실행에서는 전진시키지 않는다. 초기 수집은 `EPOCH`부터 시작한다.

### occurredAt 기준

태스크 `date_updated` 시각(epoch ms 문자열 → `Instant` 변환).

### Rate Limiting

호출당 600ms 고정 딜레이 (무료 워크스페이스 한도 100 req/min 기준).

### 토큰

ClickUp access token은 만료·갱신·원격 폐기가 없다(ClickUp 공식 문서 확정) — Jira·Linear·Asana와 달리 `AccessTokenRefresher`·`ProviderCredentialLifecycle` 빈을 등록하지 않는다.

### 수집 트리거

webhook·스케줄러 없이, 연동 직후 1회 초기 수집과 GitHub PR 머지 웹훅 처리에 편승한 증분 수집만 있다(Jira·Slack·Linear·Asana와 동일한 패턴 — `services/pipeline-worker/CLAUDE.md` 「Webhook 수집 흐름」 참고).

---

## Slack

### 수집 대상

| 타입 | 엔드포인트 |
|------|-----------|
| 채널 목록 | `conversations.list` |
| 채널 메시지 | `conversations.history` |
| 스레드 replies | `conversations.replies` |

### Reply 수집 전략

단순히 checkpoint 이후 루트 메시지만 수집하면 **오래된 스레드에 달린 새 reply**를 놓친다.

아래 조건을 만족하는 메시지에 대해 replies를 수집한다:
1. `reply_count > 0`
2. **메시지가 checkpoint 이후** 또는 **`latest_reply`가 checkpoint 이후**

`conversations.replies` 호출 시 `oldest=채널 커서` 파라미터를 붙여 서버사이드 필터링한다.

수집 대상(발행할 메시지)과 reply 확인 대상(오래된 스레드 포함)을 `ChannelMessages` record로 분리해 처리한다.
normalize 경로는 `conversations.history`를 page 단위로 가져오고, 해당 page의 메시지와 thread replies를 정규화·발행한다.

### Checkpoint — 채널별 커서

Slack checkpoint는 `checkpoints` 테이블에 **채널별로** 저장한다: `provider=slack`, `cursor_key=slack_messages:<channelId>`.
채널의 history를 끝까지 걸은(완주한) 직후 그 채널의 최대 `occurredAt`으로 갱신한다 — history가 최신→과거
순이라 최대값이 1페이지에 오므로 페이지 단위 전진은 불가하고, **채널 완주가 안전한 최소 단위**다. 중간에
실행이 죽어도 완주한 채널의 진행은 보존되어 다음 실행이 그 채널을 (아래 14일 되돌아보기만 남기고) 건너뛴다 —
429가 잦은 신규 한도 체제에서 초기 수집을 여러 실행에 나눠 끝내기 위한 구조다.

- 채널 키가 없으면 레거시 전역 키(`slack_messages`)를 fallback으로 읽는다(채널별 도입 전 데이터의 이관 경로).
  이벤트가 없는 채널도 시작 커서를 그대로 자기 키로 저장하므로, 무예외 완주 1회면 전 채널이 자기 키를
  갖게 되고 그 시점에 레거시 키를 삭제한다. 채널 목록이 비어 있으면 워터마크 보호를 위해 삭제하지 않는다.
- 채널 목록에 없는 채널의 키(삭제·아카이브된 채널의 고아 커서)는 목록 조회 직후 삭제한다. 봇이 쫓겨난
  private 채널이 재초대되면 그 채널만 처음부터 다시 수집된다(다운스트림 중복 제거 전제로 수용).

### occurredAt 기준

메시지 `ts` (Unix epoch 소수 문자열 → `Instant` 변환).

### Rate Limiting

엔드포인트별 고정 딜레이:
- `conversations.list`: 3,000ms
- `conversations.history` · `conversations.replies`: 1,200ms

429 응답은 `Retry-After` **헤더**(정수 초, 없거나 형식이 어긋나면 60초 폴백)만큼 대기 후 최대 3회
재시도한다. 첫 429부터는 그 실행 동안 해당 엔드포인트의 호출 간격을 Retry-After 값으로 승격한다
(실행 단위 `SlackPacing`). 배경: 2025-05-29 이후 생성된 비마켓플레이스 배포형 앱은 `history`/`replies`가
분당 1회·요청당 15건으로 제한되는데(Marketplace 승인 시 해제), 고정 딜레이는 구 한도 기준을 유지하고
신규 한도는 429 적응으로 흡수해 어느 체제든 설정 변경 없이 동작한다. `ok:false`/null 응답은 빈 페이지로
삼키지 않고 즉시 예외로 실패시킨다 — 조용히 넘기면 "채널 완주"로 위장돼 채널 커서를 잘못 전진시킨다.

### Tradeoff & 예상 문제점

#### `conversations.history`의 `oldest` — 커서 − 14일 되돌아보기

`fetchHistoryPage`는 채널 커서가 있으면 URI에 `oldest = 채널커서 − 14일`(`THREAD_LOOKBACK`)을 붙여
서버사이드로 자른다. 커서가 없으면(초기 수집) oldest 없이 전체를 걷는다.

- **왜 커서 그대로가 아니라 14일을 빼는가**: history는 스레드 답글을 반환하지 않는다(루트만 옴). 오래된
  루트에 새 답글이 달렸는지는 루트의 `latest_reply`를 봐야 아는데, 커서로 그대로 자르면 커서 이전 루트가
  응답에서 사라져 기존 스레드의 새 답글을 전부 놓친다. 14일 창 안의 루트는 다시 보이지만 클라이언트
  필터(`isBeforeCheckpoint`)가 재발행을 막고, replies의 `oldest`는 커서 그대로라 새 답글만 온다.
- **알려진 한계**: 루트가 14일보다 오래된 스레드에 달리는 새 답글은 놓친다. 창을 넓히면 실행당 호출 수가
  비례해 늘어나는 트레이드오프라 14일 고정으로 정했다(설정으로 빼지 않음).

#### `ts` 파싱 실패 시 `Instant.now()` 폴백 — 커서 오염 가능성 (보류 중)

`SlackNormalizer.tsToInstant`는 `ts`가 없거나 파싱에 실패하면 `Instant.now()`를 반환한다. 이 값이
`occurredAt`으로 흘러 채널 커서를 미래로 끌어올리면 `oldest` 필터가 실제로 안 본 구간을 건너뛸 수 있다.
정상 응답에서는 발생하지 않는 경로라 수정을 미뤘다 — 후속 작업 후보.

#### Slack — 실행 중 유지 데이터

채널의 history page 단위로 처리해 workspace 전체 raw·`NormalizedEvent`를 한 번에 누적하지 않는다. 다만 `users.list` 결과인 user map과 `conversations.list` 결과인 채널 목록은 실행 동안 유지한다.

#### User map 전체 수집 — 매 실행마다 반복

`fetchUserMap`으로 워크스페이스 전체 멤버를 매번 수집한다.

- **문제**: 수천 명 규모 워크스페이스에서 `users.list`를 여러 페이지로 호출. 멤버 수 / 200 페이지 × 1,200ms 딜레이 추가.
- **방법 선택 이유**: 메시지에는 userId만 있어 displayName·email 보강이 필요하다. user map은 실행당 1회 전체 수집한다.

#### 모든 채널 무조건 수집

채널 필터링 없이 워크스페이스 전체 채널을 수집한다.

- **문제**: 수백 개 채널이 있는 대형 워크스페이스에서 conversations.list 페이지 순회 + 채널당 history 호출로 수집 시간이 채널 수에 비례해 증가한다.
- **방법 선택 이유**: 어떤 채널에 관련 맥락이 있을지 사전에 알 수 없어 전체 채널을 대상으로 한다.

---

## Discord

상세 설계 근거(Discord API 실측 결과 포함)는 `docs/discord-integration.md` 참고. 여기서는 수집 전략만 다룬다.

### 수집 대상

| 타입 | 엔드포인트 |
|------|-----------|
| 텍스트 채널 목록 | `GET /guilds/{guild_id}/channels` (type 0·5만) |
| 활성 스레드 목록 | `GET /guilds/{guild_id}/threads/active` |
| 채널·스레드 메시지 | `GET /channels/{channel_id}/messages` |

DB checkpoint: `discord/discord_messages` (채널을 가로지르는 커서가 하나라, 채널별로 갱신하면 늦은
채널이 이른 채널의 커서를 덮으므로 전체 실행 마지막에 한 번만 갱신한다).

### 증분 전략 — snowflake 기반 서버사이드 필터

Discord 메시지 ID(snowflake)는 생성 시각을 품고 있어, checkpoint의 `Instant`를 snowflake로 변환해
`after` 파라미터에 넣으면 **서버가 직접 걸러준다** — Slack의 14일 되돌아보기 같은 보정 없이 커서 그대로 자를 수 있다(스레드가 채널로 분리된 모델이라 답글 유실 문제가 없다).

`after`는 커서 바로 다음 구간부터 앞으로 전진하며 채운다(재실측 2026-08-11 — `docs/discord-integration.md`
「확인 완료」 4). 백로그가 페이지 크기(100)를 넘으면 가장 오래된 쪽 100개가 먼저 오고, 최신→과거
내림차순은 배치 안쪽 정렬일 뿐 선택 구간과 무관하다. 따라서 가득 찬 페이지를 받으면 그 배치의
**최대 id를 다음 `after`로** 삼아 이어받고, 100건 미만이 오면 멈춘다. 서버가 커서 이후만 걸러 주므로
클라이언트 쪽 경계 필터링은 없다. 커서는 노이즈 필터 이전 원본에서 뽑는다 — 한 페이지가 전부
봇/시스템 메시지여도 전진해야 한다.

Slack과 마찬가지로 **채널 전체를 모으지 않고 페이지마다 발행한다**. 발행 배치가 채널 크기에 비례하면
`EventPublisher`의 단일 confirm 타임아웃(10초)에 걸려 재시도해도 계속 실패하기 때문이다.

> 초기 설계는 정렬 방향을 선택 구간으로 오해해 "최신 100개만 온다"고 보고 `before` 역방향 보정을
> 넣었다. 채널당 100건에서 수집이 끊기는데 checkpoint는 채널을 가로지르는 단일 커서라, 다른 채널의
> 더 최신 메시지가 커서를 끌어올려 **끊긴 채널의 중간 구간이 영구 유실**됐다(단일 채널 길드에서만
> 무해했다). 전진형으로 교체해 채널이 매번 완전히 비워지도록 했다 — 단일 커서의 전제가 그것이다.

### occurredAt 기준

메시지 `timestamp`(ISO-8601). `edited_timestamp`는 커서를 되돌리지 않도록 쓰지 않는다.

### Rate Limiting

호출마다 고정 250ms 딜레이(봇당 초당 50요청 상한에 여유). 429 응답은 본문 `retry_after`(초)만큼
대기 후 최대 3회 재시도한다.

### Tradeoff & 예상 문제점

#### 채널 단위 403은 건너뛰고 계속 진행

봇이 View Channel·Read Message History 권한을 못 받은 채널은 403이거나 빈 결과다.

- **문제**: 서버 관리자가 일부 채널을 봇에게 안 보이게 설정할 수 있다. 이걸 전체 수집 실패로 처리하면
  권한 있는 나머지 채널의 맥락까지 놓친다.
- **방법 선택 이유**: 채널 단위 실패를 삼키고 다음 채널로 넘어간다 — 한 채널의 권한 누락이 전체 수집을
  막으면 안 된다. 단 RabbitMQ 발행 예외는 삼키지 않는다(계약대로 checkpoint를 전진시키지 않아야
  재발행된다).

#### 자격증명이 프로젝트별이 아니라 앱 전역

수집은 DB에 저장된 프로젝트별 자격증명이 아니라, pipeline-worker 자신의 설정(`app.discord.bot-token`)에
있는 봇 토큰으로 한다.

- **문제**: Slack·Jira·GitHub는 전부 "이 프로젝트가 연결한 자격증명으로 그 프로젝트 데이터만 수집"이라는
  모델인데, Discord는 봇 하나가 여러 서버(=여러 프로젝트)에 동시에 들어가 있다.
- **방법 선택 이유**: Discord REST API는 사용자 OAuth 토큰으로 메시지 히스토리를 못 읽는다 — 봇 토큰만
  가능하다. DB 행에는 `external_ref.guild_id`(수집 대상 식별)만 있고, 사용자 OAuth 토큰(refresh
  token)은 연동 해제 시 grant 폐기 용도로만 쓰인다.

#### 아카이브된 스레드는 1차 범위에서 제외

`GET /channels/{id}/threads/archived/public`은 호출하지 않는다.

- **문제**: 활성 스레드 목록에는 없지만 아카이브된 스레드에 새 메시지가 있을 수 있다(Discord는 스레드를
  자동 아카이브한다).
- **방법 선택 이유**: 활성 스레드만으로 시작하고 실사용에서 누락이 문제가 되면 확장한다 —
  `docs/discord-integration.md`의 「구현 시 확인」에 남겨둔 제품 결정이다.

## Google Chat

상세 설계 근거(계정 게이트·인증 모델 조사 포함)는 `docs/google-chat-integration.md` 참고.
여기서는 수집 전략만 다룬다.

### 수집 대상

| 타입 | 엔드포인트 |
|------|-----------|
| 스페이스 표시 이름 | `GET /v1/{space_id}` (매 수집 1회 — 이름 변경 추적용) |
| 스페이스 메시지 | `GET /v1/{space_id}/messages` |

수집 범위는 연동 시 선택한 **스페이스 1개**다(선택 단계 1단). DM·그룹챗은 `spaces.list` 후보 조회
단계에서 `spaceType = "SPACE"` 필터로 이미 제외된다. DB checkpoint: `google-chat/google_chat_messages`
단일 커서(스페이스가 하나라 여러 채널을 가로질러 갱신할 필요가 없다).

### 증분 전략 — createTime 서버사이드 strict 필터

`filter=createTime > "{checkpoint RFC-3339}"` + `orderBy=createTime ASC`로 서버가 직접 걸러
오름차순으로 내려준다. Slack의 히스토리 풀스캔도, Discord처럼 커서를 snowflake로 변환하는 단계도
필요 없다 — 대화 아키타입 3종 중 증분 구현이 가장 단순하다. `pageToken`으로 이어받는 페이지도 이미
checkpoint 이후로 필터링된 결과라 클라이언트 쪽 경계 필터링이 불필요하다.

Slack·Discord와 마찬가지로 **스페이스 전체를 모으지 않고 페이지마다 발행한다**. 발행 배치가 스페이스
크기에 비례하면 수년치 스페이스의 초기 수집이 `EventPublisher`의 단일 confirm 타임아웃(10초)에 걸려
재시도해도 계속 실패한다 — 페이지 크기(1000)가 곧 발행 배치 상한이 된다. People API 보강은 페이지마다
호출해도 sender 단위 TTL 캐시가 흡수해 호출 수가 페이지 수에 비례하지 않는다.

**checkpoint는 전체 성공 후 한 번만 전진한다**(전 페이지의 최대 `occurredAt`). 중간 페이지 발행이
실패하면 예외가 전파돼 갱신에 도달하지 못하므로, 전량 축적하던 때와 같은 보증("전체 성공 후 전진")이
그대로 유지된다.

checkpoint가 없으면(초기 수집) `filter` 파라미터 자체를 생략해 전체 히스토리를 대상으로 한다.

### occurredAt 기준

메시지 `createTime`(RFC-3339). 편집된 메시지의 `lastUpdateTime`은 `filter`가 지원하지 않아
재수집 대상 판별에 쓰지 못한다 — 편집 추적은 대화 아키타입 공통 미지원 과제로 남는다(Teams만
정렬 기반 조기 종료 덕에 예외적으로 지원한다).

### Rate Limiting

호출마다 고정 100ms 딜레이(Cloud 프로젝트당 60초 3,000요청 쿼터 — 사용자 수와 무관하게 앱 전체가
공유하므로 보수적으로 시작한다). 429 응답에는 Discord처럼 재시도 대기 시간을 알려주는 필드가 없어
문서 권고대로 지수 백오프(`min((2^n)+jitter, max)`, 최대 5회)로 재시도한다.

### Tradeoff & 예상 문제점

#### 스페이스 1개 제한

프로젝트 하나에 스페이스 하나만 연결할 수 있다(A4 선택 단계는 단계당 단일 선택).

- **문제**: 팀이 여러 스페이스를 쓰면 하나만 수집되고 나머지는 놓친다.
- **방법 선택 이유**: 전체 자동 수집(Slack형)은 사용자 인증이라 `spaces.list`가 프로젝트와 무관한
  회사 전체 스페이스·DM까지 돌려줘 개인정보 위험이 있다. 다중 선택 지원은 A4 인터페이스 확장(공용
  코드 변경)이라 커넥터 PR 범위를 벗어난다 — 필요해지면 별도 안건으로 다룬다
  (`docs/google-chat-integration.md` §3 참고).

#### 자격증명이 만료되는 사용자 토큰 — Jira와 같은 모양

수집은 봇 토큰이 아니라 DB에 저장된 프로젝트별 사용자 access token(JSON, ~1시간 만료)으로 한다.

- **문제**: Discord와 반대로, 이 access token은 주기적으로 갱신돼야 한다. 웹훅 경로로 도는 증분
  수집에서 죽은 토큰으로 401을 낼 위험이 있다.
- **방법 선택 이유**: backend(`GoogleChatTokenService`)가 갱신을 전담하고, pipeline-worker는 내부
  토큰 API(`ensure`)로만 위임한다 — Jira와 동일한 구조다. 이 경로가 정상 동작하려면 webhook 토큰
  확보 로직이 Jira 전용에서 provider 일반화로 먼저 바뀌어야 했다(선행 PR, `docs/google-chat-integration.md` §2).
  Google 갱신 응답은 refresh_token을 다시 주지 않아(회전하지 않음) 기존 값을 보존해야 하는 점이
  Jira(회전형)와 정반대라, 코드를 그대로 복사하면 조용히 깨진다.

#### 메시지 URL이 permalink가 아니라 리소스 이름 원문

`properties.url`은 `message.name`(`spaces/{space}/messages/{id}`)을 그대로 쓴다.

- **문제**: 다른 소스(Discord의 딥링크, Teams의 `webUrl`)와 달리 클릭해서 원본으로 이동할 수 없다.
- **방법 선택 이유**: 실측 확인(2026-08-09) — Message 리소스 응답에 permalink류 필드가 아예 없다
  (Space에는 `spaceUri`가 있지만 메시지 단위 딥링크는 없음). URL을 지어낼 근거가 없으므로 결정적·
  고유한 리소스 이름을 그대로 자연키로 쓴다(`docs/google-chat-integration.md` §11).

#### actor 이름·이메일 확보에 People API 보강이 필요

Chat API 응답만으로는 `actor.name`이 항상 비고 `actor.email`도 없다 — 사용자 인증으로는
`Message.sender`에 `name`(id)·`type`만 오고 `displayName`은 오지 않는다(실측 및 공식 문서 확인,
`docs/google-chat-integration.md` §7).

- **문제**: People API(`people.googleapis.com`, `directory.readonly` scope)를 별도로 호출해야
  하고, 매 메시지마다 부르면 비용이 크다.
- **방법 선택 이유**: Slack의 `users.list` 전체 캐싱과 같은 목적이지만, People API에는 조직 전체를
  한 번에 내려주는 API가 없어(권한 범위상) 메시지에 실제로 등장한 sender만 지연 조회한다 —
  sender id 단위 TTL 캐시(`app.google-chat.person-cache-ttl`, 기본 30분) → 캐시 미스만
  `people.getBatchGet`(최대 200개/호출)으로 묶어 조회. 조회 실패한 sender는 그 실행에서만 이름·
  이메일 null로 두고 캐시하지 않아 다음 실행에서 재시도된다. Slack(`users.list`)처럼 이름·이메일을
  둘 다 확보하지만, Discord는 봇 토큰 모델이라 타인의 이메일 자체를 얻을 수단이 없어 이름만 남는다
  (`docs/discord-integration.md` §0).
