# Slack 마켓플레이스 등재 계획 — D 트랙

[public-readiness.md §0-3](public-readiness.md)의 B+C+D 병행 결정(2026-08-28 회의) 중 **D(마켓플레이스
등재 추진)의 실행 계획**이다. B(우리 앱, 느린 채로 유지)·C(BYO 앱 병기)는 이 문서의 범위가 아니며,
B가 D의 자격 모수(활성 워크스페이스 수)를 쌓는다는 의존 관계만 §8에서 다룬다.

근거는 두 갈래다 — Slack 공식 가이드라인·심사 절차 문서를 **2026-08-29에 재확인**했고
(맨 아래 「참고」), 현재 구현 상태는 backend·pipeline-worker 코드에서 직접 확인했다.
재확인에서 기존 기록과 어긋나는 것이 하나 나왔다: **등재 자격이 "활성 워크스페이스 5곳"이 아니라
10곳이다**(§2). public-readiness §0-3의 수치도 이번에 함께 정정했다.

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| Slack 안의 기능 | **`/why-code` 슬래시 커맨드** — 워크스페이스 안에서 자연어 질문 → GraphRAG 답변(ephemeral) | "do not include functionality in Slack"이 거부 사유. 우리 핵심 기능(질의)을 가장 얇게 노출하는 형태. 이름은 가이드라인의 고유 이름 권장에 맞춘다(2026-08-29 결정) |
| Assistant/Agent UI | **쓰지 않는다** | Assistant UI 앱은 "Slack 데이터를 저장하지 말 것(zero-copy)"이 명시 요건 — 그래프 저장 아키텍처와 정면 충돌. 슬래시 커맨드에는 이 요건이 없다 |
| 이벤트 수신 | **Events API 구독 — `app_uninstalled`·`tokens_revoked`** + 서명 검증 | 앱 제거 후에도 수집을 시도하고 데이터가 남는 것은 등재 여부와 무관하게 결함. 처리 순서 비보장이라 멱등으로 |
| 엔드포인트 위치 | **backend** — `/api/v1/slack/events`·`/api/v1/slack/commands` | 정리(연동 행·그래프 삭제)와 질의 프록시가 전부 backend 소관. nginx `/api/`가 이미 backend로 프록시한다. `/api/v1/webhook/` prefix는 pipeline-worker로 가므로 **쓰지 않는다** |
| 요청 인증 | `SLACK_SIGNING_SECRET` HMAC-SHA256 (v0 서명, 5분 타임스탬프 창) | 가이드라인 Security 절이 서명 검증을 요구(verification token은 deprecated) |
| OAuth 변화 | bot scope **`commands`** 추가 → 설치 시 bot 토큰이 함께 발급됨 | 슬래시 커맨드 등록에 필요. 최소 권한 — `chat:write`도 안 받는다(응답은 `response_url`로) |
| 자격증명 저장 | 평문 문자열(user 토큰) → **JSON 코덱 `{user_token, bot_token}` + 레거시 평문 폴백** | bot 토큰도 폐기 대상이라 저장해야 한다. Jira·Google Chat·Notion과 같은 패턴 |
| `/why-code` 사용 권한 | **연결한 사용자만** — `authed_user.id`를 `external_ref.connected_user_id`로 저장해 대조 | 답변에 GitHub·Jira 데이터가 섞인다. 워크스페이스 멤버십 ≠ 레포 접근권이므로 Slack 프라이버시 모델("Slack에서 못 보는 것을 앱이 보여주면 안 된다")의 역방향 위험을 게이팅으로 차단 |
| `/why-code` 질의 | **단발** — 대화(conversation)에 저장하지 않는다 | 웹 대시보드 대화와 섞이면 출처가 갈라진다. 시작은 기존 `/query` 프록시만 재사용(2026-08-29 결정) |
| 앱 제거·토큰 폐기 | **연동 해제와 동일** — provider 폐기 → 그래프 삭제 → 행·checkpoint 삭제 | 개인정보처리방침의 "연동 해제 시 삭제"와 맞춘다. 앱 제거는 가장 강한 철회 신호(2026-08-29 결정) |
| 리스팅 언어 | **이번 작업 범위 밖** | i18n 착수와 묶지 않는다. 리스팅·커맨드 응답은 지금 있는 언어로 두고, 언어 분리는 별도 작업 |
| 수집 경로 | **무변경** | user 토큰 수집·429 적응(`SlackPacing`)이 구·신 한도 양쪽에서 동작. 승인되면 한도만 올라간다 |
| user token `*:history` 유지 | 유지하고 **사유서로 방어** | 가이드라인이 "Real-time Search 같은 명확한 사용 사례"를 예외로 인정. 봇 토큰 전환은 별개 항목(등재 자격·한도를 바꾸지 못함 — public-readiness §0-3) |

