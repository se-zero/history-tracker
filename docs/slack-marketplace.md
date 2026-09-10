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
| AI 공시 | ⚠️ 랜딩 Slack 절에 오답 고지. Security 폼·long description은 제출 때 | Security & Compliance 4종, 리스팅 long description |
| LLM 학습 금지 | ✅ 학습 안 함. `/privacy` 제4조에 명시 | 없음 |
| 무료 티어 90일 조항 | ⚠️ 90일 지난 메시지가 그래프에 남아 질의로 노출될 수 있음 | 해석 확인 필요 — **열린 리스크** (§8) |
| 리스팅 자산(아이콘·스크린샷 1600×1000·비디오 30~90초) | ❌ 없음 | 제작 (§7) |
| 랜딩 페이지(공개, 설치 경로, 방침 링크) | ✅ `/landing#in-slack` — 설치 경로·방침·지원 링크·오답 고지. Slack 안 스크린샷·Add to Slack은 없음 | 스크린샷은 자산. direct install은 등재 후 (§9) |
| 지원 채널(로그인 없는 문의, 2영업일 응답) | ✅ `/support` | 2영업일 응답 유지 |
| 개인정보처리방침(수집·용도·보존·삭제 경로·연락처) | ✅ `/privacy` `#slack` — 학습 미사용·OAuth 앱 잔류 고지 | 앵커 id 불변 |
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
- `SlackCredentialLifecycle.revoke`는 **user 토큰만** `auth.revoke`한다. 봇 토큰은 워크스페이스당
  하나라, 한 프로젝트 해제가 같은 워크스페이스의 다른 프로젝트 `/why-code`까지 끊는다.
