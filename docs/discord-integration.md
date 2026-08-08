# Discord 연동 계획 — 대화 아키타입 1호

`docs/integration-abstraction.md` Part B의 대화 아키타입 첫 커넥터다. Slack이 레퍼런스 구현이며,
ai-engine은 무변경이다(`Communication` 노드 재사용). 전체 순서는 「커넥터 엔드투엔드 체크리스트」를
따르고, 이 문서는 그 체크리스트를 Discord에 대입했을 때의 **결정 사항과 Discord 고유 설계**만 다룬다.

Discord Developer Docs 조사(2026-08, v10 기준)를 근거로 작성했다. 미확정 항목은 맨 아래
「구현 시 확인」에 모았다.

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
| 행 자격증명 | 사용자 OAuth 토큰 JSON(`access_token`·`refresh_token`·`expires_at`) | V12 제약(`그 외 → encrypted_credential 필수`)을 만족시키면서, 해제 시 grant 폐기에 실제로 쓰인다 |
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

## 3. backend — 연결

`com.history.backend.discord` 패키지를 새로 만든다. SPI는 **둘만** 구현한다.

- `IntegrationProvider.DISCORD("discord", "Discord")` 추가. DB 마이그레이션 불필요.
- `DiscordProperties`(@ConfigurationProperties) + `application.yaml` 블록, `DiscordClient`
  (code 교환, grant 폐기, 봇 길드 퇴장).
- `DiscordOAuthConnectFlow`
  - `buildAuthorizeUrl` — `https://discord.com/oauth2/authorize`에
    `client_id`·`response_type=code`·`redirect_uri`·`scope=bot identify`·`permissions=66560`·`state`.
  - `exchangeCode` — `POST /oauth2/token`(client_secret basic) → 응답에서 토큰과 **`guild` 객체**를
    꺼내 `OAuthConnection(자격증명 JSON, {guild_id, guild_name})`을 돌려준다.
    선택 단계가 없으므로 `pendingSelection`이 아니라 확정 형태다.
    **`guild` 객체가 응답에 오는 것은 실측 확정됐다** — 「확인 완료」 1번.
- `DiscordCredentialLifecycle`(`ProviderCredentialLifecycle`) — §2에서 넓힌 시그니처로
  봇 길드 퇴장 + `POST /oauth2/token/revoke`(`token_type_hint=refresh_token`). 실패는 삼킨다.
- `AccessTokenRefresher`·`IntegrationSelectionFlow`는 **만들지 않는다.**
  전자는 봇 토큰이 만료되지 않아서, 후자는 서버 선택이 Discord 동의 화면에서 끝나서다.
- `IntegrationResponse.displayName` switch에 `DISCORD` case — `selectionValue("guild_name")`.
  단계가 하나뿐이라 상위 단계 병기는 없다.
- 검증: `./gradlew test`

## 4. pipeline-worker — 수집

`source/discord` 패키지에 `DiscordCollector` · `DiscordRawService` · `DiscordNormalizer` ·
`DiscordRateLimiter`. `CollectionProvider.DISCORD` 추가 외에 오케스트레이션 계층은 무변경이다.

### 자격증명 해석의 비대칭

```java
// 수집 주체는 앱 수준 봇이다 — 행의 사용자 OAuth 토큰은 해제 시 grant 폐기용이라 여기서 쓰지 않는다.
Optional<RawFetchRequest> resolveFetchRequest(IntegrationRow integration) {
    String guildId = /* external_ref.guild_id — 없으면 IllegalStateException */;
    return Optional.of(new RawFetchRequest("Bot " + botToken, guildId, Map.of()));
}
```

`AuthHeaders`는 Bearer 전용이므로 `bot(String)`을 **추가**한다(기존 동작 불변이라 안전한 추가다).
행의 `encrypted_credential`을 복호화하지 않는 유일한 커넥터가 되므로, 위 주석으로 이유를 남긴다.

### 수집 흐름

```
GET /guilds/{guild_id}/channels                 # 텍스트 채널 (type 0·5) 필터
GET /guilds/{guild_id}/threads/active           # 활성 스레드 (스레드도 채널이다)
채널·스레드별:
  1회차: GET /channels/{id}/messages?after={snowflake(checkpoint)}&limit=100
  반환 개수 == 100(가득 찬 페이지)이면:
    반복: GET /channels/{id}/messages?before={직전 배치의 가장 오래된 id}&limit=100
    가장 오래된 id ≤ checkpoint 이거나 반환 개수 < 100이 될 때까지
→ normalize → publish → 전 채널 최대 occurredAt으로 checkpoint 1회 갱신
```

