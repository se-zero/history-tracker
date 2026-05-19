# Backend Implementation Plan

## 작업 방식

backend 작업은 기능 단위 vertical slice PR로 진행한다. 각 PR은 필요한 migration, domain/entity, repository, service, controller/API, dto, test, 설정 변경을 함께 포함한다.

## 추천 순서

| 순서 | 브랜치 예시 | 작업 내용 | 포함 범위 |
|---:|---|---|---|
| 1 | `codex/backend-auth-github-foundation` | 인증/GitHub App 기반 | Spring Security/JPA/Flyway 의존성, 공통 패키지, `users`, `refresh_tokens`, `github_installations` migration/entity/repository, JWT, GitHub authorize/callback, `/me`, refresh, logout |
| 2 | `codex/backend-projects` | 프로젝트 관리 | `projects` migration/entity/repository, Project CRUD API, owner 검증, soft delete, 프로젝트명 중복 제약 |
| 3 | `codex/backend-github-integrations` | GitHub repo 연동 | `integrations` migration/entity/repository 중 GitHub 경로 우선, installation 검증, installation repository 목록 조회 API, 프로젝트당 GitHub repo 1개 연결 API, provider별 CHECK 제약 기본 |
| 4 | `codex/backend-optional-integrations` | Slack/Jira 연동 | AES-GCM 암호화 설정, Slack/Jira token 저장, provider별 request/validation, integration 응답 정리 |
| 5 | `codex/backend-conversations` | 대화/Q&A 전환 | `conversations`, `messages` migration/entity/repository, Conversation/Message API, 기존 Query API 제거, ai-engine 호출 로직 이전 |
| 6 | `codex/backend-pipeline-shared-tables` | pipeline 공유 DB 계약 | `webhook_deliveries`, `checkpoints` migration/entity/repository, 중복 webhook claim용 제약, checkpoint 복합 PK |
| 7 | `codex/backend-installation-token-cache` | GitHub installation token 관리 | `InstallationTokenService`, token 암호화 저장/갱신, 만료 5분 전 재발급, row lock 또는 conditional update. 3번 PR의 repository 목록 조회는 캐시 없이 발급하고, 이 단계에서 공통 캐시 경로로 합친다 |
| 8 | `codex/backend-user-lifecycle` | 사용자 탈퇴/복구 | user soft delete, grace period 복구, refresh token 폐기, purge scheduler, owner 리소스 정리 정책 |

## Flyway 규칙

- 개발/배포 모두 DB 스키마는 Flyway migration으로 관리한다.
- JPA `ddl-auto`는 `validate`를 사용한다.
- 기능 PR마다 필요한 migration을 추가한다.
- `main` 또는 `develop`에 머지된 migration 파일은 수정하지 않는다.
- 이미 머지된 스키마 변경은 새 migration으로 반영한다.
- 머지 전 개인 브랜치의 migration은 PR 안에서 수정 가능하다.

## 기존 Query 코드 처리

기존 Query 패스스루 코드는 대화 API를 구현하는 5번 PR에서 제거하거나 이전한다.

| 현재 파일 | 처리 |
|---|---|
| `controller/QueryController.java` | 삭제, `conversation/controller/ConversationController.java`로 대체 |
| `service/QueryService.java` | `conversation/service/MessageService.java`로 통합 |
| `dto/QueryRequest.java` | `conversation/dto/CreateMessageRequest.java`로 대체 |
| `dto/QueryResponse.java` | `conversation/dto/MessageResponse.java`로 대체 |
| `config/AiEngineConfig.java` | 유지 |