- **해제해도 앱은 워크스페이스에 남는다.** 같은 워크스페이스를 다른 프로젝트가 쓰고 있을 수 있어
  `apps.uninstall`(앱 전체 제거 API)도 봇 토큰 `auth.revoke`도 부르지 않는다. 우리 쪽 폐기는
  그 연결의 user 토큰이 전부이고, 앱 제거는 워크스페이스 관리자의 행동 → `app_uninstalled`
  이벤트(§4)로 돌아온다. 프론트 `sourceCatalog`의 Slack `deletedData`에 이 비대칭("앱 자체는
  Slack 관리 화면에서 제거")을 명시한다.

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
| 랜딩 페이지 | `/landing` (ko/en), Slack 절 `#in-slack` | Slack 안 동작 스크린샷·Add to Slack은 등재 후. Installation landing page는 이 URL |
| support 페이지 | `/support` | 2영업일 응답 유지. 스크린샷·비디오 등과 별개 |
| 개인정보처리방침 | `/privacy` `#slack` 앵커 | 보존·삭제·학습 미사용 반영. 앵커 id는 심사 제출 후 불변 |
| sub-processor 목록 | `/privacy` `#subprocessors` 앵커(제4조 표) | Security & Compliance 폼의 "Guidelines for sub-processors URL"이 요구. 전용 페이지를 두지 않고 제4조를 그대로 쓴다 — 위탁처가 늘 때 두 곳이 갈라지지 않게. 이 id도 제출 후 불변 |
| scope 사유서 | — | scope별 "무엇에 쓰는가"(기능 기준). `*:history`는 "히스토리 전반의 맥락 검색·질의"가 요건인 이유를 서술 — Real-time Search 예외 인정 사례에 정렬 |
| Security & Compliance | 폼 답 초안 작성(2026-09-09) — General·Privacy & data governance·Certifications·Security 4개 절 | AI 공시 4종은 코드에서 확정(질의 `gpt-5.4-mini`, 임베딩 `text-embedding-3-large`, 내부 판단 `gpt-4o-mini` / 학습 미사용 / 저장 한국·처리 미국 / OpenAI 공용 API). 남은 것은 §12-7(OpenAI ZDR 실측) |
| 보안 연락처 | ✅ `security@why-code.com` — Cloudflare Email Routing 수신, 2026-09-09 생성·**도착 확인 완료** | 발신은 개인 메일함에서 나간다(전용 발신 미착수) — 약관 이행 전제는 아니다(제11조는 "서비스 내에 공지") |
| VDP(취약점 공개 정책) | 없음 — 폼에 No로 답한다 | `/support`에 신고 절차 한 절을 두거나 GitHub 비공개 취약점 신고를 켜면 Yes로 올릴 수 있다. 발신 수단 없이도 성립한다 — 등재 후 후보 |
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
6. **자체 단일 서버 운영이 Security & Compliance 폼에 그대로 드러난다.** 호스팅 답이
   "on-premise 단일 서버, 제3자 호스팅 없음"이고, 인증(SOC 2·ISO 27001·CSA STAR·FedRAMP)은
   전부 없으며, 오프사이트 백업도 없다([public-readiness.md](public-readiness.md) §5-2).
   인증란은 전부 optional이라 **등재를 막는 요건은 아니지만**, 규제 산업 워크스페이스의 설치
   승인에서 걸리고 심사자가 가용성·물리보안을 되물을 여지가 있다. **빈칸과 No로 정직하게
   제출한다** — 없는 인증을 적으면 심사자가 증서를 열어보고, 그건 인증 없음보다 치명적이다
   (2026-09-09, 폼 작성 중 확인).

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
| **S2** | 자격증명 이중화 — DTO 확장(`access_token`·`authed_user.id`), `SlackCredentialCodec`(JSON+평문 폴백), `connected_user_id` 저장, lifecycle은 user 토큰만 폐기, **worker 폴백 먼저** | S1과 독립이나 순차 권장 | 신규/레거시 자격증명 왕복 테스트(backend·worker 각각), 기존 Slack 수집 회귀 그린. **S2-a(worker 읽기 호환) 완료** — `SlackCredentialCodec` + `SlackCollector.resolveFetchRequest` 구현, 전체 테스트 그린. **S2-b(backend 쓰기) 완료** — JSON 자격증명 저장, `connected_user_id`, lifecycle은 user 토큰만 폐기(봇은 워크스페이스 공유). |
| **S3** | `/why-code` 커맨드 — commands 엔드포인트, 3초 ack + 비동기 단발 질의(대화 저장 없음), 매핑·게이팅·다중 매칭 규칙, help/오류 응답 | S1(서명 공용)·S2(게이팅 키) | **코드 완료**, 실기동 미확인. 매핑·게이팅·다중 매칭 단위 테스트. 실기동(Slack에서 질의 → ephemeral, 미연결 안내)은 아직 진행하지 않음. |
| **S4** | Slack 앱 설정 — bot user·`commands` scope·커맨드 등록·Event Subscriptions URL·staging 앱 생성. **public distribution은 아직 켜지 않는다** | S1~S3 배포 | dev 워크스페이스에서 재동의 → 새 자격증명 형식 확인, 이벤트·커맨드 왕복 확인 |
| **S5** | 제출물 — 랜딩 Slack 절·support·privacy는 코드 완료. 스크린샷·비디오, scope 사유서, Security 폼 AI 공시 | S3(스크린샷 소재) | 페이지 공개 접근 확인됨(코드). 사유서·공시 사용자 검토·자산은 남음 |
| **S6** | 실적·제출 — public distribution 활성화(속도 하락 시작 — B 트랙), 10곳·10명 축적, Testing information 준비, 제출 | 전부 + 모수 충족 | 예비 심사 통과 → 기능 심사 대응 |

**S1·S2는 등재와 무관하게 가치가 있다**(라이프사이클 위생·게이팅 기반) — 모수 축적(§8-2)이
길어져도 코드 작업은 선행할 수 있다. 반대로 **public distribution 활성화(S6)는 되돌리기 어렵고
기존 사용자 수집을 느리게 만드므로** 제출 준비가 끝날 때까지 미룬다.

## 11. 문서 동반 갱신 (각 묶음에서)

- `docs/public-readiness.md` §0-3 — 자격 수치 정정(완료), D 착수 상태 갱신(S1).
- `docs/data-collection.md` — Slack 절에 라이프사이클 이벤트 절 추가(S1 완료), 승인 후 한도 복구 주석(S6).
- `docs/DB.md` — 스키마 변경 없음 확인(S1). 자격증명 JSON·`connected_user_id`는 S2-b에서 같은 BYTEA/JSONB에 반영.
- `services/backend/CLAUDE.md` — slack Events API 서술(S1 완료). 코덱(S2-b 완료). 커맨드(S3 완료).
- `services/pipeline-worker/CLAUDE.md` — 자격증명 폴백 규칙(S2).
- `clients/web-dashboard/CLAUDE.md`·`/privacy`·`/support`·`/landing#in-slack` — deletedData 문구·방침·지원 페이지(S5 페이지 완료).
- `docs/deployment.md` — `SLACK_SIGNING_SECRET`·Events URL(S1 완료). Commands Request URL(S3 완료). 앱에 커맨드 등록·bot scope는 S4.

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

**Security & Compliance 폼 작성 중 열린 것(2026-09-09):**

5. ✅ **운영 주체 실명 확정(2026-09-09)** — 폼의 Company / developer name과 `LEGAL_OPERATOR`를
   모두 **"JUNSU SEO"**로 맞췄다. 심사자가 폼과 방침·약관을 대조한다
   ([public-readiness.md](public-readiness.md) §4-3).
6. ✅ **Cloudflare를 sub-processor로 표기한다(2026-09-09)** — 터널이 TLS를 엣지에서 끊어 트래픽이
   통과하기 때문이다(저장은 없다). 방침 제4조 표에 행을 추가했고 "중계·메일 전달만 하며 저장하지
   않는다"를 함께 명시했다. `#subprocessors` 앵커가 가리키는 것이 그 표다.
7. ✅ **OpenAI ZDR 없음 — 기본값으로 확정(2026-09-09)** — ZDR은 셀프서비스 토글이 아니라
   신청·승인제이고, **신청한 적이 없다.** 따라서 기본값이다 — 요청·응답을 남용 모니터링 목적으로
   최대 30일 보관 후 삭제, 학습에는 미사용. "LLM retention settings" 답은 §13-2의 문구로 확정한다.
   **신청은 지금 하지 않는다**: 마켓플레이스 요건이 아니고, 승인은 규모·사용 사례를 보며,
   병목은 자격 모수(§8-2)지 보관 정책이 아니다. 기업 고객이 실제로 요구하면 그때 검토한다.
8. ✅ **GDPR commitment URL은 비운다(2026-09-09)** — 방침에 EU 이용자 절이 없어 링크할 것이 없다.
   등재가 가까워지면 법률 검토와 함께 별도로 본다(방침은 아직 법률 검토 전 초안이다).
9. ✅ **백업 보존을 방침 제5조에 명시했다(2026-09-09)** — 폼의 삭제 정책 답과 방침이
   어긋나지 않게 한다. 설정값은 `BACKUP_RETENTION_DAYS=14`([deployment.md](deployment.md) §4-5)지만,
   **대외 문구는 "약 2주"로 쓴다** — `backup.sh:131`의 `find -mtime +14`는 14×24h를 *넘긴* 파일을
   지우므로 15일차부터 삭제되고, cron이 하루 1회(04:00)라 최악 ~16일이다. "늦어도 14일"은
   지킬 수 없는 약속이라 방침(한/영)과 폼 답안을 모두 "약 2주"로 맞췄다(2026-09-10 리뷰 지적 반영).

**방침 변경 절차는 즉시 시행으로 간다(2026-09-09 결정).** 6·9는 개인정보처리방침의 실질
변경이고 제11조가 "시행 7일 전까지 서비스 내에 공지"를 약속하지만, **공지를 띄울 화면이 아직
없고**([public-readiness.md](public-readiness.md) §5-3) 실사용자가 사실상 없는 단계라 시행일을
`2026-09-09`로 올리고 사전 공지 없이 배포한다. **다음 개정부터는 절차대로 간다** — 공지 화면이
생기면(§5-3) 7일 전 공지를 실제로 띄운다. 심사 시점에 방침 변경 이력을 물으면 이 결정을 근거로
설명한다.

## 13. 제출 폼 답안과 재개 지점 (2026-09-09)

Security & Compliance 폼을 채우며 확정한 값과, 다시 앉았을 때 이어갈 지점을 모은다.
**폼 화면은 로그인해야 보이고 서술 답변은 재작성 비용이 크므로** 값 자체를 여기에 둔다.
제출 전에 §12의 열린 항목을 먼저 닫는다.

### 13-1. 확정값

| 절 | 칸 | 값 |
|---|---|---|
| General | Company / developer name | `JUNSU SEO` — 사업자등록이 없어 폼 설명의 "Or your name if there is no company"를 따랐다. `LEGAL_OPERATOR`와 같은 표기 |
| General | Company headquarters location | Republic of Korea |
| General | Terms of service URL | `https://why-code.com/terms` — **`www.`를 붙이면 죽는다**(apex만 살아 있다) |
| Privacy | Data center location(s) | South Korea **만**. 미국을 함께 고르지 않는다 — OpenAI는 sub-processor지 우리 데이터센터가 아니다 |
| Privacy | How do you host your data? | On-premise. 단일 자체 서버, 인바운드 포트 0, Cloudflare Tunnel로만 공개 |
| Privacy | Data host company | None — self-hosted. Cloudflare는 네트워크·TLS만 |
| Privacy | Do you have sub-processors? | **Yes** — OpenAI, L.L.C.(미국) + Cloudflare, Inc.(미국) |
| Privacy | Guidelines for sub-processors URL | `https://why-code.com/privacy#subprocessors` |
| Privacy | Exposes an LLM to customers? | **Yes** |
| Privacy | LLM model(s) | `gpt-5.4-mini`(질의·요약·재작성) · `gpt-4o-mini`(커밋 diff 요약·그래프 판단 — **요약은 그래프에 저장돼 답변에 인용된다**) · `text-embedding-3-large`(임베딩). 전부 OpenAI 호스팅 API |
| Certifications | SOC · COPPA · ISO 27001/27017/27018 · FedRAMP · CSA STAR · Privacy Shield · GDPR · pen test 날짜 | **전부 비움** — 하나도 해당 없다. 빈칸은 감점이 아니지만 없는 인증을 적으면 심사자가 증서를 열어본다 |
| Certifications | Are you HIPAA compliant? | **No** — 진료기록을 다루지 않는다. Yes는 BAA 체결 가능 선언이라 사실과 다르다 |
| Security | SSO / SAML | **No / No** — 인증은 GitHub OAuth 전용이라 기업 IdP를 붙일 수단이 없다. 비밀번호를 저장하지 않는다는 점은 "third party services" 답에서 밝힌다 |
| Security | Dedicated security team / Bug bounty | **No / No** |
| Security | Vulnerability disclosure program | **No** — `/support`에 신고 절차를 두거나 GitHub 비공개 취약점 신고를 켜면 Yes로 올릴 수 있다(발신 수단 없이 성립) |
| Security | Third party auths/connections required? | **Yes** — GitHub 로그인이 유일한 가입 경로, 소스 1개 이상 연동 필요, 답변은 OpenAI 의존 |
| Security | Security contact | `security@why-code.com` — 도착 확인 완료 |
| Testing | 알림 대상 | 이름은 `JUNSU SEO`. **연락처(개인 메일·휴대전화)는 폼에 직접 입력하고 이 문서에 남기지 않는다** — 레포가 public이다. 전화는 국제 표기라 앞 `0`을 뺀다 |
| Testing | Notification channel | Email — 심사 피드백은 길고 링크가 붙어 SMS로는 놓친다 |

### 13-2. 서술 답변 (영문 원문)

**Data retention policy**

> JUNSU SEO retains Customer Data only while the integration that produced it remains connected.
> Slack data collected through the granted read scopes — channel names and IDs, channel messages and
> thread replies, and workspace member display names and emails — is stored in a per-project knowledge
> graph for one purpose: answering the connecting user's natural-language questions about why their code
> changed. The stored text is an index that makes retrieval possible; the product provides no export, no
> backup, no archive browsing, and no bulk message viewing. Encrypted OAuth credentials are retained
> until the integration is disconnected. No Customer Data is sold, used for advertising, or used to train
> any model.

"질의를 위한 인덱스지 내보내기·백업 기능이 아니다"를 여기서부터 못 박는다 — §8-1(최대 리스크)의
판정이 실제로 내려지는 칸이다.

**Data archival/removal policy**

> Customer Data is removed on any of these triggers: **Disconnect** — the stored credentials and all data
> collected from that workspace, including its knowledge-graph nodes, are deleted without delay.
> **App uninstall / token revocation** — we subscribe to `app_uninstalled` and `tokens_revoked` and treat
> them as the strongest withdrawal signal: they run the same deletion path as an explicit disconnect.
> Handling is idempotent, since Slack does not guarantee event ordering. **Project deletion / account
> closure** — deleting a project deletes its integrations, conversations, collected records and graph;
> closing an account deactivates it immediately and deletes all associated data after a 30-day reversal
> window. Server backups are kept on a rolling basis for roughly two weeks; deleted data ages out of
> backups within that window.

**Data storage policy**

> Customer Data is stored in a Neo4j graph database and a PostgreSQL relational database running on a
> single server we operate ourselves. No database port is exposed to the internet — the only public path
> is an outbound-only Cloudflare Tunnel, with TLS 1.2+ terminated at the Cloudflare edge. OAuth
> credentials are never stored in plaintext: they are encrypted with AES-256-GCM using a key held outside
> the database, and sign-in refresh tokens are stored only as hashes. Each project's graph is isolated by
> a composite key, so one customer's data is never reachable from another project's queries. We request
> read-only scopes and never write to a customer's Slack workspace.

⚠️ **"encrypted at rest"라고 쓰지 않는다.** 암호화되는 것은 자격증명이고, 메시지 본문이 든
Neo4j·Postgres 디스크에는 암호화가 걸려 있지 않다. 과장하면 보안 문항과 어긋난다.

**What is your procedure for handling requests for data deletion?**

> Most deletion is self-service and requires no request: disconnecting an integration deletes that
> source's stored credentials and every record collected from it, including its knowledge-graph nodes;
> deleting a project deletes everything under it; and closing an account deletes all associated data after
> a 30-day reversal window. These paths are listed in Article 8 of our privacy policy.
>
> For requests that cannot be handled in-product, we accept them at contact@why-code.com, published on
> our public support page, which requires no login or account. This includes requests from people who are
> not our users — a workspace member whose messages were collected through someone else's connection — as
> described in Article 7 of our privacy policy. We respond within 2 business days.
>
> On receiving a request we verify that the requester controls the account or the identity concerned,
> identify every project and graph node holding the data, and run the same deletion path used by
> self-service disconnection. Deletion is idempotent and propagates to the knowledge graph, not only to
> the relational database. Server backups roll off on a roughly two-week cycle, after which no copy remains.

⚠️ **"삭제 완료를 이메일로 통지한다"는 넣지 않았다.** `contact@`·`security@`는 전달 전용이라
그 주소를 발신인으로 답장할 수 없다([public-readiness.md](public-readiness.md) §4-2·§5-3).
지킬 수 없는 문장을 쓰지 않는다.

**LLM data tenancy policy**

> Our LLM usage runs on OpenAI's shared, multi-tenant hosted API. We do not operate a dedicated model
> instance, and we do not fine-tune or train any model on Customer Data — each request is stateless and
> carries only the context needed to answer that one question. Isolation is enforced on our side: every
> graph node is keyed per project, so a query can only retrieve data from the project the asking user is
> authorized for, and the `/why-code` command is restricted to the user who connected that workspace.

**LLM data residency policy**

> Customer Data is stored in South Korea on our own server. When a question is asked, the question text
> and the relevant retrieved context (titles, message bodies, summaries) are transmitted to OpenAI, L.L.C.
> in the United States for embedding and answer generation. This cross-border transfer is disclosed in
> Article 4 of our privacy policy. No other region processes Customer Data.

**LLM retention settings** — 확정(2026-09-09). ZDR 미신청이라 기본값이다(§12-7).

> Data sent to OpenAI is never used to train or improve their models. Under OpenAI's standard API terms,
> request and response data may be retained by OpenAI for up to 30 days for abuse monitoring and is then
> deleted; we have not enabled Zero Data Retention. On our side, `/why-code` queries in Slack are one-shot
> and are not persisted as conversation history — only questions asked in our web dashboard are stored,
> and those are deleted when the project or account is deleted.

**What third party services does your app use?**

> **Receives customer data (sub-processor):** OpenAI API — embeddings and answer generation.
> **Infrastructure:** Cloudflare — Tunnel ingress, TLS termination, DNS, and email routing. No customer
> data is stored there.
> **Data source APIs, connected by the user and only with their consent:** GitHub, Slack, Atlassian Jira,
> Discord, Google Chat, Notion, Linear, Asana, ClickUp. All connections are read-only; we never write to a
> customer's workspace.
> **Front-end assets:** web fonts served from jsDelivr, Google Fonts, and Fontshare. A strict
> Content-Security-Policy restricts scripts to our own origin.
> **Not used:** no analytics or advertising trackers, no payment processor (the service is currently
> free), and no error-reporting SaaS. Databases (Neo4j, PostgreSQL) and the message broker (RabbitMQ) are
> open-source software we run ourselves, not hosted services.
> Authentication is GitHub OAuth only — we never store passwords, and sign-in refresh tokens are stored as
> hashes.

폰트 CDN까지 적는 이유는 방문자 IP가 그쪽에 노출되는 것이 사실이고 CSP 헤더
(`clients/web-dashboard/nginx.conf`)에 허용 출처로 박혀 있어 심사자가 직접 볼 수 있기 때문이다.
마지막 줄이 SSO=No가 "비밀번호를 쓰나 보다"로 오해되는 것을 막는다.

**Are any third party auths/connections required for your app to function?** — Yes.

> Signing in requires a GitHub account (our only authentication method). The product answers questions
> from a knowledge graph built out of the user's own connected tools, so at least one data source
> connection — GitHub, Slack, Jira, Discord, Google Chat, Notion, Linear, Asana, or ClickUp — is required
> for it to be useful. Answer generation and semantic search depend on the OpenAI API.

**How to test your app** — 실기동 확인 후 문구를 다듬는다. 뼈대는 이렇다.

> **What the app does in Slack.** One slash command, `/why-code <question>`. It answers in natural
> language, from a knowledge graph built out of the user's own connected tools, explaining why a piece of
> code changed. Replies are always ephemeral. The app never posts, edits, or deletes anything in the
> workspace — we do not request `chat:write`.
>
> **Setup.** 1) Go to https://why-code.com and sign in with GitHub. 2) Create a project. 3) Connect a
> GitHub repository — for testing, fork https://github.com/se-zero/history-tracker, which has an extensive
> commit history. 4) Connect Slack from the same screen; this installs the app into your workspace.
> 5) Wait for the first collection to finish (progress is shown on the sources screen).
>
> **Then test these.** (a) `/why-code <question>` → an ephemeral answer with cited sources. (b) `/why-code`
> with no text → usage help. (c) Run the command from a different Slack account in the same workspace → a
> message explaining that only the user who connected the workspace can use it; answers contain data from
> private repositories, and workspace membership is not repository access. (d) Disconnect Slack in our
> dashboard, or uninstall the app from Slack → all data collected from that workspace, including its graph
> nodes, is deleted.

