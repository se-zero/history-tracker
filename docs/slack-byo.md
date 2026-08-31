# Slack BYO 앱 병기 — C 트랙

[public-readiness.md §0-3](public-readiness.md)의 B+C+D 병행 결정(2026-08-28 회의) 중 **C(고객이
자기 워크스페이스에 Internal로 배포한 앱의 User OAuth Token을 붙여 넣는 경로)의 실행 정본**이다.
B(우리 앱, 느린 채로 유지)·D(마켓플레이스 등재)는 이 문서의 범위가 아니다. D의 정본은
[slack-marketplace.md](slack-marketplace.md)다. **C가 D를 대체하지 않는다.**

근거는 changelog의 "Internal customer-built applications are not impacted" 문구와, backend에
붙은 연결·격리 코드다. 그 문구가 **제3자가 토큰을 보관·호출하는 경우**에도 적용되는지는
**확인되지 않았다**(§C0). 적용된다고 쓰지 않는다.

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| 행 유니크 | 한 프로젝트 Slack 행 **하나** — `UNIQUE (project_id, provider)` | OAuth와 BYO 공존 불가. 전환은 기존 해제(그래프 삭제) 후 재연결 |
| 받는 토큰 | **User OAuth Token `xoxp-`만** | `xoxb-`/`xoxe-` 접두사, `auth.test`의 `bot_id`는 거절 (`SlackClient.verifyToken`) |
| 자격증명 JSON | `{user_token, bot_token: null}` (`SlackCredentialCodec`) | 워커는 `user_token`만 읽는다. **pipeline-worker 변경 없음** |
| `external_ref` | `workspace_id`, `workspace_name`, `connected_user_id`, **`connect_method: "byo"`** | 상수 `SlackOAuthConnectFlow.CONNECT_METHOD` / `CONNECT_METHOD_BYO`. OAuth exchange는 이 키를 넣지 않는다. 레거시·OAuth 행은 키가 없거나 `"byo"`가 아님 |
| 연결 API | `POST /api/v1/projects/{projectId}/integrations/slack` JWT, body `token` | 성공 후 `triggerCollection`. 저장 실패 시 고객 토큰 **revoke 안 함** (OAuth grant가 아님) |
| 무효 토큰 HTTP | `verifyToken`의 `UnauthorizedException` → `connectSlackWorkspace`가 **`BadRequestException`(HTTP 400)** | JWT 401과 구분해 프론트 인터셉터가 세션을 지우지 않게 |
| 해제 | `connect_method=byo`면 `SlackCredentialLifecycle.revoke`가 원격 `auth.revoke` 없이 true | 그래프·행·checkpoint는 기존처럼 삭제. 앱은 고객이 Slack 설정에서 제거 |
| Events / `/why-code` | **BYO 행 제외** | `disconnectSlackWorkspace` / `disconnectSlackUsers` / `listSlackCommandTargets`가 `isSlackByo`로 건너뜀. 같은 `workspace_id`의 OAuth 행만 `app_uninstalled`로 지워진다 |
| UI | 타일 "연결" 클릭 → 방식 선택 다이얼로그에서 OAuth vs 토큰. 주 카드는 `OAuthSourceCard` | 카탈로그 `secondaryConnect: { kind: "token", oauthHint, tokenHint }`. 토큰 직접 입력은 Slack BYO만 예외 |
| 한도 | 워커 history 고정 딜레이 **1.2s** ≈ 내부 앱 Tier 3(~50/min) | `SlackRateLimiter` 기본 `conversations-history-delay-ms:1200`. 우리 앱 OAuth는 느린 B(비등재) |
| 킬 스위치 | **없음** | 등재 후 토큰 경로 폐쇄는 지금 범위 아님 |

## 1. 무엇이 걸려 있는가

- **공개 배포(B)를 켜면 우리 앱의 `conversations.history`가 1 req/min로 떨어진다.** C는 고객
  Internal 앱 토큰으로 그 한도를 우회하려는 병행 경로다. 우회가 changelog 예외에 해당하는지는 §C0.
- **D 자격 모수는 우리 앱 설치 수**다. C로 연결한 사용자는 자기 앱을 설치한 것이라 10곳·10명에
  안 잡힌다. 그래서 B를 없애지 않는다([public-readiness.md §0-3](public-readiness.md)).
- **설계 원칙 예외** — 다른 provider는 토큰을 붙여 넣지 않는다. Slack BYO만 `POST .../integrations/slack`.

