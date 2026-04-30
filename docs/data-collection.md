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

- `sort=updated&direction=desc` (최신순 정렬)
- `per_page=100` (GitHub API 최대값)
- checkpoint 이전 항목이 나오면 이후 페이지도 모두 이전임이 보장되므로 **조기 종료**

### PR 수집 상세

`/pulls?state=closed`로 closed PR 전체를 가져온 뒤 클라이언트에서 `merged_at != null` 필터링한다.
(GitHub `/pulls` API는 `merged_at` 서버사이드 필터를 지원하지 않음)

각 PR에 속한 커밋 목록은 `/pulls/{pr}/commits`로 추가 수집해 `prNumber` 매핑을 구성한다 (PR당 1회 호출).
각 커밋의 변경 파일 목록(`files`)은 `/commits/{sha}`로 추가 수집한다 (커밋당 1회 호출).

### occurredAt 기준

| 타입 | occurredAt 기준 | fallback |
|------|----------------|---------|
| ChangeSet | `committed_at` | `authored_at` |
| PullRequest | `merged_at` | `created_at` |
| Communication (Issue) | `updated_at` | `created_at` |

### Rate Limiting

- 매 API 호출 후 300ms 고정 딜레이
- 응답 헤더 `X-RateLimit-Remaining` ≤ 10이면 `X-RateLimit-Reset` 시각까지 동적 대기

### Tradeoff & 예상 문제점

#### 커밋당 `/commits/{sha}` 개별 호출 (enrichCommits)

`files`(변경 파일 목록과 diff)는 `/commits` list API 응답에 포함되지 않는다. 커밋당 1회 개별 detail API 호출이 불가피하다.

- **문제**: 커밋 수가 많을수록 호출량과 시간이 선형 증가. 커밋 1,000개면 1,000번 호출 + 300ms × 1,000 ≒ 5분.
  초기 전체 수집 시 수천 개의 커밋이 있는 저장소에서는 수십 분이 걸릴 수 있다.
- **현재 선택 이유**: `files`의 diff는 LLM이 diffSummary를 생성하고 `MODIFIED` 관계를 구축하는 핵심 데이터다. 없으면 지식 그래프의 코어 기능이 동작하지 않는다.

#### PR 수집 — closed 전체 수집 후 클라이언트 필터

GitHub Search API(`/search/issues?is:pr is:merged`)를 사용하면 서버사이드에서 merged PR만 필터할 수 있지만, 현재 환경에서 422 오류로 사용 불가. `/pulls` API에는 `merged_at` 서버사이드 필터가 없다.

- **문제**: `updated_at > checkpoint`이지만 `merged_at <= checkpoint`인 PR — 이미 수집했지만 이후에 코멘트·리뷰가 달려 updated된 경우 — 을 API로 받아왔다가 버린다.
  오래된 PR에 코멘트가 활발한 저장소에서 불필요한 API 호출이 증가할 수 있다.
- **현재 선택 이유**: 동작하는 유일한 방법. `closed` → `merged_at`으로 2단계 필터하므로 정확성은 보장된다.

#### PR-커밋 관계 구축 — `/pulls/{pr}/commits` (PR당 1회 호출)

- **문제**: PR 100개 = commits 엔드포인트 100번 추가 호출. 초기 전체 수집 시 PR이 많은 저장소에서 호출량 급증.
- **현재 선택 이유**: 커밋 메시지에 `PR #123` 텍스트가 없으면 `CONTAINS` 관계가 유실된다. API 기반으로 하면 squash/rebase merge를 포함해 100% 정확한 관계 구축이 가능하다. 증분 수집에서 수집 대상 PR이 적어 허용 범위.

#### Issues list가 PR 포함

GitHub `/issues` API는 PR도 반환한다. `GitHubNormalizer`에서 `pull_request` 키가 있는 항목을 필터링해 버린다.

- **문제**: PR 데이터를 이슈 엔드포인트에서도 받아오지만 정규화 시 폐기 → 불필요한 데이터 전송.
- **현재 선택 이유**: GitHub API에 `is_issue=true` 같은 서버사이드 필터가 없다. API 특성상 불가피.

