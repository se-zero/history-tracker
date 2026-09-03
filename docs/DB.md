# DB 스키마

backend 서비스(`services/backend`)의 PostgreSQL 테이블 정의 및 관계를 기술한다.
마이그레이션 파일: `src/main/resources/db/migration/V1~`

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
    github_installation_users {
        UUID installation_id PK,FK
        UUID user_id PK,FK
    }
    github_user_credentials {
        UUID user_id PK
        bytea encrypted_credential
    }
    user_provider_connections {
        UUID user_id PK,FK
        string provider PK
        timestamptz first_connected_at
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
    users          ||--|| github_user_credentials : "1:1 CASCADE"
    users          ||..o{ github_installations  : "1:N"
    users          ||--o{ github_installation_users : "1:N  (식별)"
    github_installations ||--o{ github_installation_users : "1:N  (식별)"
    users          ||--o{ user_provider_connections : "1:N  (식별, CASCADE)"
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
| `consent_terms_version` | TEXT | nullable | 가입 시 동의한 약관 버전 (V18). NULL이면 다음 로그인 때 동의 화면을 본다 |
| `consent_recorded_at` | TIMESTAMPTZ | nullable | 약관 동의 기록 시각 (V18) |
| `plan` | TEXT | NOT NULL, DEFAULT `FREE` | 요금제 (V19) |
| `free_query_count` | INTEGER | NOT NULL, DEFAULT 0 | FREE 플랜 질의 횟수 카운트 (V19) |

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
| `replaced_at` | TIMESTAMPTZ | | 회전으로 교체된 시각. NULL이면 아직 유효. 값이 있으면 재사용 탐지 대상 |

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
| `installer_user_id` | UUID | FK → `users.id` **SET NULL** | App을 처음 설치한 사용자 (기록용 — 접근권은 아래 `github_installation_users`가 갖는다) |
| `encrypted_installation_token` | BYTEA | | 60분 유효 installation token 캐시 (AES-GCM) |
| `installation_token_expires_at` | TIMESTAMPTZ | | 캐시된 token 만료 시각 |
| `created_at` | TIMESTAMPTZ | NOT NULL | App 설치 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | token 갱신 시각 |

**인덱스**
- UNIQUE `(installation_id)`
- `(installer_user_id)`

**`installer_user_id`가 소유권이 아닌 이유 (V17)**

설치는 **계정 단위**다 — 조직에 설치하면 그 조직 구성원 여럿이 같은 설치를 쓴다. 그런데 이 컬럼이
접근 기준이던 시절에는 로그인 동기화가 이 값을 매번 덮어써서, 팀원 둘이 같은 조직 설치를 쓰면
나중에 로그인한 사람이 설치를 가져가고 앞 사람은 404를 받았다. FK도 `CASCADE`라 **한 명의 탈퇴가
다른 사용자의 연동까지** 지웠다(`integrations.installation_id`가 이 테이블을 CASCADE 참조한다).

V17에서 접근권을 `github_installation_users`로 분리하고 이 FK를 `SET NULL`로 바꿨다.
이제 이 컬럼은 "최초 설치자" 기록일 뿐이며 **덮어쓰지 않는다.**

---

### `github_installation_users`

설치에 접근할 수 있는 사용자 (N:M). 등록 경로는 둘이다 — (1) 로그인 동기화 때 GitHub이 그
사용자에게 보여준 설치(`GET /user/installations`), (2) 설치 직후 콜백에 붙어 오는
`installation_id`(앱 JWT로 실존 확인 후 등록). (2)가 필요한 이유: GitHub 설치 목록 API는 접근
판정이 저장소 기반이라 **저장소 0개 설치를 목록에서 통째로 뺀다** — 막 조직에 설치한 관리자가
돌아오는 콜백이 유일한 등록 기회다(등록 전에 사용자 토큰으로 활성 멤버십을 확인해 위조
`installation_id`를 막는다). 같은 이유로 로그인 동기화의 prune(목록에 없는 멤버십 정리)은
목록에 없는 조직 멤버십을 지우기 전에 설치 실존(404면 앱 삭제 → 정리)과 활성 멤버십(사용자
토큰 — 멤버면 유지, 조직을 떠났으면 정리, 403 등 확인 불가면 그 행만 유지하고 다른 행의 정리는
계속)으로 판정한다. 멤버십 확인은 앱의 Organization Members(read) 권한을 요구한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `installation_id` | UUID | PK, FK → `github_installations.id` CASCADE | 설치 |
| `user_id` | UUID | PK, FK → `users.id` CASCADE | 접근 가능한 사용자 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 멤버십 등록 시각 |

**인덱스**
- PRIMARY KEY `(installation_id, user_id)`
- `(user_id)` — 사용자별 설치 목록 조회

사용자가 파기되면 멤버십 행만 CASCADE로 사라지고 **설치 행은 남는다** — GitHub 쪽 설치는 그대로이고,
다른 멤버가 계속 쓰거나 나중에 누가 다시 로그인하면 멤버십이 복원된다.

**단, 멤버가 하나도 남지 않는 경우가 있다.** 개인 설치의 유일한 사용자가 탈퇴하면 아무도 쓸 수 없는
행에 `account_login`(그 사람의 GitHub 사용자명)과 암호화된 installation token 캐시가 남는다.
지금은 의도적으로 남긴다 — GitHub 쪽 설치가 살아 있는 한 계정 단위로 재사용될 수 있고, 지웠다가
같은 설치가 다시 동기화되면 `installation_id` 유니크 충돌 경로가 생긴다. **멤버가 0이 된 설치를
정리할지는 열린 항목이다**(개인정보 관점에서는 지우는 편이 낫다).

---

### `github_user_credentials` (V21)

GitHub App **사용자** OAuth 토큰. 로그인 `exchangeCode` 직후 사용자당 1행. 레포 목록 ACL용.
수집용 설치 토큰(`github_installations.encrypted_installation_token`)과 별개.
`integrations.encrypted_credential`에도 넣지 않는다(GitHub 행은 credential NULL 제약).

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `user_id` | UUID | PK, FK → `users.id` CASCADE | 토큰 소유자 |
| `encrypted_credential` | BYTEA | NOT NULL | 사용자 OAuth 자격증명 (AES-GCM) |
| `created_at` | TIMESTAMPTZ | NOT NULL | 최초 저장 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 갱신으로 덮어쓴 시각 |

평문 JSON 키 이름은 Jira와 같다: `access_token`, `refresh_token`, `expires_at`,
`refresh_token_expires_at`. AES-GCM. 갱신 시 access·refresh 둘 다 회전하므로 둘 다 덮어쓴다.

Expire user authorization tokens ON 전제: access 8시간, refresh 6개월.
`exchangeCode`는 refresh/`expires_in`이 없으면 로그인을 실패시킨다.

---

### `user_provider_connections` (V19)

사용자가 한 번이라도 연동한 provider 이력. FREE 플랜은 연동을 해제·삭제해도 같은 provider를
재연동해 증분 수집을 다시 얻을 수 없어야 하므로, `integrations` 행 존재 여부가 아니라 이 이력으로
"이미 연동한 적 있는지"를 판정한다.

| 컬럼 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `user_id` | UUID | PK, FK → `users.id` CASCADE | 연동 이력의 소유자 |
| `provider` | TEXT | PK | 연동했던 provider 종류 |
| `first_connected_at` | TIMESTAMPTZ | NOT NULL, DEFAULT `now()` | 최초 연동 시각 |

V19 배포 시 그 시점까지 존재하던 `integrations` 행을 근거로 백필됐다(연동을 해제·삭제한
이력까지 소급하지는 않는다 — 배포 시점에 살아 있던 연동만).

**인덱스**
- PRIMARY KEY `(user_id, provider)`

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
| `incremental_enabled` | BOOLEAN | NOT NULL, DEFAULT TRUE | FALSE면 webhook 증분 수집을 막는다 (V19). FREE 플랜 연동 저장 시 false로 시작, PAID 전환 시 소유 연동 전체 true로 갱신 |
| `created_at` | TIMESTAMPTZ | NOT NULL | 연동 등록 시각 |
| `updated_at` | TIMESTAMPTZ | NOT NULL | 메타데이터 변경 시각 |

**CHECK 제약**
- `provider = 'github'` → `installation_id NOT NULL`, `encrypted_credential NULL`
- `provider <> 'github'` → `installation_id NULL`, `encrypted_credential NOT NULL`

provider 열거형 CHECK는 V12에서 제거했다 — 새 연동을 붙일 때마다 마이그레이션을 강제했기 때문이다.
자격증명 형태 제약은 provider 목록이 아니라 "installation 기반인가"로 표현돼 provider가 늘어도 그대로 성립한다.

**`external_ref` JSON 키**
- GitHub: `repository_id`, `repository_full_name`, `branch`(선택 — 지정하면 해당 브랜치로 수집을 스코프한다)
- Slack: `workspace_id`, `workspace_name`, `connected_user_id`(신규 연결 — Slack `authed_user.id`. 레거시 행에는 없음),
  `connect_method`(BYO 붙여넣기만 `"byo"`. OAuth·레거시는 키 없음. 값은 `SlackOAuthConnectFlow.CONNECT_METHOD_BYO`와 동일 문자열)
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
| `slack` | JSON `{"user_token": ..., "bot_token": ...}` — `user_token`은 `authed_user.access_token`, `bot_token`은 루트 `access_token`(없으면 null). 레거시 행은 user 토큰 평문이며 복호화 시 폴백한다. 재동의 때 승급하고 마이그레이션은 하지 않는다 |
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
| `provider` | TEXT | PK 일부, CHECK 제약 없음(V16에서 삭제 — 유효성은 애플리케이션의 `CollectionProvider` enum이 보증) | 외부 시스템 종류 |
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