(d)를 명시적으로 안내한다 — §8-1의 "export or backup" 의심을 실물로 반박하는 장면이다.

### 13-3. 열린 항목 — 여기서부터 재개한다

**① OpenAI ZDR — 닫혔다(2026-09-09).** 신청·승인제이고 신청한 적이 없으므로 기본값이다
(§12-7). 폼 문구는 §13-2로 확정했고, **방침 제4조에도 "OpenAI가 남용 확인 목적으로 최대 30일
보관 후 삭제"를 명시했다** — 폼이 방침보다 상세한 상태를 남기지 않는다(백업 보존과 같은 처리).
같은 개정(시행일 2026-09-09) 안에 들어가 개정 이벤트가 늘지 않았다.

**② `/why-code` 실기동 범위 확인** — 워크스페이스에서 커맨드가 동작하는 것은 확인됐다(2026-09-09).
아래가 미확인이라 S1·S3·S4의 상태를 아직 고치지 않았다.

1. **로컬(ngrok)인가 배포 서버인가** — 배포면 S4를 완료로 쓸 수 있고, 로컬이면 배포 확인이 남는다
   (`.env`의 redirect URI가 ngrok을 가리키고 있다)
2. Events Request URL 등록과 앱 제거 테스트 — S1 실기동. 폼의 삭제 정책 답을 뒷받침하는 증거다
3. 재동의 후 기존 Slack 수집이 정상인지 — §12-3이 닫힌다
4. 다른 계정 게이팅 안내 / 빈 입력·`help` 응답 — 폼 "How to test"의 (b)(c)