#### 전체 데이터 메모리 누적

commits, PRs, issues 전체를 메모리에 쌓은 뒤 반환한다.

- **문제**: 초기 전체 수집 시 수천 개 커밋이 있는 저장소에서 OOM 가능성.
- **현재 선택 이유**: 구조 단순성 유지. Jira처럼 채널별 즉시 publish + 202 응답으로 전환하면 해결 가능하나 GitHub·Slack은 아직 미전환.

### 웹훅 기반 증분 수집 _(계획)_

폴링은 초기 전체 수집과 정기 배치에 적합하지만, 새 PR이 머지되는 즉시 반응하려면 API를 반복 호출해야 한다. 웹훅은 GitHub가 이벤트 발생 시점에 직접 push하므로 지연 없이 증분 처리가 가능하다.

#### 대상 이벤트

| 웹훅 이벤트 | 조건 | 처리 대상 |
|------------|------|---------|
| `pull_request.closed` | `merged == true` | PullRequest + 소속 ChangeSet 관계 보강 |

커밋(`push` 이벤트)과 이슈(`issues` 이벤트)는 폴링으로 커버하거나 향후 추가.

#### PR 머지 처리 흐름

```
[GitHub] pull_request.closed (merged=true)
    │
    ▼
1. GET /repos/{owner}/{repo}/pulls/{prNumber}
   → PR detail 수집 (merge_commit_sha, merged_at, title, body 등)
    │
    ▼
2. PullRequest NormalizedEvent 생성 → RabbitMQ 발행 (upsert)
    │
    ▼
3. GET /repos/{owner}/{repo}/pulls/{prNumber}/commits
   → PR에 속한 커밋 sha 목록 수집
    │
    ▼
4. 각 commitSha에 대해:
    ├─ [ChangeSet 이미 존재] → prNumber만 refs에 추가, CONTAINS 관계 보강 이벤트 발행
    └─ [ChangeSet 없음]
         ├─ 옵션 A: 최소 ChangeSet stub 생성 (sha만 포함) → 이후 push 이벤트 또는 폴링에서 채움
         └─ 옵션 B: GET /commits/{sha} 호출 → 파일 diff 포함 전체 ChangeSet 생성
```

#### ChangeSet 존재 여부 분기 판단

ai-engine이 이벤트를 처리할 때 Neo4j에서 sha로 ChangeSet 노드를 조회한다. pipeline-worker에서는 분기를 판단하지 않고 **항상 ChangeSet 이벤트와 prNumber refs를 함께 발행**한다. ai-engine의 upsert 로직이 이미 있으면 관계만 추가, 없으면 노드를 생성한다.

#### 폴링과의 관계

웹훅은 폴링을 **대체하지 않고 보완**한다.

| 역할 | 담당 |
|------|------|
| 초기 전체 수집 | 폴링 |
| 웹훅 수신 전 기간 복구 (서비스 다운 중 발생한 이벤트) | 폴링 |
| 실시간 증분 | 웹훅 |

폴링과 웹훅이 동시에 같은 PR을 처리해도 ai-engine의 upsert로 중복 노드 생성 없이 처리된다.

---

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
- **현재 선택 이유**: `created` 기준으로만 수집하면 기존 이슈의 상태 변경이 그래프에 반영되지 않는다. 변경 이력 추적이 이 프로젝트의 핵심 목적이므로 중복 발행이 누락보다 낫다. ai-engine의 upsert 처리가 전제 조건.

#### `app.jira.max-pages-per-run` 상한 — 처리 지연

한 번 실행에서 처리 못 한 페이지는 다음 호출까지 발행이 미뤄진다.

- **문제**: 초기 전체 수집 또는 대규모 업데이트 배치 직후에 여러 번 호출해야 완료된다. 스케줄링 없이 수동 호출하면 "언제 다 처리되는지" 알기 어렵다.
- **현재 선택 이유**: 상한 없이 처리하면 페이지당 200ms × 수백 페이지 = 수 분 블로킹. 다른 타입(GitHub, Slack) 수집을 차단한다. 페이지 단위 checkpoint 갱신으로 중단 재개가 가능해 반복 호출로 완료할 수 있다.