## 1. 무엇이 걸려 있는가

- **rate limit 복구** — 공개 배포 앱의 `conversations.history`·`replies`는 1 req/min·15건인데,
  마켓플레이스 승인 시 Tier 3(50+ req/min·1,000건)로 복구된다. 초기 수집 11시간 → 수 분.
- **유료화 약관 마찰 해소** — 유료 구독(2026-08-26 결정)을 붙이면 B는 "상업적 배포는 마켓플레이스가
  유일한 채널"이라는 약관 조항과 충돌한다. 등재가 이 마찰을 구조적으로 없앤다.
- **거부 사유의 해소** — 현재 앱은 "Slack 안에 기능이 없다"는 명시적 부적격 사유에 걸린다.
  이는 설정이 아니라 제품 구조였고, `/why-code`가 그 구조를 바꾼다.

## 2. 등재 기준 대비 현재 갭 (2026-08-29 가이드라인 기준)

| 기준 | 현재 | 필요한 변경 |
|------|------|------|
| "do not include functionality in Slack" (부적격 사유) | ❌ 슬래시 커맨드·봇·App Home 없음 | `/why-code` 슬래시 커맨드 (§3) |
| `app_uninstalled`·`tokens_revoked` 처리 | 코드 완료, Events Request URL 미등록(S4). `tokens_revoked`는 `connected_user_id` 저장(S2) 전까지 레거시 행에 no-op | Events URL 등록(S4), 자격증명 키(S2) |
| 활성 워크스페이스 **10곳**(28일 내 사용, 샌드박스 제외) + 주간 활성 **10명** | ❌ 1곳 | B 트랙 실적 축적 (§8). ⚠️ 기존 기록(5곳)에서 **상향 확인됨** |
| user token `*:history` scope | ⚠️ `channels:history`·`groups:history` 사용 | 유지 + scope 사유서. **enhanced review 대상**임을 전제로 준비 (§7) |
| "export or backup message data" (부적격 사유) | ⚠️ 메시지 본문을 Neo4j `Communication.body`에 저장 | 리스팅 포지셔닝 + 개인정보처리방침 보강으로 방어. **최대 리스크** (§8) |
| AI 공시 | ❌ 없음 | Security & Compliance 항목에 모델·보존·테넌시·리전 공시, 랜딩·long description에 오답 가능성 고지 (§7) |
| LLM 학습 금지 | ✅ 학습 안 함(임베딩+RAG만) | 공시 문서에 명시만 하면 됨 |
| 무료 티어 90일 조항 | ⚠️ 90일 지난 메시지가 그래프에 남아 질의로 노출될 수 있음 | 해석 확인 필요 — **열린 리스크** (§8) |
| 리스팅 자산(아이콘·스크린샷 1600×1000·비디오 30~90초) | ❌ 없음 | 제작 (§7) |
| 랜딩 페이지(공개, 설치 경로, 방침 링크) | ⚠️ `/landing` 있음 — Slack 앱 전용 서술 없음 | Slack 앱 절 추가 (§7) |
| 지원 채널(로그인 없는 문의, 2영업일 응답) | ⚠️ 연락처는 있음(public-readiness 4-2 완료) | 공개 support 페이지로 정리 (§7) |
| 개인정보처리방침(수집·용도·보존·삭제 경로·연락처) | ⚠️ `/privacy` `#slack` 앵커 있음 | 보존 기간·삭제 요청 경로·AI 사용을 보강 (§7) |
| OAuth `state` | ✅ `OAuthStateService` 서명 state | 없음 |
| 토큰 암호화 저장 | ✅ `BACKEND_CREDENTIAL_KEY` 암호화 | 없음 |
| TLS 1.2+ | ✅ Cloudflare 엣지 종단 | 없음 |
| collaborator 등록(승인 후 유지 의무) | ❌ 1인 소유 | 앱 설정에서 추가 — 계정 확보 필요 |