- **snowflake 변환** — Discord ID는 생성 시각을 품은 snowflake라, checkpoint의 `Instant`를
  `(epochMilli - 1420070400000) << 22`(Discord epoch 2015-01-01)로 바꿔 `after`에 넣는다.
  덕분에 **checkpoint 저장소의 `Instant` 계약을 그대로 두고도 서버사이드 증분 필터가 된다** —
  Slack이 채널 히스토리를 끝까지 훑던 문제가 여기서는 발생하지 않는다.
- **`after` 페이지네이션은 항상 "가장 최신"부터 내림차순으로 채운다(실측 확정, 아래 참고).**
  체크포인트 이후 새 메시지가 `limit`(최대 100)보다 많으면 **1회 호출로 전부 못 받는다** — 가장 최신
  100개만 오고 체크포인트 바로 다음 구간은 비어 있다. `before`·`after`·`around`가 상호 배타적이라
  한 호출에 섞을 수 없으므로, 가득 찬 페이지를 받으면 그 배치의 가장 오래된 id로 **`before`로 바꿔서**
  체크포인트에 닿을 때까지 내려가야 한다. 위 수집 흐름의 2단계가 이 보정이다.
- checkpoint: `discord/discord_messages` 단일 커서. Slack·Teams와 같은 이유로 채널을 가로질러
  마지막에 한 번만 전진시킨다.
- 봇이 접근 권한(View Channel·Read Message History)을 갖지 못한 채널은 403이거나 빈 결과다.
  **채널 단위 실패는 삼키고 다음 채널로 넘어간다** — 한 채널의 권한 누락이 전체 수집을 막으면 안 된다.
  단 발행 예외는 삼키지 않는다(계약대로 checkpoint를 전진시키지 않아야 재발행된다).
- 아카이브된 스레드(`GET /channels/{id}/threads/archived/public`)는 1차 범위에서 제외한다 —
  활성 스레드만으로 시작하고, 누락이 문제가 되면 확장한다(「구현 시 확인」 1번).

### NormalizedEvent 매핑 (`docs/normalized-event.md` 계약)

| 계약 필드 | Discord 값 | 비고 |
|-----------|-----------|------|
| `source` | `DISCORD` | |
| `properties.url` (자연키) | `https://discord.com/channels/{guild_id}/{channel_id}/{message_id}` | Discord는 URL 필드를 주지 않아 **조립**한다. 결정적이고 프로젝트 안에서 고유 |
| `properties.body` | `content` | 이미 평문이다(Teams의 HTML 변환이 불필요). `<@123>` 멘션만 표시 이름으로 치환 |
| `properties.channel` | 채널 `name` (스레드면 스레드 `name`) | |
| `properties.conversation_id` | 스레드 안 메시지면 스레드 채널 id / 답글(type 19)이면 `message_reference.message_id` / 그 외 자기 `id` | |
| `properties.created_at` · `occurredAt` | `timestamp` | `edited_timestamp`는 커서를 되돌리지 않도록 쓰지 않는다 |
| `actor.id` | `author.id` (snowflake) | 안정적·고유. 사용자명은 변경 가능하므로 id로 쓰지 않는다 |
| `actor.name` | `author.global_name` (없으면 `username`) | |
| `actor.email` | **항상 `null`** | 봇은 타인의 이메일을 얻을 수 없다. §7 참고 |
| `refs` | `content`에 `RefsExtractor` 그대로 | 평문이라 전처리가 필요 없다 |

정규화 제외: `author.bot == true`(봇·웹훅 메시지), 시스템 메시지(`type`이 0·19가 아닌 것).
Slack normalizer의 관례와 같다.

### Rate limit

`DiscordRateLimiter`는 두 층을 지킨다.

- **전역**: 봇당 초당 50요청. 보수적으로 고정 딜레이를 깔고 시작한다.
- **버킷별**: 라우트 rate limit이 최상위 리소스(여기서는 `channel_id`)별로 잡히므로, 채널을 순차
  처리하는 현재 설계에서는 충돌이 적다. `X-RateLimit-Remaining`·`X-RateLimit-Reset-After`를 보고
  남은 양이 적으면 대기한다.
