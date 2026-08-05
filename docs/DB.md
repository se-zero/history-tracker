# DB 스키마

backend 서비스(`services/backend`)의 PostgreSQL 테이블 정의 및 관계를 기술한다.
마이그레이션 파일: `src/main/resources/db/migration/V1~V12`

---

## 테이블 관계도

> **선 종류**: 실선(`--`) = 식별 관계(자식 PK에 부모 FK 포함), 점선(`..`) = 비식별 관계
> **카디널리티**: `||` 정확히 1, `|o` 0 또는 1, `o{` 0 이상, `|{` 1 이상

```mermaid
erDiagram
    users {
        UUID id PK
        string provider
        string provider_user_id
        citext email
        timestamptz deleted_at
    }
    refresh_tokens {
        UUID id PK
        UUID user_id FK
        bytea token_hash
        timestamptz expires_at
    }
    github_installations {
        UUID id PK
        bigint installation_id
        UUID installer_user_id FK
    }
    projects {
        UUID id PK
        UUID owner_id FK
        string name
        int sort_order
    }
    integrations {
        UUID id PK
        UUID project_id FK
        string provider
        jsonb external_ref
        UUID installation_id FK
    }
    conversations {
        UUID id PK
        UUID project_id FK
        UUID user_id FK
        jsonb running_summary
        UUID summary_through_message_id
        timestamptz summary_updated_at
        bigint summary_version
    }
    messages {
        UUID id PK
        UUID conversation_id FK
        string role
        string content
    }
    checkpoints {
        UUID project_id PK,FK
        string provider PK
        string cursor_key PK
        timestamptz cursor_value
    }
    webhook_deliveries {
        UUID id PK
        string delivery_id
        UUID project_id FK
        string status
    }
    app_credentials {
        string provider PK
        bytea encrypted_credential
        timestamptz updated_at
    }

    users          ||..o{ refresh_tokens        : "1:N"
    users          ||..o{ github_installations  : "1:N"
    users          ||..o{ projects              : "1:N"
    users          |o..o{ conversations         : "0/1:N  (SET NULL)"
    projects       ||..o{ integrations          : "1:N"
    projects       ||..o{ conversations         : "1:N"
    projects       |o..o{ webhook_deliveries    : "1:N  (nullable FK)"
    projects       ||--o{ checkpoints           : "1:N  (식별)"
    github_installations |o..o{ integrations   : "0/1:N  (nullable FK)"
    conversations  ||..o{ messages              : "1:N"
```

---

## 테이블 정의

### `users`

소셜 로그인(OAuth) 사용자 계정. soft-delete(`deleted_at`)로 관리된다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | 사용자 고유 식별자 |
| `provider` | TEXT | NOT NULL | OAuth 제공자 (MVP: `github`) |
| `provider_user_id` | TEXT | NOT NULL | provider 발급 안정 식별자 |
| `email` | CITEXT | NOT NULL | OAuth 콜백 이메일 (대소문자 무시) |
| `display_name` | TEXT | | OAuth 프로필 이름 |
| `avatar_url` | TEXT | | OAuth 프로필 이미지 URL |
| `created_at` | TIMESTAMPTZ | NOT NULL | 사용자 최초 생성 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | row 수정 시각 |
| `deleted_at` | TIMESTAMPTZ | soft-delete 마커 | 탈퇴 시각. NULL이면 활성 사용자 |

**인덱스**
- UNIQUE `(provider, provider_user_id)` WHERE `deleted_at IS NULL`
- `(email)`
- `(deleted_at)` WHERE `deleted_at IS NOT NULL` — purge 후보 조회용

---

### `refresh_tokens`

JWT refresh token 저장. token 값은 해시(BYTEA)로만 보관한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | refresh token 레코드 ID |
| `user_id` | UUID | NOT NULL, FK → `users.id` CASCADE | 토큰 소유자 |
| `token_hash` | BYTEA | NOT NULL, UNIQUE | refresh token 해시 (평문 저장 금지) |
| `expires_at` | TIMESTAMPTZ | NOT NULL | 토큰 만료 시각 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 토큰 발급 시각 |

**인덱스**
- UNIQUE `(token_hash)`
- `(user_id)`

---

### `github_installations`

GitHub App 설치 정보. installation token은 암호화해 캐싱한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | installation 레코드 ID (내부) |
| `installation_id` | BIGINT | NOT NULL, UNIQUE | GitHub 발급 installation ID (외부) |
| `account_type` | TEXT | NOT NULL | `User` 또는 `Organization` |
| `account_login` | TEXT | NOT NULL | GitHub 사용자명 또는 조직명 |
| `installer_user_id` | UUID | NOT NULL, FK → `users.id` CASCADE | App을 처음 설치한 사용자 |
| `encrypted_installation_token` | BYTEA | | 60분 유효 installation token 캐시 (AES-GCM) |
| `installation_token_expires_at` | TIMESTAMPTZ | | 캐시된 token 만료 시각 |
| `created_at` | TIMESTAMPTZ | NOT NULL | App 설치 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | token 갱신 시각 |