**③ 테스트 계정 방식** — 심사자는 우리 워크스페이스에 로그인할 수 없고 자기 워크스페이스에 설치해
시험한다. 온보딩이 GitHub 로그인 → 프로젝트 생성 → 연동 → 수집 대기라 빈손으로 시작하면 답할
데이터가 없다.

- **A. 공개 레포 포크** — `se-zero/history-tracker`가 public이다. 심사자가 포크해 우리 앱을 설치하면
  커밋 히스토리로 답이 나온다. 계정 공유가 없고 심사자가 자기 계정으로 전 과정을 밟는다는 게 장점.
  **포크에는 PR·이슈가 따라오지 않아** 제품의 핵심(맥락 연결)이 약해진다
- **B. 데모 조직 초대** — PR·이슈까지 있는 데모를 만들고 심사자 계정을 초대. 완전하지만 데이터 제작
  비용과 리뷰 중 왕복(연락 → 초대 → 재개)이 생긴다
- **C. 테스트 GitHub 계정 자격증명 제공** — 권하지 않는다. 계정 공유는 약관 소지가 있다
- **권장: A + 비디오.** 포크로 손에 잡히는 체험을 주고, PR·이슈·Slack이 엮이는 장면은 녹화로 보인다

**④ 자산 제작(S5)** — 스크린샷 1600×1000(8:5)·2MB 이하, 비디오 30~90초 YouTube 공개(자막 on,
광고 off). **Slack 안에서 동작하는 화면**이어야 한다 — 대시보드 화면만 내면 "Slack 안에 기능이 없다"는
부적격 사유를 스스로 증명하는 꼴이다. 동작 환경이 살아 있을 때 찍는 것이 가장 싸다.