#### `expand=changelog` 요청 — 미사용 데이터 전송

`JiraRawService.fetchSearchPage`에서 `"expand", "changelog"`를 요청하지만 `JiraNormalizer`가 changelog를 사용하지 않는다.

- **문제**: 응답 크기가 불필요하게 커진다. changelog에는 이슈 필드 변경 이력이 포함되어 페이로드가 수배 증가할 수 있다. 페이지당 처리 시간이 늘어나 `max-pages-per-run` 상한에 더 빨리 도달한다.
- **현재 선택 이유**: 향후 "언제 상태가 바뀌었는지" 등 변경 이력 분석 기능 추가를 위해 유지 중. 당장 필요 없으면 제거해도 무방하나 보류.

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

### occurredAt 기준

메시지 `ts` (Unix epoch 소수 문자열 → `Instant` 변환).

### Rate Limiting

엔드포인트별 고정 딜레이:
- `conversations.list`: 3,000ms
- `conversations.history` · `conversations.replies`: 1,200ms

### Tradeoff & 예상 문제점

#### `conversations.history`에 `oldest` 파라미터 없음 — 채널 전체 순회

`fetchAllMessages`의 URI에 `oldest=lastScannedAt`이 없다. 매번 채널의 전체 메시지 히스토리를 최신순으로 cursor 페이지네이션으로 순회한다.

- **문제**: 채널 메시지가 수만 건인 경우 checkpoint 시각에 도달할 때까지 수십 페이지를 API로 가져온다. 대형 워크스페이스에서 수집 시간과 API 호출량이 선형 증가하며 현실적으로 수 시간이 걸릴 수 있다. 더 심각한 문제는 **조기 종료 로직이 없다**는 점 — checkpoint 이전 메시지가 나와도 루프가 계속 돌아 채널 전체 히스토리를 끝까지 받아온다.
- **현재 선택 이유**: `threadCandidates` 수집을 위해 checkpoint 이전 메시지도 `latest_reply` 체크가 필요하다. 단순히 `oldest` 파라미터를 추가하면 오래된 스레드에 달린 새 reply를 놓친다는 트레이드오프. 완전한 해결은 `oldest`로 서버 필터 + 별도 "활성 스레드 목록 유지" 구조가 필요하나 미구현.
- **개선 방향**: checkpoint 이전 메시지에서 `latest_reply > checkpoint`인 thread_ts만 별도 추적 → 해당 스레드만 replies 조회. 나머지 히스토리는 `oldest` 파라미터로 서버사이드 차단.

#### 채널 전체 메시지 메모리 누적

모든 채널의 messages·threads를 `channelData` List에 쌓아 반환한다.

- **문제**: 채널이 많고 메시지가 많을수록 메모리 사용량이 선형 증가. 채널 수 × 메시지 수 규모에서 OOM 가능성.
- **현재 선택 이유**: 구조 단순성 유지. 채널별 즉시 normalize·publish 구조로 전환하면 해결 가능하나 미구현.

#### User map 전체 수집 — 매 실행마다 반복

`fetchUserMap`으로 워크스페이스 전체 멤버를 매번 수집한다.

- **문제**: 수천 명 규모 워크스페이스에서 `users.list`를 여러 페이지로 호출. 멤버 수 / 200 페이지 × 1,200ms 딜레이 추가.
- **현재 선택 이유**: 메시지에는 userId만 있어 displayName·email 보강이 필요하다. 인메모리 캐시(GitHubRawService의 `userProfileCache` 방식)로 개선 가능하나 현재는 실행당 1회 전체 수집.

#### 모든 채널 무조건 수집

채널 필터링 없이 워크스페이스 전체 채널을 수집한다.

- **문제**: 수백 개 채널이 있는 대형 워크스페이스에서 conversations.list 페이지 순회 + 채널당 history 호출로 수집 시간이 채널 수에 비례해 증가한다.
- **현재 선택 이유**: 어떤 채널에 관련 맥락이 있을지 사전에 알 수 없다. 채널 whitelist 설정으로 제한 가능하나 현재 미구현. 워크스페이스 규모가 작은 초기에는 허용 범위.