**인덱스**
- UNIQUE `(installation_id)`
- `(installer_user_id)`

---

### `projects`

사용자가 생성한 분석 프로젝트.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | 프로젝트 ID |
| `owner_id` | UUID | NOT NULL, FK → `users.id` CASCADE | 프로젝트 소유자 |
| `name` | TEXT | NOT NULL | 프로젝트 표시 이름 |
| `description` | TEXT | | 프로젝트 설명 |
| `sort_order` | INTEGER | NOT NULL, DEFAULT 0 | 소유자 단위 수동 정렬 순서(오름차순). 드래그로 재정렬, 신규는 목록 끝 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 프로젝트 생성 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 메타데이터 변경 시각 |

**인덱스**
- `(owner_id)`
- `(owner_id, sort_order)` — 소유자 프로젝트를 정렬 순서로 조회
- UNIQUE `(owner_id, lower(name))` — 같은 사용자 내 프로젝트명 중복 불가

---

### `integrations`

프로젝트에 연결된 외부 서비스 연동 정보. 서비스별 식별자는 `external_ref`(JSONB)에 저장한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | 연동 레코드 ID |
| `project_id` | UUID | NOT NULL, FK → `projects.id` CASCADE | 이 연동이 속한 프로젝트 |
| `provider` | TEXT | NOT NULL | 외부 시스템 종류. 유효값은 앱(`IntegrationProvider`)이 보증한다 |
| `external_ref` | JSONB | NOT NULL | provider별 식별자 묶음 |
| `installation_id` | UUID | FK → `github_installations.id` CASCADE, nullable | GitHub 연동 시 installation 참조 |
| `encrypted_credential` | BYTEA | nullable | provider별 자격증명 암호화 보관 (AES-GCM). 평문 포맷은 아래 참고 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 연동 등록 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 메타데이터 변경 시각 |

**CHECK 제약**
- `provider = 'github'` → `installation_id NOT NULL`, `encrypted_credential NULL`
- `provider <> 'github'` → `installation_id NULL`, `encrypted_credential NOT NULL`

provider 열거형 CHECK는 V12에서 제거했다 — 새 연동을 붙일 때마다 마이그레이션을 강제했기 때문이다.
자격증명 형태 제약은 provider 목록이 아니라 "installation 기반인가"로 표현돼 provider가 늘어도 그대로 성립한다.

**`external_ref` JSON 키**
- GitHub: `repository_id`, `repository_full_name`, `branch`(선택 — 지정하면 해당 브랜치로 수집을 스코프한다)
- Slack: `workspace_id`, `workspace_name`
- Jira: `cloud_id`, `site_name`, `project_key`, `project_name`(선택)
  - 최초 동의 직후에는 사이트·프로젝트를 아직 모르므로 `status`(`pending_selection`) 하나만 담긴다
  - 토큰 갱신이 영구 실패해 pending으로 되돌아온 경우는 기존 키를 유지한 채 `status`만 덧붙는다
    (재동의 시 자동 복원에 쓰인다 — 두 pending 상태는 고른 값의 유무로 구분한다)
  - `status` 값은 provider 중립(`pending_selection`)이다. 구 Jira 전용 값 `pending_project`도
    읽기에서 pending으로 인정해 저장된 행을 마이그레이션 없이 수용한다
  - 선택 단계의 키(`cloud_id`·`project_key` 등)는 provider의 `IntegrationSelectionFlow`가 선언한다 —
    pipeline-worker가 수집할 때 읽는 키와 같아야 하기 때문이다

**`encrypted_credential` 평문 포맷**

AES-256-GCM으로 암호화한다(`security.credentials.key`, base64 디코딩 후 32바이트).
저장 바이트는 `IV(12) + 암호문 + GCM tag(16)`를 이어 붙인 단일 배열이다.

| provider | 평문 |
|----------|------|
| `github` | NULL — installation token은 `github_installations.encrypted_installation_token`에 따로 캐싱한다 |
| `slack` | user access token 문자열 그대로 (`xoxp-...`) — 동의 URL이 `user_scope`를 쓰므로 응답의 `authed_user.access_token`을 저장한다 |
| `jira` | OAuth 토큰 JSON — `{"access_token": ..., "refresh_token": ..., "expires_at": ...}` |

Jira는 Atlassian refresh token이 회전하므로 갱신할 때마다 세 값이 통째로 교체된다.
GitHub과 달리 **만료 시각이 암호문 안에 있어** 만료 여부를 판정할 때도 복호화가 필요하다
(pipeline-worker는 이 판정을 할 수 없어 backend 내부 API에 토큰 확보를 위임한다).

