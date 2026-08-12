# Discord 연동 계획 — 대화 아키타입 1호

`docs/integration-abstraction.md` Part B의 대화 아키타입 첫 커넥터다. Slack이 레퍼런스 구현이며,
ai-engine은 무변경이다(`Communication` 노드 재사용). 전체 순서는 「커넥터 엔드투엔드 체크리스트」를
따르고, 이 문서는 그 체크리스트를 Discord에 대입했을 때의 **결정 사항과 Discord 고유 설계**만 다룬다.

Discord Developer Docs 조사(2026-08, v10 기준)를 근거로 작성했다. 미확정 항목은 맨 아래
「구현 시 확인」에 모았다.

**진행 상황(2026-08-09)**: §3 backend 연결·§4 pipeline-worker 수집·§5 web-dashboard 화면 코드 작업
완료(backend `./gradlew test` 536개, pipeline-worker `./gradlew test` 218개, 프론트
`typecheck && build` 전체 그린). §9 실기동 시나리오도 연결·초기 수집까지 확인 완료 —
연결(redirect URI 오리진 사고 포함, §1) 다음으로 **수집이 checkpoint 쓰기에서 매번 실패하는 공용
버그(A9 — `checkpoints.provider` 열거형 CHECK 잔존)를 발견해 당일 수정했다**
(`docs/integration-abstraction.md`의 A9, V13 마이그레이션). 수정 후 재트리거로 checkpoint 정상 갱신
실측 확인.

## 이 커넥터가 검증하는 것 (1호로 고른 이유)

MS Teams를 1호에서 밀어낸 이유는 비용이다(유료 조직 테넌트 + 관리자 동의 —
`docs/teams-integration.md` §1-0). Discord는 서버 생성·봇 등록이 전부 무료이고 관리자 동의 절차가
없어 같은 자리를 훨씬 싸게 지난다.

다만 **무엇을 검증하는지는 조사 후 달라졌다.** 착수 전에는 "대화형에서도 A4 다단 선택 메커니즘이
통하는가"를 볼 생각이었는데, Discord는 **자기 동의 화면에서 서버를 고르게 하므로 선택 단계가 아예
없다**(Slack형). 따라서 Discord가 실제로 검증하는 것은 다음 셋이다.

1. **대화 아키타입이 Slack 외 소스로도 성립하는가** — `Communication` 재사용, ai-engine 무변경.
2. **비만료형 provider의 404 경로** — 봇 토큰은 만료되지 않아 `AccessTokenRefresher`를 만들지 않는다.
   내부 토큰 API가 404로 답하고 호출부가 이를 "갱신 못 함"으로 올바로 처리하는지, Slack의 조용한 204
   사건 이후 만든 안전망을 두 번째 provider로 확인한다.
3. **앱 수준 봇 + 프로젝트별 설치 대상**이라는 GitHub App형 자격증명 모델이 OAuth 프레임워크에
   얹히는가 — 여기서 공용 SPI의 구멍이 하나 드러났다(§2).

**대화형에서의 A4 선택 메커니즘 검증은 여전히 미해결**이며 Teams(1단 team 선택)나 Google Chat이
그 역할을 맡는다. `docs/integration-abstraction.md`의 Part B 순서 근거도 이에 맞춰 정정했다.

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| provider 표기 | RDB/경로 `discord` · source `DISCORD` · alias `DISCORD:{userId}` · routing `event.discord`(자동 유도) | 카탈로그 id와 일치. 한 단어라 표기 충돌 없음 |
| API | Discord REST **v10** (`https://discord.com/api/v10`) | |
| 연결 플로우 | OAuth2 `bot`+`identify` → **Discord 동의 화면에서 서버 선택** → 즉시 확정 | 선택 단계 없음(Slack형). `IntegrationSelectionFlow`를 만들지 않는다 |
| 수집 주체 | **앱 수준 봇 토큰** (`Authorization: Bot {token}`) | 사용자 OAuth 토큰으로는 REST 메시지 히스토리를 못 읽는다. `messages.read` scope는 로컬 RPC 전용 |
| 행 자격증명 | `refresh_token` **평문 문자열** (Slack형 — JSON 아님) | 구현 중 정리: `access_token`·`expires_at`은 어디서도 읽지 않는다(수집은 봇 토큰, 갱신기 없음) — YAGNI로 Jira식 JSON 코덱 없이 Slack처럼 단일 문자열만 저장한다. refresh token 하나로 V12 제약도 만족한다 |
| 토큰 갱신 | `AccessTokenRefresher` **미구현** | 봇 토큰은 만료되지 않는다. 수집이 사용자 토큰에 의존하지 않으므로 갱신할 이유가 없다 |
| 원격 폐기 | `ProviderCredentialLifecycle` **구현** — 봇이 서버를 떠난다 | 해제 후 봇이 남으면 사용자 서버에 방치된다. SPI 시그니처 확장을 선행 완료했다(§2) |
| 증분 | snowflake `after` 커서 — checkpoint의 `Instant`를 snowflake로 변환 | checkpoint 저장소 계약(`Instant`)을 건드리지 않는다 |
| 필수 게이트 | **MESSAGE_CONTENT privileged intent** | REST에도 적용된다 — 켜지 않으면 `content`가 **빈 문자열**로 온다. 100서버 미만은 포털에서 자기 승인 |
| 개인정보 | `actor.email`을 **못 얻는다** | 봇은 타인의 이메일에 접근할 수 없다(`email` scope는 자기 자신만). 동일인 판단이 이름에만 의존 |