심사 절차(2026-08-29 확인): **public distribution 활성화가 제출 선행 조건**이다. 예비 심사
최대 10영업일(반려 후 재제출 시 큐 리셋), 기능 심사 최대 10주(첫 피드백 후에는 큐 유지).
승인 후 기능 변경은 재심사 대상이고, 변경 검증용 **staging 앱**(published 앱 manifest 복제)을
쓰는 것이 공식 권장 절차다.

## 3. 제품 변경 1 — `/why-code` 슬래시 커맨드

사용자가 Slack 채널에서 `/why-code 결제 재시도 로직이 왜 이렇게 바뀌었어?`를 치면, 그 워크스페이스가
연결된 프로젝트의 그래프로 GraphRAG 질의를 돌리고 **ephemeral**(본인에게만 보이는) 메시지로 답한다.

### 흐름

```
Slack → POST /api/v1/slack/commands (form-encoded: team_id, user_id, text, response_url …)
  1. 서명 검증 (§4와 공용) — 실패 시 401
  2. 즉시 200 ack (3초 제한) — "찾는 중" ephemeral 텍스트
  3. 비동기:
     team_id → integrations(provider=slack, external_ref.workspace_id) 조회
     user_id ≟ external_ref.connected_user_id 게이팅
     → 프로젝트 확정 → 기존 질의 경로(AiEngineQueryClient) 재사용
     → response_url로 ephemeral 응답 POST (30분 유효, 토큰 불필요)
```

- **ai-engine은 무변경**이다. backend의 기존 `/query` 프록시 경로를 그대로 쓴다. **대화에 저장하지
  않는 단발 질의**다 — 웹 대시보드 대화와 출처가 갈라지지 않게 한다(2026-08-29 결정).
- ack 3초 제한 때문에 질의를 동기로 돌릴 수 없다(LLM 질의는 60초까지 걸린다 — `AiEngineConfig`
  read timeout). 비동기 실행 후 `response_url`로 밀어넣는 구조가 가이드라인 권장이기도 하다.
- 응답은 항상 ephemeral — 가이드라인이 "방해 최소화를 위해 ephemeral을 기본으로" 권장하고,
  답변에 비공개 소스 데이터가 섞이므로 채널 공개 응답은 프라이버시 모델 위반 소지가 있다.

### 게이팅 — 연결한 사용자만

답변은 프로젝트 그래프 전체(GitHub 커밋·PR·Jira·Slack)에서 나온다. 슬래시 커맨드는 워크스페이스의
**누구나** 칠 수 있으므로, 게이팅 없이는 레포 접근권 없는 워크스페이스 멤버가 비공개 레포의 커밋
메시지를 읽게 된다 — 0-1b(조직 설치에서 남의 레포 노출)와 같은 종류의 구멍을 Slack 쪽에 새로 여는 셈이다.

- 연결 시점에 `oauth.v2.access` 응답의 `authed_user.id`를 `external_ref.connected_user_id`로
  저장한다(현재는 `access_token`만 꺼내고 버린다 — `SlackOAuthAccessResponse.AuthedUser` 확장).
