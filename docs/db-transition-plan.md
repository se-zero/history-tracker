# DB 전환 계획

pipeline-worker의 현재 webhook 구현은 backend/DB가 아직 없다는 전제에서 동작하는 임시 구조다.
DB가 생기면 사용자/프로젝트별 연동 정보와 webhook delivery 상태를 DB 기준으로 조회/저장하도록 전환한다.

## 전환 대상

### 1. 프로젝트 연동 정보

현재 기본 구현:

- `NoopProjectIntegrationResolver`
- project를 찾지 못하면 webhook 요청에 `404` 반환

DB 전환 후:

- GitHub webhook payload의 `repository.full_name`, `repository.id`, `installation.id`로 project를 조회한다.
- 조회한 project의 GitHub/Jira/Slack 연동 정보를 읽어 `ProjectCollectionContext`를 만든다.
- GitHub/Jira/Slack credential은 `application.yaml`에 두지 않고 DB 또는 secret 저장소에서 가져온다.

예상 흐름:

```text
GitHub webhook payload
  -> repository / installation 식별자 추출
  -> DB에서 project 조회
  -> DB에서 provider별 integration 조회
  -> ProjectCollectionContext 생성
  -> PipelineService.collectIncremental(context)
```

### 2. Webhook delivery 중복 처리

현재 기본 구현:

- `FileWebhookDeliveryStore`
- `webhook-deliveries.json` 파일에 delivery 상태 저장
- 재시작 시 남아 있는 `IN_PROGRESS` 항목은 stale로 보고 제거

DB 전환 후:

- 파일 기반 구현과 `app.webhook.delivery-store.*` 설정을 제거한다.
- DB 테이블에 GitHub delivery id와 처리 상태를 저장한다.
- `tryClaim`은 insert conflict 또는 unique constraint로 중복을 막는다.

예상 테이블:

```text
webhook_deliveries
- provider
- delivery_id
- project_id
- status: IN_PROGRESS | PROCESSED | FAILED
- received_at
- updated_at
```

권장 처리:

```text
tryClaim:
  INSERT(provider, delivery_id, project_id, status=IN_PROGRESS)
  unique conflict면 false

markProcessed:
  UPDATE status=PROCESSED

markFailed:
  UPDATE status=FAILED
```

DB 구현이 유일한 구현체로 확정되면 임시 파일 구현뿐 아니라 인터페이스도 삭제한다.

삭제 대상:

- `WebhookDeliveryStore`
- `FileWebhookDeliveryStore`
- `app.webhook.delivery-store.*` 설정

대체 구조:

```text
GitHubWebhookService
  -> WebhookDeliveryRepository
  -> DB
```

`GitHubWebhookService`는 `WebhookDeliveryRepository`를 직접 의존한다.

### 3. Checkpoint

현재 기본 구현:

- `FileCheckpointManager`
- 전역 `checkpoint.json` 파일 사용

DB 전환 권장:

- checkpoint도 project 단위로 DB에 저장한다.
- 여러 사용자/프로젝트/worker 인스턴스가 생기면 전역 파일 checkpoint는 사용할 수 없다.

예상 테이블:

```text
collection_checkpoints
- project_id
- provider: github | jira | slack
- cursor_key: github_commits | github_pull_requests | github_issues | jira_updated | slack_messages
- cursor_value
- updated_at
```

단, checkpoint 전환은 webhook delivery DB 전환과 별도 작업으로 진행해도 된다.

## 남겨야 할 설정

DB 전환 후에도 `application.yaml`에 남길 수 있는 값:

- GitHub webhook secret
- GitHub/Jira/Slack API base URL
- rate limit 설정
- executor 종료 대기 시간

DB 전환 후 제거할 값:

- 파일 delivery store 설정
- 파일 checkpoint 설정
- 사용자/프로젝트별 credential

## 구현 순서 제안

1. backend DB에 project, integration, webhook delivery 테이블 추가
2. `ProjectIntegrationResolver`, `NoopProjectIntegrationResolver`를 삭제하고 DB 기반 project integration service/repository로 교체
3. `WebhookDeliveryStore`, `FileWebhookDeliveryStore`를 삭제하고 DB 기반 webhook delivery repository로 교체
4. webhook 수집이 project별 integration으로 실행되는지 검증
5. checkpoint를 project 단위 DB 저장으로 전환