**인덱스**
- UNIQUE `(project_id, provider)`
- `(installation_id)` WHERE `installation_id IS NOT NULL`
- `(external_ref->>'repository_full_name')` WHERE `provider = 'github'`

---

### `conversations`

AI 질의 대화 세션. 사용자가 탈퇴하면 `user_id`가 NULL로 유지된다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | 대화(스레드) ID |
| `project_id` | UUID | NOT NULL, FK → `projects.id` CASCADE | 대화가 속한 프로젝트 |
| `user_id` | UUID | FK → `users.id` SET NULL, nullable | 대화 생성자 (탈퇴 시 NULL로 익명화) |
| `title` | TEXT | | 대화 제목 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 대화 시작 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 마지막 메시지 추가 시각 |
| `running_summary` | JSONB | nullable | 최근 원문 이력보다 오래된 완성 대화 턴의 누적 요약 |
| `summary_through_message_id` | UUID | nullable | 누적 요약에 마지막으로 포함된 ASSISTANT 메시지 ID |
| `summary_updated_at` | TIMESTAMPTZ | nullable | 누적 요약 최종 갱신 시각 |
| `summary_version` | BIGINT | NOT NULL, DEFAULT 0 | 동시 요약 갱신 충돌 감지를 위한 CAS 버전 |

**인덱스**
- `(project_id, updated_at DESC)`
- `(user_id, updated_at DESC)` WHERE `user_id IS NOT NULL`
- GIN `(lower(title) gin_trgm_ops)` — 대화 검색(⌘K)의 제목 부분 일치 (pg_trgm, V10)

---

### `messages`

대화 내 개별 메시지.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | 메시지 ID |
| `conversation_id` | UUID | NOT NULL, FK → `conversations.id` CASCADE | 메시지가 속한 대화 |
| `role` | TEXT | NOT NULL, CHECK IN (`USER`, `ASSISTANT`, `SYSTEM`) | 작성자 종류 |
| `content` | TEXT | NOT NULL | 메시지 본문 |
| `metadata` | JSONB | nullable | ASSISTANT 응답 부가정보 — `structured`(요약·`evidence` 근거) 또는 질의 실패 마커(`fallback`, `error_type`) |
| `created_at` | TIMESTAMPTZ | NOT NULL | 메시지 생성 시각 |

**인덱스**
- `(conversation_id, created_at ASC)`
- GIN `(lower(content) gin_trgm_ops)` — 대화 검색(⌘K)의 본문 부분 일치 (pg_trgm, V10)

---

### `checkpoints`

pipeline-worker의 수집 커서 위치를 저장한다. `(project_id, provider, cursor_key)` 복합 PK.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `project_id` | UUID | PK 일부, FK → `projects.id` CASCADE | 체크포인트가 속한 프로젝트 |
| `provider` | TEXT | PK 일부, CHECK IN (`github`, `jira`, `slack`) | 외부 시스템 종류 |
| `cursor_key` | TEXT | PK 일부 | 수집 종류 (`github_commits`, `github_pull_requests` 등) |
| `cursor_value` | TIMESTAMPTZ | NOT NULL | 마지막으로 처리한 cursor 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 마지막 갱신 시각 |

---

### `webhook_deliveries`

웹훅 중복 처리 방지용 수신 기록.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `id` | UUID | PK | delivery 레코드 ID |
| `delivery_id` | TEXT | NOT NULL, UNIQUE | GitHub `X-GitHub-Delivery` 헤더 값 (중복 처리 차단 키) |
| `project_id` | UUID | FK → `projects.id` CASCADE, nullable | 매칭된 프로젝트 (매칭 실패 시 NULL) |
| `status` | TEXT | NOT NULL DEFAULT `IN_PROGRESS`, CHECK IN (`IN_PROGRESS`, `PROCESSED`, `FAILED`) | 처리 상태 |
| `received_at` | TIMESTAMPTZ | NOT NULL | 웹훅 수신 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 상태 변경 시각 |
| `last_error` | TEXT | nullable | 처리 실패 시 마지막 에러 메시지 |

**인덱스**
- `(project_id, received_at DESC)` WHERE `project_id IS NOT NULL`

---

### `app_credentials`

앱 수준(프로젝트·사용자 무관) 외부 서비스 자격증명. 현재 유일한 행은 Atlassian 봇 계정의
OAuth 토큰으로, Jira 개인정보 보고 배치가 사용한다. refresh token이 갱신마다 회전해
새 값을 저장해야 하므로 환경변수가 아니라 DB에 둔다. 어떤 테이블과도 FK 관계가 없다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `provider` | TEXT | PK | 자격증명 소유 서비스 (`ATLASSIAN`) |
| `encrypted_credential` | BYTEA | NOT NULL | AES-GCM 암호화된 토큰 묶음 (access + refresh + 만료 시각) |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 마지막 갱신 시각 (토큰 회전마다 갱신) |