- 커맨드의 `user_id`와 대조해, 다르면 안내 ephemeral로 답한다("이 워크스페이스를 연결한 계정만
  사용할 수 있어요" + 서비스 링크). 가이드라인의 "unrecognized users를 우아하게 처리하라" 요건.
- **레거시 행에는 이 키가 없다** — 커맨드 처리 시점에 저장된 user 토큰으로 `auth.test`를 불러
  `user_id`를 지연 확인·백필한다. 재동의 없이 동작해야 하므로 마이그레이션 대신 지연 백필로 간다.

### 다중 매칭 — 같은 워크스페이스, 여러 프로젝트

연동 유니크 키가 `(project, provider)`라 같은 워크스페이스가 여러 프로젝트에 연결될 수 있다
(0-2 결정 — "여러 사람이 각자 개인 용도로"). `connected_user_id`까지 대조하면 대부분 1건으로
좁혀지지만, **한 사용자가 자기 프로젝트 여럿에 같은 워크스페이스를 연결한 경우**가 남는다.
1건이면 즉시 실행, 복수면 프로젝트 목록을 ephemeral로 보여주고 `/why-code [프로젝트명] 질문` 형태로
재시도하게 한다 — 자동 선택(최근 연결 우선 등)은 틀렸을 때 사용자가 알아챌 수 없어 배제했다.

### 커맨드 UX (가이드라인 요건 반영)

- 이름은 **`/why-code`** 로 고정한다(2026-08-29). 가이드라인이 권장하는 고유 이름에 가깝고 `/why`보다
  충돌 가능성이 낮다.
- `help`·빈 입력에는 사용법을, 질의 실패에는 행동 가능한 오류 메시지를 ephemeral로 답한다
  ("Oops!"류 금지 — 가이드라인 명시).
- hint·short description 텍스트를 커맨드 등록에 채운다.

## 4. 제품 변경 2 — Events API 라이프사이클

`app_uninstalled`(워크스페이스에서 앱 제거)·`tokens_revoked`(개별 토큰 폐기)를 구독한다.
지금은 관리자가 앱을 제거해도 우리가 모른다 — 연동 행이 남아 매 웹훅마다 죽은 토큰으로 수집을
시도하고, **수집된 데이터도 그대로 남는다.** 등재 여부와 무관하게 위생 결함이라 이 묶음(S1)을
가장 먼저 진행한다.

- `POST /api/v1/slack/events` — `url_verification` challenge 에코, 서명 검증(§3과 공용 필터),
  3초 안에 200 반환 후 비동기 처리(Slack은 실패 시 재시도하고, 계속 실패하면 구독을 끊는다).
- `app_uninstalled`: `team_id`로 `external_ref.workspace_id` 매칭되는 **모든** 연동 행을 찾아
  기존 해제 경로(`IntegrationService.disconnect`의 내부 — provider 폐기 → 그래프 삭제 → 행·checkpoint
  삭제)를 태운다. 원격 폐기는 이미 죽은 토큰이라 실패하는데, `SlackClient.ALREADY_REVOKED_ERRORS`
  재해석(0-1c에서 완료)이 성공으로 처리해 준다 — 이 경로에 새 코드가 필요 없다.
- `tokens_revoked`: 페이로드의 `oauth` 사용자 목록에 저장된 토큰의 사용자가 포함되면 해당 연동만
  같은 경로로 정리한다. bot 토큰 폐기는 §5의 bot 토큰에도 적용된다.
- **멱등이 계약이다** — 두 이벤트의 도착 순서는 보장되지 않고(공식 문서 확인) 중복 배달도 있다.
  행이 이미 없으면 조용히 200. "그래프 삭제가 멱등이라 재시도로 수렴"하는 기존 해제 설계가 그대로 맞는다.
- `SecurityConfig` 허용 목록에 두 경로를 추가한다(사용자 JWT 없음 — 서명이 유일한 인증).
  서명 검증은 **raw body** 기준이므로 `@RequestBody String`으로 받는다(GitHub 웹훅과 같은 모양).

**`app_uninstalled`·`tokens_revoked`는 연동 해제와 동일하게 그래프까지 지운다**(2026-08-29 결정).
개인정보처리방침이 "연동 해제 시 삭제"를 약속하고 있고, 앱 제거는 사용자가 표현할 수 있는 가장 강한
철회 신호다. 재설치 대비로 보존하지 않는다.

## 5. OAuth·자격증명 변경 — bot 토큰 도입

슬래시 커맨드를 등록하려면 앱에 bot user와 `commands` bot scope가 필요하다. 이 순간부터
`oauth.v2.access` 응답의 루트 `access_token`(bot, `xoxb-`)이 의미를 갖는다 — 현재 DTO는
`authed_user.access_token`(user, `xoxp-`)만 매핑한다.

- `SlackOAuthAccessResponse`에 루트 `access_token`·`authed_user.id` 매핑 추가.
- 자격증명을 `{user_token, bot_token}` JSON으로 저장하는 `SlackCredential`/`SlackCredentialCodec`
  도입 — Notion·Google Chat처럼 `integration.service`에 둔다(SPI 구현체를 leaf로 유지).
  **레거시 평문 폴백이 필수다**: JSON 파싱 실패 시 전체를 user 토큰으로 해석한다. 기존 행은
  재동의 때 새 형식으로 승급되며 데이터 마이그레이션은 하지 않는다.
- **pipeline-worker도 같은 폴백을 구현한다** — Slack 수집이 행 자격증명을 복호화해 user 토큰으로
  쓰므로, 새 형식 행에서 user 토큰을 꺼내는 코드가 없으면 수집이 깨진다. 배포 순서는 worker(읽기
  호환) 먼저, backend(쓰기 전환) 다음.
- `SlackCredentialLifecycle.revoke`는 두 토큰을 각각 `auth.revoke`하고 AND로 결합한다 —
  short-circuit으로 두 번째 호출이 생략되지 않게 지역 변수에 담아 결합한다(Discord에서 실제로
  났던 버그 — public-readiness 0-1c 수정 내용 참고).
- **해제해도 앱은 워크스페이스에 남는다.** 같은 워크스페이스를 다른 프로젝트가 쓰고 있을 수 있어
  `apps.uninstall`(앱 전체 제거 API)은 부르지 않는다. 우리 쪽 토큰 폐기가 해제의 전부이고, 앱
  제거는 워크스페이스 관리자의 행동 → `app_uninstalled` 이벤트(§4)로 돌아온다. 프론트
  `sourceCatalog`의 Slack `deletedData`에 이 비대칭("앱 자체는 Slack 관리 화면에서 제거")을 명시한다.

## 6. 수집 경로 — 변경 없음

`SlackPacing`이 고정 딜레이(구 한도 기준) + 429 적응(신 한도 흡수)으로 설계돼 있어
([data-collection.md](data-collection.md) Slack 절), 공개 배포 직후의 1 req/min 체제도 승인 후의
Tier 3 복구도 **설정 변경 없이** 흡수한다. 승인이 나면 429가 사라져 자연히 빨라진다.

## 7. 심사 제출물 (코드 밖 작업)

| 제출물 | 현재 | 할 일 |
|--------|------|------|
| 앱 이름 | `history-tracker` 계열 | 트레이드마크 규칙 확인 — "X for Slack" 형태는 되고 "Slack X"는 안 된다 |
| short description | — | 10단어 이내 |
| long description | — | 문제 정의 + Slack 안에서 뭘 하는지. **LLM 오답 가능성 고지 포함**(AI 요건) |
| 아이콘·스크린샷 | — | 1600×1000(8:5)·2MB 이하, **Slack 안에서 동작하는 모습**(우리 대시보드 화면이 아니라) |
| 비디오 | — | 30~90초 YouTube 공개 링크, 자막 on·광고 off, 실제 환경 스크린캐스트. 강력 권장이라 만든다 |
| pricing 표시 | 유료 구독 결정됨 | "Free and paid plans available" 또는 확정 모델로 표기 |
| 랜딩 페이지 | `/landing` (ko/en) | Slack 앱 전용 절 추가 — 설치 경로(Add to Slack 또는 안내), 방침 링크, Slack 안 동작 스크린샷. DESIGN.md에서 파생 |
| support 페이지 | 연락처만 | 공개(로그인 불요) 페이지 + 이메일/폼. **2영업일 내 응답**이 유지 의무 |
| 개인정보처리방침 | `/privacy` `#slack` 앵커 | 보존 기간·삭제 요청 경로(연동 해제·탈퇴·이메일 문의)·**AI 사용(OpenAI 모델, 학습 미사용)** 보강. 앵커 id는 심사 제출 후 불변 |
| scope 사유서 | — | scope별 "무엇에 쓰는가"(기능 기준). `*:history`는 "히스토리 전반의 맥락 검색·질의"가 요건인 이유를 서술 — Real-time Search 예외 인정 사례에 정렬 |
| Security & Compliance | — | AI 공시 4종(모델·데이터 보존·테넌시·리전), 보안 문항 |
| 심사용 시나리오 | — | 심사자는 앱을 설치·시험한다. GitHub 로그인 → 프로젝트 생성이 선행되는 온보딩이라 **테스트 계정·데모 조직·안내 문서**를 Testing information에 제공해야 한다 |
| staging 앱 | — | published 앱 manifest 복제본. 승인 후 변경 검증·재심사용 |

리스팅·커맨드 응답의 언어는 **이번 작업 범위 밖**이다. "언어 지원"을 표기하려면 그 언어로 **전체
경험**(Slack 안 메시지 포함)이 가능해야 한다는 요건이 있지만, i18n 착수와 묶지 않고 지금 있는
언어로 제출한다. 언어 분리는 [i18n.md](i18n.md)가 정본인 별도 작업이다.

## 8. 등재 리스크 등록부

심각도 순. "통과 불확실성"은 코드로 없앨 수 없어 별도로 관리한다.

1. **"export or backup message data" 판정 — 최대 리스크.** 메시지 본문을 Neo4j에 저장하는 것은
   사실이고, 심사가 이를 export/backup으로 읽으면 기능 추가로도 못 넘는다. 방어선은 셋:
   (a) 제품이 내보내기·백업·원문 열람 기능을 제공하지 않음 — 저장은 질의를 위한 인덱스,
   (b) `*:history` 예외 사유(Real-time Search류)와 같은 프레임의 사유서,
   (c) 보존·삭제 정책의 명문화(§7). 그래도 남는 불확실성은 **사전 문의**로 줄인다(§12).
2. **자격 모수 — 활성 워크스페이스 10곳(28일 내 사용, 샌드박스 제외)·주간 활성 10명.**
   기존 기록(5곳)보다 **두 배로 상향 확인됐다**(2026-08-29). C(BYO)로 연결한 사용자는 자기 앱
   설치라 이 숫자에 안 잡힌다 — B를 유지해야 하는 이유가 더 강해졌다. 이 모수는 코드가 아니라
   **배포·사용자 확보의 문제**라, S1~S3(코드)와 독립적으로 진행되고 제출 시점만 가른다.
3. **`*:history` enhanced review.** 해당 scope 요청 앱은 강화 심사 대상이라고 명시돼 있다.
   기능 심사(최대 10주)가 더 길어질 수 있다는 일정 리스크로 취급한다.
4. **무료 티어 90일 조항.** "무료 워크스페이스의 90일 지난 메시지에 앱이 접근을 제공하면 안 된다"는
   조항이 있는데, 우리는 수집 시점에 보이던 메시지를 그래프에 계속 둔다 — 워크스페이스가 무료
   플랜이면 90일 이후에도 질의로 노출될 수 있다. 수집 자체는 API가 90일 밖을 안 주므로 문제가
   없고, **보존·노출**이 쟁점이다. 플랜 조회(`team.billing:read`)로 무료 워크스페이스의 오래된
   Communication을 걸러내는 방안이 있으나 scope가 하나 늘어난다 — 해석을 먼저 확인하고 결정한다(§12).
5. **리스팅 언어 일관성** — 이번 작업에서는 다루지 않는다. 앱 내 언어(현재 한국어 중심)와
   리스팅 언어가 어긋나면 심사에서 걸릴 수는 있으나, i18n 착수와 함께 본다.

## 9. 하지 않는 것

- **Assistant/Agent UI** — zero-copy(Slack 데이터 저장 금지) 요건과 아키텍처가 충돌한다.
  슬래시 커맨드로 충분히 "Slack 안의 기능"을 성립시킨다.
- **봇 토큰으로 수집 전환** — 등재 자격도 rate limit도 바꾸지 못하고, 채널마다 초대가 필요해
  개인 사용자에게 불리하다(public-readiness §0-3에서 별개 항목으로 이미 결정).
- **App Home·Home 탭** — 가이드라인이 "쓰지 않는 탭은 켜지 말라"고 명시한다. 커맨드만으로
  시작하고, 심사 피드백이 요구하면 그때 설계한다(Phase 2 후보).
- **direct install** — 설치-우선 온보딩(계정 없이 설치한 사용자 유도)을 새로 설계해야 한다.
  리스팅 → 랜딩 → 가입 → 연결의 기본 경로로 제출하고, 등재 후 개선 항목으로 미룬다.
- **org-ready deployment(Enterprise Grid)** — Slack Connect 채널 대응 등 별도 요건이 붙는다. 범위 밖.
- **Slack으로의 알림·메시지 발송** — `chat:write`를 받지 않는다. 커맨드 응답은 전부 `response_url`.
- **`/why-code` 결과를 대화로 저장** — 단발 질의만 한다. 웹 채팅과 이력을 합치는 것은 등재 후 후보.
- **이번 작업에서 언어 분리** — 리스팅·커맨드 응답의 다국어는 i18n 착수와 함께 본다.

## 10. 진행 순서

리뷰 응집도 기준 묶음. S1~S3이 코드, S4~S6은 설정·자산·운영이다. 코드 묶음은 전부 backend
중심이라 **순차**가 기본이고, S5(제출물·프론트·문서)만 S2·S3과 병렬 가능하다.

| 묶음 | 내용 | 선행 | 성공 기준 |
|------|------|------|----------|
| **S1** | Events API 수신 — 서명 검증 필터, `url_verification`, `app_uninstalled`·`tokens_revoked` 멱등 정리, `SLACK_SIGNING_SECRET` 배선(compose·SecurityConfig) | — | 코드 완료, 실기동 미확인. 서명 검증(타임스탬프 창 포함)·이벤트 처리 단위 테스트 통과. 로컬 실기동(앱 제거 → 행·그래프 삭제 확인)은 아직 진행하지 않음. |
| **S2** | 자격증명 이중화 — DTO 확장(`access_token`·`authed_user.id`), `SlackCredentialCodec`(JSON+평문 폴백), `connected_user_id` 저장, lifecycle 양 토큰 폐기, **worker 폴백 먼저** | S1과 독립이나 순차 권장 | 신규/레거시 자격증명 왕복 테스트(backend·worker 각각), 기존 Slack 수집 회귀 그린 |
| **S3** | `/why-code` 커맨드 — commands 엔드포인트, 3초 ack + 비동기 단발 질의(대화 저장 없음), 매핑·게이팅·다중 매칭 규칙, help/오류 응답 | S1(서명 공용)·S2(게이팅 키) | 매핑·게이팅·다중 매칭 단위 테스트, 실기동: Slack에서 질의 → ephemeral 답변. 미연결 사용자 안내 확인 |
| **S4** | Slack 앱 설정 — bot user·`commands` scope·커맨드 등록·Event Subscriptions URL·staging 앱 생성. **public distribution은 아직 켜지 않는다** | S1~S3 배포 | dev 워크스페이스에서 재동의 → 새 자격증명 형식 확인, 이벤트·커맨드 왕복 확인 |
| **S5** | 제출물 — 랜딩 Slack 절, support 페이지, privacy 보강, 스크린샷·비디오, scope 사유서, AI 공시 초안 | S3(스크린샷 소재) | 페이지 공개 접근 확인, `typecheck && build` 그린, 사유서·공시 사용자 검토 완료 |
| **S6** | 실적·제출 — public distribution 활성화(속도 하락 시작 — B 트랙), 10곳·10명 축적, Testing information 준비, 제출 | 전부 + 모수 충족 | 예비 심사 통과 → 기능 심사 대응 |

**S1·S2는 등재와 무관하게 가치가 있다**(라이프사이클 위생·게이팅 기반) — 모수 축적(§8-2)이
길어져도 코드 작업은 선행할 수 있다. 반대로 **public distribution 활성화(S6)는 되돌리기 어렵고
기존 사용자 수집을 느리게 만드므로** 제출 준비가 끝날 때까지 미룬다.

## 11. 문서 동반 갱신 (각 묶음에서)

- `docs/public-readiness.md` §0-3 — 자격 수치 정정(완료), D 착수 상태 갱신(S1).
- `docs/data-collection.md` — Slack 절에 라이프사이클 이벤트 절 추가(S1 완료), 승인 후 한도 복구 주석(S6).
- `docs/DB.md` — 스키마 변경 없음 확인(S1). 자격증명 JSON·`connected_user_id`는 S2에서 같은 BYTEA/JSONB에 추가.
- `services/backend/CLAUDE.md` — slack Events API 서술(S1 완료). 커맨드·코덱은 S2·S3.
- `services/pipeline-worker/CLAUDE.md` — 자격증명 폴백 규칙(S2).
- `clients/web-dashboard/CLAUDE.md`·`/privacy` — deletedData 문구·방침 보강(S5).
- `docs/deployment.md` — `SLACK_SIGNING_SECRET`·Events URL(S1 완료). 커맨드 URL은 S3·S4.

## 12. 확인 필요 (착수 전 문의·실측)

사용자 결정(커맨드 이름 `/why-code`, 앱 제거 시 그래프 삭제, 단발 질의, 언어는 이번 범위 밖)은
2026-08-29에 닫혔다. 아래만 남는다.

**Slack에 문의(제출 전, 근거 확보):**

1. 메시지 본문을 질의용 인덱스로 저장하는 구조가 "export or backup" 부적격에 해당하는지 —
   §8-1. C(BYO) 트랙의 "고객 제작 앱 내부 앱 예외" 문의(public-readiness §0-3)와 함께 묶어 보낸다.
2. 무료 티어 90일 조항이 "수집 시점에 유효했던 데이터의 보존"에도 적용되는지 — §8-4.

**직접 실측(코드 착수 중 확인):**

3. bot scope 추가 후 재동의 시 기존 user 토큰이 유지되는지(재발급인지) — S2 실기동에서 확인.
4. `auth.test`가 user 토큰으로 `user_id`를 돌려주는 형식(레거시 백필 경로) — S3 전에 확인.

## 참고 (2026-08-29 확인)

- [Marketplace 가이드라인·요건](https://docs.slack.dev/slack-marketplace/slack-marketplace-app-guidelines-and-requirements/) — 부적격 사유·리스팅·보안·AI 공시·유지 의무
- [배포·심사 절차](https://docs.slack.dev/slack-marketplace/distributing-your-app-in-the-slack-marketplace) — 예비/기능 심사 기간, staging 앱, 재제출
- [`app_uninstalled`](https://docs.slack.dev/reference/events/app_uninstalled/) · [`tokens_revoked`](https://docs.slack.dev/reference/events/tokens_revoked/) — 순서 비보장, 멱등 처리 근거
- [rate limits](https://docs.slack.dev/apis/web-api/rate-limits/) · [2025-05-29 changelog](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps)
- 내부: [public-readiness.md §0-3](public-readiness.md)(B+C+D 결정) · [data-collection.md](data-collection.md)(Slack 수집·429 적응)