사용자에게 제시하는 이유는 "봇이 싫으면"이 아니라 **"빠르게 받고 싶으면"**이다.

## 2. 연결 모델

한 프로젝트에 Slack은 한 줄이다. provider 값이 `slack`으로 같고 유니크가 `(project_id, provider)`라
**OAuth 행과 BYO 행을 동시에 둘 수 없다.** `provider=slack-byo`로 가르는 설계는 하지 않는다(§하지
않는 것). 이미 OAuth로 붙어 있으면 409 — 전환하려면 해제(그래프 삭제) 후 다시 붙인다.

흐름:

```
프론트(방식 선택 다이얼로그의 토큰 선택지 → 토큰 폼) → JWT POST .../integrations/slack { "token": "<xoxp-…>" }
  1. 확정 Slack 행 있으면 409 (verifyToken 호출 없음)
  2. SlackClient.verifyToken — 접두사 xoxp- 아니면 즉시 거절, auth.test, bot_id면 거절
  3. UnauthorizedException → BadRequestException (HTTP 400)
  4. SlackCredentialCodec.serialize({user_token, bot_token: null}) → 암호화 저장
  5. external_ref에 workspace_id·workspace_name·connected_user_id·connect_method="byo"
  6. triggerCollection(slack) — 워커는 user_token Bearer로 기존 수집
```

OAuth 연결(`SlackOAuthConnectFlow.exchangeCode`)은 `connect_method`를 **넣지 않는다.** 키가 없거나
값이 `"byo"`가 아니면 OAuth/레거시다. Events·커맨드·원격 revoke는 그 행만 대상으로 한다.

## 3. 자격증명과 워커

저장 형태는 D(S2)에서 도입한 JSON과 같다. BYO는 `bot_token`이 항상 null이다.

| 경로 | JSON | 워커 |
|------|------|------|
| OAuth (우리 앱) | `{user_token, bot_token}` — bot은 있으면 저장, 수집에는 안 씀 | `user_token` Bearer |
| BYO | `{user_token, bot_token: null}` | 동일 — `connect_method`를 읽지 않음 |
| 레거시 | 평문 user 토큰 (코덱 폴백) | 동일 |

**pipeline-worker는 C 때문에 바뀌지 않는다.** 수집 계약이 OAuth와 같아서다. 워커가 `bot_token`으로
폴백하거나 `connect_method`로 분기하지 않는다.

## 4. 해제·격리

연동 해제 순서(권한 폐기 → 그래프 삭제 → 행·checkpoint 삭제)는 기존과 같다. 갈리는 것은 **원격
폐기뿐**이다.

- **OAuth**: `auth.revoke` (user + 있으면 bot). 앱은 워크스페이스에 남을 수 있다.
- **BYO**: `SlackCredentialLifecycle.revoke`가 `connect_method=byo`이면 decrypt/`auth.revoke` 없이
  true. 저장된 자격증명과 그래프는 지운다. **고객 앱은 Slack 설정에서 고객이 제거한다** — 우리가
  `apps.uninstall` 하지 않는 것과 같은 이유(토큰·앱의 소유자가 우리 앱이 아님)에, 더해 그 토큰을
  우리가 폐기하면 고객의 Internal 앱 grant까지 끊는다.

저장이 unique 충돌 등으로 실패해도 **붙여 넣은 토큰을 revoke하지 않는다.** OAuth `connectOAuth`의
409 정리(방금 교환한 grant 폐기)와 반대다 — 여기는 고객 소유 토큰이라 실패 경로에서 폐기하면 안 된다.

우리 앱 Events API의 `app_uninstalled`는 **우리 앱이 그 워크스페이스에서 빠진 신호**다. 같은
`workspace_id`의 BYO 행은 다른 앱의 토큰이므로 건너뛴다. 건너뛰지 않으면 고객 Internal 앱 연결이
우리 앱 제거에 휩쓸려 지워진다. `tokens_revoked`·`/why-code`도 같다 — 우리 앱 OAuth grant·커맨드
대상만.

## 5. UI

