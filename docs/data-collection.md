# 데이터 수집 전략

pipeline-worker가 각 플랫폼에서 데이터를 수집하는 방법과 API 한도 관리 전략을 정리한다.

---

## 공통 원칙

- **증분 수집**: checkpoint에 기록된 마지막 수집 시각 이후 데이터만 가져온다. 재시작해도 중복 발행 없음.
- **occurredAt 기준 checkpoint 갱신**: 수집 시각(`Instant.now()`)이 아닌 이벤트 실제 발생 시각을 기준으로 갱신한다. 발행하지 못한 이벤트가 있어도 checkpoint가 앞으로 이동하지 않아 누락이 없다.
- **원자적 checkpoint 저장**: `.tmp` 파일에 쓴 뒤 `Files.move(ATOMIC_MOVE)`로 교체 — 중간 실패 시 오염 방지.

---

## GitHub

### 수집 대상

| 타입 | 엔드포인트 | checkpoint 필드 |
|------|-----------|----------------|
| Commit | `GET /repos/{owner}/{repo}/commits` | `commitsScannedAt` |
| Pull Request | `GET /repos/{owner}/{repo}/pulls?state=closed` | `pullRequestsScannedAt` |
| Issue | `GET /repos/{owner}/{repo}/issues?state=all` | `issuesScannedAt` |

타입별 checkpoint가 독립적이라 재시작 시 완료된 타입은 건너뛴다.

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

#### GitHub normalize 경로의 메모리 누적

normalize 경로는 PR, commit, issue를 페이지 단위로 처리한다.

- **동작**: normalize 응답은 `202 {"queued": N}`이며, 내부에서도 raw 전체와 `NormalizedEvent` 전체를 한 번에 누적하지 않는다.
- **남는 데이터**: PR-commit 관계 보강을 위해 실행 동안 `sha → prNumber` 맵은 유지한다. 이 맵은 PR raw 전체보다 작고 commit `refs.prNumber` 보강에 필요하다.
- **raw endpoint**: `/api/v1/raw/github`는 필드 확인용 샘플로 동작하며 PR/commit/issue 1페이지만 반환한다.

## Jira

### 수집 대상

Jira 이슈 전체를 JQL로 조회한다.

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

- **문제**: ai-engine이 동일 Jira key의 이벤트를 upsert로 처리하지 않으면 노드가 중복 생성된다.
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

`conversations.replies` 호출 시 `oldest=lastScannedAt` 파라미터를 붙여 서버사이드 필터링한다.

수집 대상(발행할 메시지)과 reply 확인 대상(오래된 스레드 포함)을 `ChannelMessages` record로 분리해 처리한다.
normalize 경로는 `conversations.history`를 page 단위로 가져오고, 해당 page의 메시지와 thread replies를 정규화·발행한다.
Slack checkpoint는 채널별로 갱신하지 않고 전체 실행 중 최대 `occurredAt`을 마지막에 한 번 갱신한다.

### occurredAt 기준

메시지 `ts` (Unix epoch 소수 문자열 → `Instant` 변환).

### Rate Limiting

엔드포인트별 고정 딜레이:
- `conversations.list`: 3,000ms
- `conversations.history` · `conversations.replies`: 1,200ms

### Tradeoff & 예상 문제점

#### `conversations.history`에 `oldest` 파라미터 없음 — 채널 히스토리 순회

`fetchHistoryPage`의 URI에 `oldest=lastScannedAt`이 없다. 매번 채널의 메시지 히스토리를 최신순으로 cursor 페이지네이션으로 순회한다.

- **문제**: 채널 메시지가 수만 건인 경우 checkpoint 시각에 도달할 때까지 수십 페이지를 API로 가져온다. 대형 워크스페이스에서 수집 시간과 API 호출량이 선형 증가하며 현실적으로 수 시간이 걸릴 수 있다. 더 심각한 문제는 **조기 종료 로직이 없다**는 점 — checkpoint 이전 메시지가 나와도 루프가 계속 돌아 채널 전체 히스토리를 끝까지 받아온다.
- **방법 선택 이유**: `threadCandidates` 수집을 위해 checkpoint 이전 메시지도 `latest_reply` 체크가 필요하다. 단순히 `oldest` 파라미터를 추가하면 오래된 스레드에 달린 새 reply를 놓친다.

#### Slack normalize 경로의 메모리 누적

normalize 경로는 채널의 history page 단위로 처리한다.

- **동작**: normalize 응답은 `202 {"queued": N}`이며, 내부에서도 workspace 전체 raw와 `NormalizedEvent` 전체를 한 번에 누적하지 않는다.
- **남는 데이터**: `users.list` 결과인 user map과 `conversations.list` 결과인 채널 목록은 실행 동안 유지한다.
- **raw endpoint**: `/api/v1/raw/slack`은 필드 확인용 샘플로 동작하며 첫 채널의 history 1페이지와 해당 page의 thread replies만 반환한다.

#### User map 전체 수집 — 매 실행마다 반복

`fetchUserMap`으로 워크스페이스 전체 멤버를 매번 수집한다.

- **문제**: 수천 명 규모 워크스페이스에서 `users.list`를 여러 페이지로 호출. 멤버 수 / 200 페이지 × 1,200ms 딜레이 추가.
- **방법 선택 이유**: 메시지에는 userId만 있어 displayName·email 보강이 필요하다. user map은 실행당 1회 전체 수집한다.

#### 모든 채널 무조건 수집

채널 필터링 없이 워크스페이스 전체 채널을 수집한다.

- **문제**: 수백 개 채널이 있는 대형 워크스페이스에서 conversations.list 페이지 순회 + 채널당 history 호출로 수집 시간이 채널 수에 비례해 증가한다.
- **방법 선택 이유**: 어떤 채널에 관련 맥락이 있을지 사전에 알 수 없어 전체 채널을 대상으로 한다.