## 1. 사전 준비 — Discord 앱·봇 등록

[Discord Developer Portal](https://discord.com/developers/applications)에서 전부 무료로 끝난다.

- **New Application** 생성 → **Bot** 탭에서 봇 추가, **Token → Reset Token**으로 봇 토큰 발급.
  이 토큰은 앱 전체에 하나이며 **모든 프로젝트가 공유**한다(GitHub App private key와 같은 성격).
- **Bot 탭 → Privileged Gateway Intents → MESSAGE CONTENT INTENT를 켠다.**
  이걸 빠뜨리면 API는 200을 주는데 `content`가 빈 문자열이라 **수집은 도는데 본문만 사라진다** —
  가장 조용하게 실패하는 지점이므로 스모크 테스트에서 본문 존재를 반드시 단언한다.
  봇이 100개 서버 미만이면 토글만으로 되고, 그 이상은 앱 인증(verification)과 승인이 필요하다.
- **OAuth2 → Redirects**에 `{BASE}/api/v1/integrations/discord/callback` 등록.
  경로의 `discord`는 소문자 kebab이며 이후 바꿀 수 없다.
  `{BASE}`는 **프론트 오리진**이다(Slack·Jira와 동일). 콜백이 돌려주는 302가 상대 경로
  (`/projects/{id}/sources?connected=discord`)라 이 URI의 오리진이 곧 사용자가 착지하는 오리진이고,
  `/api/` 프록시와 SPA fallback을 동시에 가진 곳은 프론트(nginx)뿐이기 때문이다.
  backend(:8080)를 직접 등록하면 연동은 성공해도 마지막 리다이렉트가 401로 끝난다.
- OAuth2 URL Generator로 만들 동의 URL의 구성:
  scope `bot identify`, bot permissions는 **View Channels(1024) + Read Message History(65536)**
  = `permissions=66560`. 최소 권한만 준다 — 메시지 전송·관리 권한은 요청하지 않는다.
- 환경변수(`ATLASSIAN_*` 패턴): `DISCORD_CLIENT_ID` · `DISCORD_CLIENT_SECRET` ·
  `DISCORD_REDIRECT_URI` · **`DISCORD_BOT_TOKEN`**. 앞의 셋은 backend만, 봇 토큰은
  **pipeline-worker에도** 필요하다(수집 주체가 봇이다). `infra/docker/docker-compose.yml`의
  두 서비스 블록에 각각 추가하고 실제 값은 `.env`.

봇을 서버에 넣는 것은 사용자가 동의 화면에서 하므로, 개발자가 미리 준비할 것은 위가 전부다.
테스트용 서버는 Discord 클라이언트에서 무료로 만들면 된다.

## 2. 선행 작업 — `ProviderCredentialLifecycle.revoke`가 external_ref를 못 받았다 (✅ 완료, 2026-08-08)

시그니처가 자격증명만 받았다.

```java
default void revoke(byte[] encryptedCredential) {}
```

Slack(`auth.revoke`)과 Jira(refresh token 폐기)는 자격증명만으로 폐기가 끝나서 이 모양으로 충분했다.
**Discord의 의미 있는 폐기는 "봇이 서버를 떠나는 것"**(`DELETE /users/@me/guilds/{guild_id}`,
봇 토큰으로 호출)인데, 여기엔 `external_ref.guild_id`가 필요했다. 즉 Discord는 **폐기에 수집 대상
참조가 필요한 첫 provider**였다. 그냥 두면 연동을 해제해도 봇이 사용자 서버에 그대로 남는 문제였다.

**처리 내용**: `revoke(byte[] encryptedCredential, Map<String, Object> externalRef)`로 시그니처를
넓혔다. 기존 두 구현체(`SlackCredentialLifecycle`·`JiraCredentialLifecycle`)는 새 인자를 무시하도록
수정했고, 호출부(`IntegrationService.revokeProviderAccess`)는 이미 들고 있는 연동 행에서
`integration.getExternalRef()`를 그대로 전달하도록 고쳐 추가 조회가 필요 없었다. `./gradlew test`
전체 그린(Testcontainers 스키마 검증 포함). Discord 커넥터 PR과 분리한 선행 PR로 처리했다 —
`docs/integration-abstraction.md`의 A8 항목도 함께 완료 표시했다.

**Teams 문서 §2(webhook 토큰 확보 일반화)는 Discord에 필요 없다.** 현재 코드는 Jira만 특별 취급하고
나머지 provider는 그냥 지나가므로, 갱신이 없는 Discord는 지금 구조에서도 정상 동작한다.
그 일반화는 Teams·Google Chat 같은 만료 토큰형이 붙을 때 필요해진다.

## 3. backend — 연결 (✅ 완료, 2026-08-08)

`com.history.backend.discord` 패키지. SPI는 **둘만** 구현했다(`AccessTokenRefresher`·
`IntegrationSelectionFlow`는 계획대로 만들지 않았다). `./gradlew test` 536개 전체 그린.

- `IntegrationProvider.DISCORD("discord", "Discord")` 추가. DB 마이그레이션 불필요.
- `DiscordProperties`(@ConfigurationProperties, `PropertiesConfig`의 `@EnableConfigurationProperties`
  목록에 등록 — 빠뜨리면 `NoSuchBeanDefinitionException`으로 전체 컨텍스트 테스트가 죽는다) +
  `application.yaml`(운영·테스트 양쪽) 블록, `DiscordClient`(code 교환, grant 폐기, 봇 길드 퇴장).
- `DiscordOAuthConnectFlow`
  - `buildAuthorizeUrl` — `client_id`·`response_type=code`·`redirect_uri`·`scope=bot identify`·
    `permissions=66560`·`state`. Jira 패턴대로 `UriComponentsBuilder.encode()`라 scope의 공백은
    `%20`으로 인코딩된다(테스트로 고정).
  - `exchangeCode` — `POST /oauth2/token`(client_secret basic, form) → 응답의 `refresh_token`과
    **`guild` 객체**(`id`·`name`)를 꺼내 `OAuthConnection(refreshToken, {guild_id, guild_name})`을
    돌려준다. 선택 단계가 없으므로 `pendingSelection`이 아니라 확정 형태다.
    **`guild` 객체가 응답에 오는 것은 실측 확정됐다** — 「확인 완료」 1번.
  - `access_token`·`expires_at`은 어디서도 안 읽어 `DiscordTokenResponse`에 매핑하지 않았다
    (YAGNI — 저장 자격증명은 §0에서 정리한 대로 `refresh_token` 평문 하나뿐이다).
- `DiscordCredentialLifecycle`(`ProviderCredentialLifecycle`) — §2에서 넓힌 시그니처로
  `revokeToken(refreshToken)`(`POST /oauth2/token/revoke`) 후 `externalRef`에서 꺼낸 `guild_id`로
  `leaveGuild`. `guild_id`가 없으면(이론상 도달하지 않지만 방어적으로) 퇴장은 건너뛰고 grant 폐기는
  그대로 한다 — 단위 테스트로 두 분기 모두 고정.
- `IntegrationResponse.displayName` switch에 `DISCORD` case — `externalRefValue("guild_name")`.
  단계가 하나뿐이라 상위 단계 병기는 없다.
- `IntegrationServiceTest`에 Slack·Jira 해제 테스트와 나란히 Discord 해제 테스트를 추가해
  "폐기 → 길드 퇴장 → RDB 삭제" 순서와 A8의 `externalRef` 전달을 실제 lifecycle 구현으로 검증한다.

## 4. pipeline-worker — 수집 (✅ 완료, 2026-08-08)

`source/discord` 패키지에 `DiscordCollector` · `DiscordRawService` · `DiscordNormalizer` ·
`DiscordRateLimiter`. `CollectionProvider.DISCORD` 추가 외에 오케스트레이션 계층은 무변경이다.
`./gradlew test` 218개 전체 그린(`PipelineWorkerApplicationTests` context load 포함).

### 자격증명 해석의 비대칭

```java
// 수집 주체는 앱 수준 봇이다 — 행의 사용자 OAuth 토큰은 해제 시 grant 폐기용이라 여기서 쓰지 않는다.
Optional<RawFetchRequest> resolveFetchRequest(IntegrationRow integration) {
    // botToken 누락(빈 문자열) — 없으면 IllegalStateException. guild_id보다 먼저 본다: worker 자신의
    // 설정이라 행마다 다르지 않고, 누락이면 이 배포 전체가 대상이기 때문이다.
    String guildId = /* external_ref.guild_id — 없으면 IllegalStateException */;
    return Optional.of(new RawFetchRequest("Bot " + botToken, guildId, Map.of()));
}
```

`AuthHeaders`는 Bearer 전용이므로 `bot(String)`을 **추가**했다(기존 동작 불변이라 안전한 추가). 행의
`encrypted_credential`을 복호화하지 않는 유일한 커넥터다 — `botToken`은 `DiscordCollector` 생성자가
`@Value("${app.discord.bot-token}")`로 worker 자신의 설정에서 직접 받는다(DB 조회가 아니다).
`DISCORD_BOT_TOKEN`은 backend·pipeline-worker 두 docker-compose 블록에 동일하게 forward한다 —
backend는 해제 시 길드 퇴장에, worker는 수집에 쓴다. **양쪽에 각자 설정해야 하는 특이 케이스라 한쪽만
빠뜨리는 실수가 나오기 쉽다.** worker 쪽이 빠지면 `application.yaml`의 `${DISCORD_BOT_TOKEN:}` 기본값이
빈 문자열이라, 여기서 막지 않으면 `AuthHeaders.bot("")`이 `"Bot "`(트레일링 스페이스뿐인) 헤더로 조용히
요청을 만들고 실패가 수집 시점 401로만 드러난다 — `guild_id` 누락은 여기서 즉시 예외인 것과 비대칭이라
`resolveFetchRequest`에서 fail-fast로 맞췄다(`resolveSafely`가 삼켜 이 provider만 건너뛴다, §4 위 참고).

### 수집 흐름

```
GET /guilds/{guild_id}/channels                 # 텍스트 채널 (type 0·5) 필터
GET /guilds/{guild_id}/threads/active           # 활성 스레드 (스레드도 채널이다)
채널·스레드별로 페이지 단위 반복:
  1회차: GET /channels/{id}/messages?after={snowflake(checkpoint)}&limit=100
  이후 : GET /channels/{id}/messages?after={직전 페이지의 최대 id}&limit=100
  페이지마다 normalize → publish (발행 배치 = 페이지)
  반환 개수 < 100이면 그 채널 종료
→ 전 채널 최대 occurredAt으로 checkpoint 1회 갱신
```

- **snowflake 변환** — Discord ID는 생성 시각을 품은 snowflake라, checkpoint의 `Instant`를
  `(epochMilli - 1420070400000) << 22`(Discord epoch 2015-01-01)로 바꿔 `after`에 넣는다.
  덕분에 **checkpoint 저장소의 `Instant` 계약을 그대로 두고도 서버사이드 증분 필터가 된다** —
  Slack이 채널 히스토리를 끝까지 훑던 문제가 여기서는 발생하지 않는다.
- **`after`는 커서 바로 다음 구간부터 앞으로 전진하며 채운다(실측 확정, 아래 참고).** 백로그가
  `limit`(최대 100)보다 많으면 **가장 오래된 쪽 100개**가 먼저 온다. 배치 안쪽 정렬만 최신→과거
  내림차순이고, 이 정렬은 **선택 구간과 무관하다** — 초기 설계는 이 둘을 혼동해 "최신 100개만 온다"고
  보고 `before` 역방향 보정을 넣었으나, 재실측으로 뒤집혔다. 따라서 가득 찬 페이지를 받으면 그 배치의
  **최대 id를 다음 `after`로** 삼아 이어받는다. 서버가 커서 이후만 걸러 주므로 클라이언트 경계
  필터링도 필요 없다.
- checkpoint: `discord/discord_messages` 단일 커서. Slack·Teams와 같은 이유로 채널을 가로질러
  마지막에 한 번만 전진시킨다.
- 봇이 접근 권한(View Channel·Read Message History)을 갖지 못한 채널은 403이거나 빈 결과다.
  **채널 단위 실패는 삼키고 다음 채널로 넘어간다** — `DiscordCollector.collect`의 채널 루프가
  `WebClientResponseException.Forbidden`을 잡아 그 채널만 건너뛴다(`DiscordRawService`는 예외를
  그대로 던지기만 한다 — 삼킬지는 provider가 아니라 오케스트레이션 계층에 가까운 Collector가 정한다).
  단 발행 예외는 삼키지 않는다(계약대로 checkpoint를 전진시키지 않아야 재발행된다).
- **길드 단위 실패도 같은 철학으로 삼킨다.** 관리자가 연동 해제 없이 봇을 서버에서 추방하면(흔한
  시나리오) 봇이 길드 멤버가 아니게 되어 `fetchChannels`의 두 호출(`/channels`, `/threads/active`)
  자체가 403이다. `DiscordCollector.collect`가 `rawService.fetchChannels(context)` 호출을 감싸
  `WebClientResponseException.Forbidden`이면 이번 실행은 Discord만 건너뛰고(checkpoint 미전진)
  `0`을 반환한다. 이걸 삼키지 않으면 `PipelineService.collectIncremental`이 예외를 그대로
  전파해(오케스트레이션은 provider 하나의 실패로 이후 provider를 멈춘다) `CollectionProvider` 선언
  순서상 Discord 바로 다음인 Google Chat까지 그 프로젝트의 매 웹훅마다 함께 실패한다.
- 아카이브된 스레드(`GET /channels/{id}/threads/archived/public`)는 1차 범위에서 제외한다 —
  활성 스레드만으로 시작하고, 누락이 문제가 되면 확장한다(「구현 시 확인」 1번).
- **다음 커서는 배열 위치가 아니라 최대 id로 뽑는다**(`DiscordRawService.maxMessageId`). 응답 정렬에
  기대 첫 원소를 집으면 정렬이 뒤집힌 순간 커서가 뒤로 가 무한 반복이 된다 — 정렬 가정을 믿은 것이
  애초 버그의 원인이었으므로 순서와 무관한 max로 고정하고, 오름차순 응답을 넣는 회귀 테스트
  (`fetchMessagePage_nextCursorUsesMaxId_notArrayPosition`)로 잠갔다.
- **커서는 노이즈 필터 이전 원본에서 뽑는다.** 100건이 전부 봇/시스템 메시지인 페이지에서 필터 결과로
  커서를 정하면 전진하지 못해 같은 페이지를 무한히 다시 받는다
  (`fetchMessagePage_fullPageOfNoise_stillAdvancesCursor`).
- **커서 전진을 매 페이지 검사하고, 전진하지 않으면 예외를 던진다**(`advances`). `after`가 커서 자신을
  제외하므로 최대 id는 항상 요청 커서보다 커야 하지만, **그게 정확히 이 코드가 한 번 틀렸던 종류의
  가정이다.** 최대 id가 전진하지 않거나 snowflake로 파싱되지 않으면 `IllegalStateException`으로 올린다.

  경고만 남기고 그 채널만 조용히 끊는 선택지는 **일부러 버렸다** — 다른 채널이 공용 커서를 전진시켜
  남은 구간이 영구 누락되는데, 그게 바로 이번에 고친 버그다. 예외로 올리면 `collect` 전체가 실패해
  checkpoint가 전진하지 않고(SPI 계약), 다음 수집에서 같은 커서로 재시도된다. 무한 루프(스레드 점유 +
  API 연타)도, 조용한 누락도 만들지 않는 유일한 선택지다
  (`fetchMessagePage_cursorWouldNotAdvance_throwsInsteadOfLooping`,
  `fetchMessagePage_unparseableIds_throwsInsteadOfSilentlyTruncating`).
- **채널 전체를 모으지 않고 페이지마다 발행한다**(Slack `SlackCollector`와 같은 모양). `DiscordRawService`는
  한 페이지 + `nextCursor`만 돌려주고(`DiscordMessagePage`), `DiscordCollector`가 페이지마다
  normalize → publish → 커서 전진을 돈다. 채널 전체를 모아 한 번에 발행하면 **발행 배치와 메모리 점유가
  채널 크기에 비례**하는데, `EventPublisher.awaitConfirms`는 배치 크기와 무관하게 단일
  `publish-confirm-timeout-ms`(10초) 안에서 기다리므로 큰 채널은 타임아웃 → checkpoint 미전진 →
  다음 실행이 **같은 크기 배치를 다시 시도해 또 실패**하는 영구 루프가 된다. 페이지 단위로 묶으면
  발행 배치가 최대 100건으로 고정된다.

  > 이 위험은 페이지네이션 버그가 채널당 ~100건으로 우연히 상한을 걸고 있던 동안 가려져 있었다.
  > 전진 페이지네이션으로 고치면서 함께 드러나 같은 변경에서 처리했다.

### NormalizedEvent 매핑 (`docs/normalized-event.md` 계약)

| 계약 필드 | Discord 값 | 비고 |
|-----------|-----------|------|
| `source` | `DISCORD` | |
| `properties.url` (자연키) | `https://discord.com/channels/{guild_id}/{channel_id}/{message_id}` | Discord는 URL 필드를 주지 않아 **조립**한다. 결정적이고 프로젝트 안에서 고유 |
| `properties.body` | `content` | 이미 평문이다(Teams의 HTML 변환이 불필요). `<@\d+>`(실제 멘션)만 `mentions` 배열의 표시 이름으로 치환하고, `mentions`가 비어 있는 평문 `@이름`은 그대로 둔다 |
| `properties.channel` | 채널 `name` (스레드면 스레드 `name`) | |
| `properties.conversation_id` | 스레드 안 메시지면 스레드 채널 id / 답글(type 19)이면 부모 체인의 **해소된** conversation_id / 그 외 자기 `id` | 아래 「답글 체인 해소」 참고 |
| `properties.created_at` · `occurredAt` | `timestamp` | `edited_timestamp`는 커서를 되돌리지 않도록 쓰지 않는다 |
| `actor.id` | `author.id` (snowflake) | 안정적·고유. 사용자명은 변경 가능하므로 id로 쓰지 않는다 |
| `actor.name` | `author.global_name` (없으면 `username`) | |
| `actor.email` | **항상 `null`** | 봇은 타인의 이메일을 얻을 수 없다. §7 참고 |
| `refs` | `content`에 `RefsExtractor` 그대로 | 평문이라 전처리가 필요 없다 |

정규화 제외: `author.bot == true`(봇·웹훅 메시지), 시스템 메시지(`type`이 0·19가 아닌 것).
Slack normalizer의 관례와 같다.

### 답글 체인 해소 — 직접 부모만 보면 대화가 쪼개진다

Discord의 `message_reference.message_id`는 **직접 부모**만 가리킨다(Slack은 항상 스레드 루트를
가리켜 이 문제가 없다). A←B←C처럼 답글에 답글이 달리면, B의 `conversation_id`는 A로 정확히
묶이지만 C는 B로만 묶여 **한 대화가 둘로 쪼개진다**. 답글에 답글은 흔한 패턴이라 방치하면 그래프
품질에 영향이 있다.

`DiscordNormalizer`는 채널 하나의 수집 실행 동안(페이지를 가로질러) `messageId → 해소된
conversation_id` 맵을 유지한다. 답글은 부모의 **해소된** 값을 물려받으므로(부모 자신이 답글이면
그 부모도 이미 해소돼 있다) 체인 전체가 한 conversation_id로 접힌다.

- **처리 순서가 핵심이다.** 부모를 자식보다 먼저 해소해야 하는데, 한 페이지 안에서 Discord 응답은
  최신→과거 내림차순이다(배치 안쪽 정렬 — 「확인 완료」 4). id는 snowflake라 오름차순=생성 순
  오름차순이므로, 정규화 직전에 id로 정렬해 처리 순서만 바꾼다. 반환하는 이벤트 목록 자체의 순서는
  발행·checkpoint 어느 쪽도 기대지 않아 안전하다.
- **맵은 페이지를 가로질러 살아야 한다.** 채널 전체를 모으지 않고 페이지마다 발행하므로(§4 위
  「채널 전체를 모으지 않고 페이지마다 발행한다」), `DiscordCollector.collect`가 채널마다 새 맵을
  만들어 그 채널의 `do-while` 페이지 루프 전체에 넘긴다. 답글은 같은 채널 안에서만 걸리므로 채널
  경계에서 초기화해도 정확하다.
- **부모가 맵에 없으면 직접 부모 id로 폴백한다** — 지금까지의 동작과 같다. 이전 실행에서 이미
  수집된 부모, 또는 노이즈로 필터된 부모(봇 메시지 등)가 이 경우다. 초기 수집은 채널 전체를 한
  실행에서 훑으므로 사실상 완전히 해소되고, 증분도 활동이 몰려 들어오므로 대부분 잡힌다. 남는
  잔여(체인 중간이 다른 실행에 걸친 경우)는 **기존 동작과 동일한 수준**이라 이 수정으로 더 나빠지는
  경우는 없다.

### Rate limit (구현 결과 — 계획 대비 단순화)

- **전역**: 호출마다 고정 딜레이(`app.rate-limit.discord.default-delay-ms`, 기본 250ms) — 봇당 초당
  50요청 상한에 보수적인 여유를 둔다. GitHub처럼 `X-RateLimit-Remaining`을 읽어 적응적으로 늦추는
  대신 Slack과 같은 고정 딜레이로 시작했다 — 채널을 순차 처리하는 현재 설계에서는 버킷 충돌이
  드물어 헤더 기반 정교화의 이득이 크지 않다고 판단했다. 실사용에서 429가 잦아지면 그때 GitHub
  패턴으로 옮긴다.
- **429**: `WebClientResponseException.TooManyRequests`를 잡아 응답 본문의 `retry_after`(초, 소수)만큼
  대기 후 재시도한다. 최대 3회까지 재시도하고 그래도 실패하면 예외를 전파한다(`DiscordRawServiceTest`로
  재시도-성공 경로를 고정).

## 5. web-dashboard — 화면 (✅ 완료, 2026-08-08)

`sourceCatalog.tsx`의 `discord` 항목(브랜드 마크 `DiscordMark`와 함께 이미 있었다)을
`status: "wired"`로 바꾸고 `connect: "oauth"`와 `deletedData`를 채웠다. 계획대로 provider 전용
컴포넌트는 만들지 않았다 — 선택 단계가 없어 `OAuthSourceCard`가 Slack과 동일하게 렌더한다.

`deletedData`에 **봇이 서버에서 나간다**는 사실을 넣었다: "수집한 채널 메시지·스레드와 그 그래프,
그리고 서버에 추가된 봇" — 사용자가 해제 버튼을 누르면 서버 멤버 목록에서도 봇이 사라지므로 미리
알리는 편이 맞다고 판단했다.

프론트에 provider 문자열이 하드코딩된 곳이 없어(`types/api.ts`의 `provider`가 판별 유니온이 아닌
평문 `string`으로 이미 정리돼 있음 — `docs/integration-abstraction.md` §3-4 참고) 이 한 항목 수정이
전부였다. 검증: `npm run typecheck && npm run build` 그린.

## 6. ai-engine — 무변경

`Communication` + `source=DISCORD`로 정규화되므로 코드 변경이 없다. 소스별 삭제
(`DELETE /graph/projects/{id}/sources/DISCORD`)·Actor alias(`DISCORD:{id}`)·Slack 노이즈 필터가
source 문자열 기반으로 자동 적용된다. 표시 라벨도 대문자 snake에서 `Discord`로 바르게 유도되므로
`_SOURCE_PREFIX_LABELS` 등록이 필요 없다. 확인은 스모크 테스트 하나면 된다.

## 7. 개인정보 — 이메일 없음이 주는 영향

Discord 봇은 다른 사용자의 이메일에 접근할 수 없다(`email` scope는 동의한 본인에게만 적용된다).
따라서 `actor.email`은 항상 `null`이고, **동일인 판단이 표시 이름에만 의존**하게 된다.

`docs/actor-node-design.md`의 스코어링에서 이메일은 가장 강한 신호이므로, Discord Actor는 다른
소스의 동일인과 자동으로 묶이지 않을 가능성이 높다. 이는 버그가 아니라 소스의 한계이므로,
`docs/actor-manual-merge.md`의 **수동 병합이 Discord에서는 예외가 아니라 정상 경로**라고 보고
연동 후 안내한다. 이름·이메일은 기존대로 `ActorAlias.pd_*`에만 저장한다.

Atlassian식 개인정보 보고 의무는 없다.

## 8. 문서 동반 갱신 (✅ 완료, 2026-08-08)

- ~~`docs/data-collection.md`~~ ✅ Discord 섹션(수집 대상·snowflake 증분·rate limit·트레이드오프) 추가.
- ~~`docs/integration-abstraction.md`~~ ✅ Part B 표의 Discord 진행 표시.
- ~~`services/backend/CLAUDE.md`~~ ✅ (패키지 구조에 `discord`, SPI 표 시그니처, provider 공통 규칙)
  · ~~`services/pipeline-worker/CLAUDE.md`~~ ✅ (`source.discord` 행, 라우팅 표, checkpoint 목록,
  rate limit, 봇 토큰 설정 — DB에서 안 읽는 예외 케이스로 명시).
- `docs/graph-schema.md`·`docs/DB.md`는 변경 없음(새 노드·테이블 없음) 확인함 — 실제로 손대지 않았다.

## 9. 검증 계획

- 단위: backend·pipeline-worker `./gradlew test`, 프론트 `typecheck && build`.
- **본문 존재 단언** — MESSAGE_CONTENT를 끈 채로도 수집은 성공해 버리므로, 정규화 테스트에서
  `body`가 비지 않음을 반드시 확인한다(§1의 조용한 실패 지점).
- 실기동 시나리오(무료 테스트 서버): 연결 ✅ → 동의 화면에서 서버 선택 ✅ → 초기 수집 ✅
  (A9 수정 후 checkpoint 정상 갱신 확인) → 그래프에 DISCORD Communication 확인 ✅ →
  새 메시지·스레드 답글 추가 후 PR 머지 웹훅으로 증분(미확인) →
  봇이 못 보는 비공개 채널이 수집을 막지 않는지(확인됨 — 접근 없는 채널은 로그만 남기고 건너뜀) →
  해제 시 DISCORD 노드만 삭제되고 **봇이 서버에서 나가는지**(미확인) 확인.

## 확인 완료 (2026-08-08 실측)

1. **`POST /oauth2/token` 응답에 `guild` 객체가 온다 — 확정.** 실제 교환 응답:

   ```json
   {
     "token_type": "Bearer",
     "access_token": "...",
     "expires_in": 604800,
     "refresh_token": "...",
     "scope": "identify",
     "guild": { "id": "1535519139987456092", "name": "history_tracker님의 서버", "...": "..." }
   }
   ```

   `guild.id`·`guild.name`이 그대로 온다. **`exchangeCode(String code)`는 code 하나만으로 충분하다** —
   시그니처를 넓힐 필요가 없다(§2의 A8과 별개로, 이 항목 때문에 생길 뻔했던 두 번째 공용 변경은 없다).
   덤으로 확인된 것: `scope`가 `identify` 하나뿐이다 — `bot` scope는 "봇을 서버에 추가"라는 부수효과로
   소비되고 반환 토큰에는 안 남는다. §0에 적은 "행 자격증명은 identify 권한뿐이라 수집에 못 쓰고 해제
   시 grant 폐기용으로만 남는다"는 설계가 실측으로 확인됐다.
2. **redirect URI에 `http://localhost` 허용됨 — 확정.** 포털이 `http://localhost:8080/api/v1/integrations/discord/callback`
   등록을 그대로 받았다. Slack·Jira와 달리 Discord 쪽에서는 로컬 개발에 터널이 필요 없다.
   **다만 포트를 backend(:8080)로 두면 안 된다** — 실제로 이 값으로 연동해 보니 토큰 교환·저장까지는
   성공하는데 콜백의 상대 302가 `localhost:8080/projects/...`로 해석돼 401로 끝났다. 오리진은 항상
   프론트여야 하므로 로컬이라면 `http://localhost:5173/...`, 터널을 쓰면 터널 도메인이다.
3. **MESSAGE_CONTENT 적용 확인 — 확정.** `GET /channels/{id}/messages`를 봇 토큰으로 호출한 실제 응답:
   `type: 0`(일반 메시지) 세 건 모두 `content`에 실제 텍스트가 채워져 왔다. `type: 7`(멤버 가입 시스템
   알림)만 `content`가 빈 문자열인데, 이는 인텐트 미적용이 아니라 시스템 메시지 자체가 본문이 없는
   정상 동작이다 — §4 정규화 제외 규칙(`type`이 0·19가 아닌 것 제외)이 정확히 걸러야 할 사례다.

   부수 발견: 사람이 자동완성 없이 그냥 타이핑한 `@이름`은 `mentions` 배열이 비고 `content`에도
   평문 그대로 남는다(`<@id>` 형식이 아니다). 실제 Discord 멘션 기능을 쓴 경우만 `mentions`가 채워지고
   `content`에 `<@1535516144784642048>` 같은 snowflake 형식이 들어간다. **멘션 치환은 `mentions`
   배열이 비어있지 않을 때만 동작하면 된다** — 나머지 `@텍스트`는 이미 최종 형태라 손댈 필요 없다.

4. **`after`의 선택 구간 — 확정(2026-08-11 재실측으로 정정).** 1차 실측(2026-08-08)에서 확인된 것은
   **배치 안쪽 정렬**(최신→과거 내림차순)뿐이었는데, 이를 **선택 구간**("최신 100개만 온다")으로
   확대 해석해 §4에 `before` 역방향 보정을 넣었다. 이 해석은 틀렸다.

   **재실측에 100건 초과 채널은 필요 없다** — 선택 규칙이 `limit`의 숫자값에 의존할 수 없으므로
   `limit=1`이면 메시지 3건짜리 채널로 1회 호출에 판별된다. 메시지 4건(M1…M4, 오름차순) 채널에서:

   | 호출 | 반환 | 해석 |
   |------|------|------|
   | `?limit=1&after={M1}` | `[M2]` | 커서 **바로 다음** 것 → 전진형 |
   | `?limit=2&after={M1}` | `[M3, M2]` | 오래된 쪽부터 2건, 배치 안에서만 내림차순 |

   "최신 100개" 가설이 맞았다면 각각 `[M4]`, `[M4, M3]`이 왔어야 한다. 다른 채널(3건)에서도 동일.
   커뮤니티 문서의 서술과도 일치한다 — Get Channel Messages는 "`before`에는 last id, `after`에는
   **first id**를 쓴다"(discord/discord-api-docs discussion #6789). 배열이 내림차순이므로 first id는
   배치의 최신 id이고, 그것으로 전진한다는 뜻이다.

   **영향 — 지연이 아니라 유실이었다.** 채널당 1회 수집에 100건만 받고 끝나는 것 자체는 느릴 뿐이지만,
   checkpoint가 **채널을 가로지르는 단일 커서**(`DiscordCollector.collect`)라는 점과 겹치면서 유실이 된다.
   단일 커서는 *모든 채널이 매 실행마다 끝까지 비워진다*는 전제에서만 안전한데, 이 버그가 그 전제를 깼다.

   > 채널 A(150건, 1년치) + 채널 B(3건, 어제)인 초기 수집: A는 오래된 100건에서 끊기고(6개월 전),
   > B는 3건 전부 수집된다. 커서는 두 채널의 최대 `occurredAt`이므로 **어제**로 점프하고,
   > 다음 실행은 `after=어제`라 **A의 6개월 전~어제 구간이 영구 유실**된다. `after` 커서는 앞으로만
   > 가고 `updateCursor`도 과거로 되돌리지 않아 재수집 경로가 없다.

   단일 채널 길드에서만 "유실 없이 느리기만" 했다. 다채널 + 100건 초과 채널이면 유실이다.
   전진형 교체로 채널이 매번 완전히 비워지므로 단일 커서의 전제가 복원된다.

   **마이그레이션**: 이 수정 이전에 수집한 적이 있는 프로젝트는 checkpoint가 손상 지점에 멈춰 있으므로
   `discord/discord_messages` 커서를 삭제하고 재수집해야 한다(새 코드만으로는 따라잡지 못한다).
   2026-08-11 시점에 Discord 연동 프로젝트는 존재하지 않아 실제 수행 대상은 없었다.

## 구현 시 확인 (미확정 — 제품 결정, 코드 착수를 막지 않는다)

1. **아카이브 스레드 누락 범위** — 활성 스레드만 수집할 때 실제로 얼마나 놓치는지 보고, 크면
   `threads/archived/public`을 증분 대상에 넣는다.
2. **포럼 채널(type 15)** — 각 포스트가 스레드인 구조라 별도 취급이 필요한지 확인. 팀이 포럼 채널을
   쓰지 않으면 범위 밖으로 둔다.

## 참고 (Discord Developer Docs, v10)

- Get Channel Messages(파라미터·권한·MESSAGE_CONTENT): docs.discord.com/developers/resources/message
- OAuth2(`bot` scope·동의 URL·토큰·revoke): discord.com/developers/docs/topics/oauth2
- Rate Limits(전역 50/s·버킷·429): docs.discord.com/developers/topics/rate-limits
- MESSAGE_CONTENT 권한 배경: github.com/discord/discord-api-docs/discussions/5412