- **429**: 응답 본문 `retry_after`(초, 소수)와 `X-RateLimit-Scope`를 존중해 재시도한다.
  GitHub 수집기의 `X-RateLimit-Reset` 대기와 같은 패턴이다.

## 5. web-dashboard — 화면

`sourceCatalog.tsx`의 `discord` 항목(브랜드 마크 `DiscordMark`와 함께 이미 있다)을
`status: "wired"`로 바꾸고 `connect: "oauth"`와 `deletedData`를 채우면 끝이다.
선택 단계가 없으므로 `OAuthSourceCard`는 Slack과 동일하게 렌더한다.

`deletedData` 문구에는 **봇이 서버에서 나간다**는 사실을 넣는다(§2 구현 후) — 사용자가 해제 버튼을
누르면 서버 멤버 목록에서도 봇이 사라지므로, 미리 알리는 편이 맞다.
검증: `npm run typecheck && npm run build`.

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

## 8. 문서 동반 갱신 (커넥터 PR에 포함)

- `docs/data-collection.md` — Discord 섹션(수집 대상·snowflake 증분·rate limit·트레이드오프).
- `docs/integration-abstraction.md` — Part B 표의 Discord 완료 표시.
- `services/backend/CLAUDE.md`(패키지 구조에 `discord`) · `services/pipeline-worker/CLAUDE.md`
  (`source.discord` 행, 라우팅 표, checkpoint 목록, 봇 토큰 설정).
- §2를 선행 PR로 처리했다면 `services/backend/CLAUDE.md`의 SPI 표도 함께 고친다.
- `docs/graph-schema.md`·`docs/DB.md`는 변경 없음(새 노드·테이블 없음)을 확인만 한다.

## 9. 검증 계획

- 단위: backend·pipeline-worker `./gradlew test`, 프론트 `typecheck && build`.
- **본문 존재 단언** — MESSAGE_CONTENT를 끈 채로도 수집은 성공해 버리므로, 정규화 테스트에서
  `body`가 비지 않음을 반드시 확인한다(§1의 조용한 실패 지점).
- 실기동 시나리오(무료 테스트 서버): 연결 → 동의 화면에서 서버 선택 → 초기 수집 →
  그래프에 DISCORD Communication 확인 → 새 메시지·스레드 답글 추가 후 PR 머지 웹훅으로 증분 →
  봇이 못 보는 비공개 채널이 수집을 막지 않는지 → 해제 시 DISCORD 노드만 삭제되고 **봇이 서버에서
  나가는지** 확인.

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
   등록을 그대로 받았다. Slack·Jira와 달리 로컬 개발에 터널이 필요 없다.
3. **MESSAGE_CONTENT 적용 확인 — 확정.** `GET /channels/{id}/messages`를 봇 토큰으로 호출한 실제 응답:
   `type: 0`(일반 메시지) 세 건 모두 `content`에 실제 텍스트가 채워져 왔다. `type: 7`(멤버 가입 시스템
   알림)만 `content`가 빈 문자열인데, 이는 인텐트 미적용이 아니라 시스템 메시지 자체가 본문이 없는
   정상 동작이다 — §4 정규화 제외 규칙(`type`이 0·19가 아닌 것 제외)이 정확히 걸러야 할 사례다.

   부수 발견: 사람이 자동완성 없이 그냥 타이핑한 `@이름`은 `mentions` 배열이 비고 `content`에도
   평문 그대로 남는다(`<@id>` 형식이 아니다). 실제 Discord 멘션 기능을 쓴 경우만 `mentions`가 채워지고
   `content`에 `<@1535516144784642048>` 같은 snowflake 형식이 들어간다. **멘션 치환은 `mentions`
   배열이 비어있지 않을 때만 동작하면 된다** — 나머지 `@텍스트`는 이미 최종 형태라 손댈 필요 없다.

4. **`after` 페이지네이션의 정렬 — 확정.** 체크포인트 id로 `after` 호출 시, 반환된 메시지는 전부
   체크포인트보다 최신이었고(필터링 자체는 맞다), 순서는 **최신→과거 내림차순**이었다(오름차순 아님).
   `limit`보다 적은 결과였어서 "100개 넘는 백로그"에서 최신 쪽만 잘려 오는지까지는 이 테스트로 직접
   확인되지 않았지만, 이 정렬 방향과 Discord 문서의 커서 상호 배타 규칙을 근거로 §4 수집 흐름에
   `before` 전환 로직을 반영했다.

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