미연결 Slack 타일의 "연결"을 누르면 방식 선택 다이얼로그(`ConnectMethodDialog`)가 열리고,
OAuth 앱 설치 vs 토큰 붙여넣기 중 고른다 — 선택지 문구는 카탈로그의
`secondaryConnect: { kind: "token", oauthHint, tokenHint }`(Slack만)가 소유한다.
**`TokenIntegrationCard`/`SlackCard`를 주 카드로 두지 않는다** — 연동 행은 `OAuthSourceCard`.
토큰 직접 입력은 Slack BYO만 예외 — 다른 소스는 동의·설치만.
토큰 폼은 `clients/web-dashboard/src/components/sources/SlackTokenConnectDialog.tsx`다.

## 6. 한도

워커 `SlackRateLimiter`는 `conversations.history`·`replies`에 기본 1,200ms를 둔다(주석: Tier 3
50/min). 429가 나면 `SlackPacing`이 그 실행 동안 간격을 승격한다.

- **C(고객 Internal 앱)** — changelog가 내부 앱을 새 한도 대상에서 빼 둔다고 하므로, 가정은
  Tier 3(~50/min). 1.2s 페이싱이 그 버킷에 맞춰져 있다. **제3자 보관이 예외인지는 §C0.**
- **B(우리 앱, 비등재)** — 공개 배포를 켜면 1 req/min. 같은 워커 설정이 429 적응으로 흡수한다.

킬 스위치(등재 후 BYO 경로를 서버에서 닫기)는 없다. 등재 후 토큰 경로를 유지할지는 미정이고
지금 범위가 아니다.

## 7. C0 문의 — 배포 전 게이트 (열린 위험)

changelog(2025-05-29)는 **"Internal customer-built applications are not impacted"** 라고만 한다.
고객이 자기 워크스페이스에 Internal로 배포한 앱의 `xoxp`를 **우리 SaaS가 보관하고
`conversations.history`에 쓰는 것**이 그 예외에 해당하는지는 Slack이 답하지 않았다.

**추측해서 "예외에 해당한다"고 쓰지 않는다.** 이 절의 성공 기준은 Slack 회신 원문을 아래에 붙이는
것이다. 지금은 **미회신 — 배포 전 게이트**. 거절이면 **C를 접는다.**

### 문의 초안 (사람이 Slack에 보낼 텍스트)

**한국어**

> 안녕하세요. 2025-05-29 changelog
> (https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps)의
> "Internal customer-built applications are not impacted" 예외 범위를 확인하고 싶습니다.
>
> 우리 제품은 제3자 SaaS입니다. 고객이 자기 Slack 워크스페이스에 Internal로 배포한 앱을 만들고,
> 그 앱의 User OAuth Token(`xoxp`)을 우리 서비스에 붙여 넣습니다. 우리는 그 토큰을 암호화해 보관하고,
> `conversations.history` 등으로 메시지를 수집합니다. 앱의 소유자·설치 주체는 고객이고, 토큰을 들고
> Web API를 호출하는 주체는 제3자인 우리입니다.
>
> 이 사용이 changelog의 Internal customer-built application 예외에 해당합니까?
> 고객이 자기 워크스페이스에서만 쓰는 Internal 앱이라는 점과, 제3자가 그 토큰을 보관·사용하는 점
> 중 어느 쪽이 예외 판단에 영향을 줍니까?

**English**

> Hello — we would like to confirm the scope of the 2025-05-29 changelog statement
> "Internal customer-built applications are not impacted"
> (https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps).
>
> We are a third-party SaaS. A customer creates an app, distributes it as Internal in their own
> Slack workspace, and pastes that app's User OAuth Token (`xoxp`) into our product. We store the
> token (encrypted) and use it to call `conversations.history` (and related methods) to collect
> messages. The customer owns and installed the app; we hold and use the token as a third party.
>
> Does this fall under the "Internal customer-built applications" exception in that changelog?
> Does the exception still apply when a third party stores and uses the token, or only when the
> customer-built app itself is the caller?

### 회신

*(미회신 — 배포 전 게이트. 회신이 오면 이 절에 원문을 붙인다.)*

## 8. 하지 않는 것

- **봇 토큰(`xoxb`)으로 수집 전환** — 등재 자격도 한도 버킷의 축도 바꾸지 못하고, 채널마다 초대가
  필요하다. public-readiness §0-3에서 별개 항목으로 이미 둔다.
- **`provider=slack-byo` 분리** — 유니크·수집·해제가 provider 단위라 행을 둘로 쪼개지 않는다.
  구분은 `external_ref.connect_method` 한 키.
- **기존 OAuth 행의 자격증명만 갈아끼우기** — 전환은 해제(그래프 삭제) 후 재연결. 한 행에 OAuth
  grant와 고객 토큰을 섞지 않는다.