### 13-4. 배포해야 반영되는 것

`#subprocessors` 앵커와 방침 개정(Cloudflare 위탁·백업 보존·실명 표기·시행일 2026-09-09)은
빌드에 들어 있어야 공개된다.

```bash
cd infra/docker && ./prod.sh up -d --build web-dashboard
```

**폼에 sub-processors URL을 제출하기 전에 배포한다.** 배포 전에는 해시가 안 맞아 방침 맨 위로
착지하며(깨지지는 않는다), 심사자에게는 "목록으로 보내준다더니 첫 화면"으로 보인다.

### 13-5. 제출까지 남은 순서와 금지 사항

```
S4 앱 설정 마무리(Events URL·staging 앱)
  → 실기동 4종 확인(13-3 ②)
  → 스크린샷·비디오(13-3 ④)
  → Testing information 작성
  → 활성 워크스페이스 10곳 + 주간 활성 10명 축적   ← 코드가 아닌 문제, 가장 오래 걸린다
  → public distribution 활성화
  → 제출
```

- **동의 체크박스를 미리 누르지 않는다** — 필수 항목이 빈 채로 반려되면 **재제출 시 큐가 리셋**된다
  (예비 심사 최대 10영업일 재대기).
- **인증 URL을 지어내지 않는다** — 심사자가 연다. 빈칸은 감점이 아니지만 허위는 치명적이다(§8-6).
- **public distribution을 미리 켜지 않는다** — `conversations.history`가 1 req/min으로 떨어져
  초기 수집이 11시간짜리가 된다(§1). 되돌리기 어렵다.

## 참고 (2026-08-29 확인)

- [Marketplace 가이드라인·요건](https://docs.slack.dev/slack-marketplace/slack-marketplace-app-guidelines-and-requirements/) — 부적격 사유·리스팅·보안·AI 공시·유지 의무
- [배포·심사 절차](https://docs.slack.dev/slack-marketplace/distributing-your-app-in-the-slack-marketplace) — 예비/기능 심사 기간, staging 앱, 재제출
- [`app_uninstalled`](https://docs.slack.dev/reference/events/app_uninstalled/) · [`tokens_revoked`](https://docs.slack.dev/reference/events/tokens_revoked/) — 순서 비보장, 멱등 처리 근거
- [rate limits](https://docs.slack.dev/apis/web-api/rate-limits/) · [2025-05-29 changelog](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps)
- 내부: [public-readiness.md §0-3](public-readiness.md)(B+C+D 결정) · [data-collection.md](data-collection.md)(Slack 수집·429 적응)