- **고객 앱에 Events API·`/why-code`를 요구** — 그 앱은 고객 소유 Internal 앱이다. 라이프사이클
  이벤트와 슬래시 커맨드는 **우리 앱 OAuth 행만**.
- **워커 `bot_token` 폴백** — 수집은 `user_token`만. BYO는 bot이 없다.
- **등재(D) 시 BYO 경로 자동 제거** — 킬 스위치 없음. 유지 여부는 미정, 지금 범위 아님.
- **토큰 원문을 로그에 남기기** — 식별자(`projectId` 등)까지만.

D의 슬래시 커맨드·Events URL 등록·심사 제출물은 [slack-marketplace.md](slack-marketplace.md)다.
C 작업으로 그 묶음(S4~S6)을 바꾸지 않는다.

## 9. 리스크 등록부

1. **C0 Internal 예외 × 제3자 보관 — 열린 위험.** §7. 회신 없이 C를 배포하지 않는다. 거절이면 C를
   접는다. **예외에 해당한다고 단정하지 않는다.**
2. **B 실적 잠식.** C가 "빠른 탈출구"면 우리 앱을 설치하는 사람이 줄어 D의 주간 활성 10명이 더디게
   쌓일 수 있다. public-readiness §0-3에서 관찰하기로 한 항목 — 여기서 닫지 않는다.
3. **토큰 붙여넣기 운영 부담.** 고객이 잘못된 토큰·봇 토큰·다른 워크스페이스 토큰을 넣을 수 있다.
   접두사·`bot_id` 거절과 HTTP 400이 1차 방어다. 온보딩 카피는 C2.
4. **해제 후 고객 앱이 Slack에 남음.** 고지(Privacy `#slack`·프론트 `deletedData`)로 다룬다. 우리가
   고객 앱을 제거하지 않는 것이 맞다.

## 10. 코드 위치

| 역할 | 위치 |
|------|------|
| BYO 연결 | `IntegrationService.connectSlackWorkspace` |
| 토큰 검증 (`xoxp-`, `bot_id` 거절, `UnauthorizedException`) | `SlackClient.verifyToken` |
| 키 상수 `CONNECT_METHOD`=`"connect_method"`, `CONNECT_METHOD_BYO`=`"byo"` | `SlackOAuthConnectFlow` — OAuth `exchangeCode`는 이 키를 넣지 않음 |
| BYO revoke 스킵 | `SlackCredentialLifecycle.revoke` |
| HTTP | `IntegrationController` `POST .../integrations/slack`, body `ConnectSlackIntegrationRequest.token` |
| Events / 커맨드에서 BYO 건너뜀 | `IntegrationService.isSlackByo` — `disconnectSlackWorkspace`, `disconnectSlackUsers`, `listSlackCommandTargets` |
| JSON 코덱 | backend·worker 각각 `SlackCredentialCodec` (`user_token` / `bot_token`) |
| 수집 | pipeline-worker `SlackCollector` — `user_token` Bearer. `connect_method` 미사용 |
| 프론트 | `sourceCatalog` `secondaryConnect: { kind: "token", … }`. 주 카드 `OAuthSourceCard`. `ConnectMethodDialog`(방식 선택) + `SlackTokenConnectDialog`(토큰 폼) |

## 11. 문서 동반 갱신

- `docs/public-readiness.md` §0-3 — C 구현 체크, C0은 열린 체크박스
- `docs/data-collection.md` — 라이프사이클은 우리 앱 OAuth 행만
- `docs/DB.md` — `external_ref.connect_method`
- `services/backend/CLAUDE.md` · `services/pipeline-worker/CLAUDE.md` · `clients/web-dashboard/CLAUDE.md`
- `/privacy` `#slack` — 자격증명 수집 방법·삭제 경로를 OAuth/BYO로 구분. 앵커 id 불변

## 참고

- [2025-05-29 changelog](https://docs.slack.dev/changelog/2025/05/29/rate-limit-changes-for-non-marketplace-apps) — Internal 예외 문구. **제3자 보관에의 적용은 미확인**
- [rate limits](https://docs.slack.dev/apis/web-api/rate-limits/)
- 내부: [public-readiness.md §0-3](public-readiness.md)(B+C+D) · [slack-marketplace.md](slack-marketplace.md)(D만) · [data-collection.md](data-collection.md)(Slack 수집)
