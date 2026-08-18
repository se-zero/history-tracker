# Jira 개인정보 보고 정책

Jira에서 수집한 개인정보(이름·이메일)를 Atlassian 규정에 따라 신고·갱신·삭제하는 방법과,
배포 시 필요한 봇 계정 등록 절차를 정리한다.

## 왜 이 의무가 있나

Atlassian에 OAuth 앱을 등록할 때 "개인정보를 저장하는가?"에 **Yes**로 답하면
**Personal Data Reporting API 구현이 의무**가 된다. 우리는 Jira 이슈를 수집하며 작성자·담당자의
이름과 이메일을 지식 그래프에 저장하므로 Yes다.

보고 대상은 우리 서비스 가입자가 아니라 **연동된 Jira 프로젝트에서 활동한 모든 사람**이다.
그들 대부분은 우리 서비스의 존재를 모른다 — 본인이 동의한 적 없는 서드파티에 개인정보가
남아 있는 상황을 통제하는 것이 이 API의 목적이다.

규칙은 두 가지다: **accountId마다 7일에 한 번 이상 보고**하고(더 자주 폴링 금지),
한 번에 최대 90개씩 보낸다.

## 개인정보는 어디에 있나

이름·이메일은 `Actor` 노드가 아니라 **`ActorAlias`(소스 계정 단위) 노드의 `pd_*` 필드**에 있다.
alias는 "어느 툴의 어느 계정에서 받았는지"를 아는 단위라, Jira 계정이 폐쇄되면
**그 계정에서 받은 것만 골라 지울 수 있다** — 같은 사람의 GitHub 활동 기록은 그대로 남는다.
`Actor.name`(표시 이름)은 alias들에서 파생·저장되는 유일한 값이고, 삭제와 재계산은
항상 한 트랜잭션으로 묶인다(나뉘면 "alias는 지웠는데 이름이 남는" 조용한 결함이 생긴다).

노드 구조 상세는 [graph-schema.md](graph-schema.md)·[actor-node-design.md](actor-node-design.md) 참고.

## 보고 사이클 (전자동)

backend 스케줄러(`JiraPersonalDataReportService`)가 매일 돌며, ai-engine(`graph/privacy.py`)이
그래프 반영을 담당한다.

1. **대상 조회** — 마지막 보고가 7일 넘은 accountId를 그래프에서 전역 조회 (프로젝트 중복 제거,
   획득 시각은 가장 오래된 값 — 최근 값을 보내면 그 사이 변경을 영영 놓친다)
2. **보고** — 봇 계정 토큰으로 Atlassian `report-accounts` API 호출
3. **응답 반영**
   - 응답 없음(변경 없음) → 보고 시각만 기록
   - `updated`(정보가 낡음) → 프로젝트 연동 토큰으로 Jira에서 재조회해 **모든 프로젝트의 사본을 갱신**.
     모든 프로젝트에서 확정 실패(권한 상실)일 때만 삭제하고, 일시 오류(429·5xx)가 섞여 있으면
     지우지 않고 다음 회차로 미룬다 — **삭제는 되돌릴 수 없으므로 항상 보수적으로 판단한다**
   - `closed`(계정 폐쇄) → 그 계정의 `pd_*`를 비우고 표시 이름을 남은 소스 기준으로 재계산

## 삭제 상태 두 가지

| | `closed` | `access_lost` |
|---|---|---|
| 계기 | Atlassian의 계정 폐쇄 통보 | `updated`인데 어느 프로젝트 토큰으로도 재조회 불가 |
| 성격 | **영구** — 어떤 자동 경로도 값을 되살리지 않는다 | **휴면** — 재연동 후 다음 수집이 자연 복구 |
| 보고 | 영원히 제외 | 복구되면 자동 복귀 |

`access_lost` 복구는 이름만이 아니라 이메일도 함께 되살아난다 — 재수집 이벤트에 이메일이
있으면 그 값으로 갱신하고, 없으면(예: Jira 담당자 이벤트) 지우기 전 이메일을 그대로 보존한다.

지워진 alias는 개인정보가 없으므로 보고 대상에서 자동으로 빠진다(사유 불문).
모든 소스가 지워진 사람의 표시 이름은 `(삭제된 사용자)`가 된다.

## 운영자가 알아야 할 것

- **삭제된 이름을 관리 화면에서 수동 입력하는 것은 막지 않는다.** 운영자가 타이핑한 값은
  Atlassian에서 받은 데이터가 아니라 운영자가 붙인 라벨이다(예: `김영희(전 PM)`).
  금지되는 것은 시스템이 **자동으로** 되살리는 것뿐이다.
- **이슈 제목·본문에 섞인 사람 이름은 지우지 못한다.** 식별할 키가 없고 지우면 그래프의 의미가
  훼손된다. 규정상 감수하는 한계다.
- **계정ID(aliases 원문)는 사용자 화면에 노출하지 않는다.** 액터 관리 UI 목록도, 그래프 뷰
  응답도 accountId 대신 표시 이름만 내려준다. LLM 도구(tool-calling 질의 경로)가 반환하는
  aliases는 동일인 매칭·판단 재료로 기능상 필요해 예외로 유지한다.
- **GitHub·Slack에는 동등한 의무 API가 없다.** 이 정책은 Jira 전용이다.
- **정합성 감사**: `POST /migrations/verify-actor-names` (ai-engine) — 모든 Actor의 표시 이름이
  alias 재계산값과 일치하는지 읽기 전용으로 검사한다. 삭제 배치 끝에도 자동 실행된다.

## 배포 절차 — 봇 계정 등록 (최초 1회)

보고는 특정 사용자·프로젝트의 토큰이 아니라 **봇 계정의 앱 수준 토큰**으로 한다.
사용자가 연동을 해제하거나 프로젝트를 지워도 보고 의무는 앱 전체의 것이라 계속 돌아야 하기 때문이다.
봇의 아이디·비밀번호는 시스템에 저장하지 않는다 — 아래 절차로 만든 **OAuth 토큰만**
DB(`app_credentials` 1행, 암호화)에 저장되고, 이후 배치가 갱신마다 회전시키며 영구히 쓴다.

### 준비물

- 전용 Atlassian 봇 계정. **자기 소유 사이트가 하나 있어야 한다**(무료 Jira 사이트면 됨 —
  사이트가 없는 계정은 OAuth 동의 자체가 거부된다). 고객 사이트에 초대할 필요는 없다.
- 봇 계정 메일함은 수신 확인이 가능해야 한다 — 사이트 휴면 삭제 경고, 재동의 안내가 온다.
- 개발자 콘솔에서 앱의 Personal Data Declaration이 **Yes**로 설정돼 있어야 한다.

### 등록 단계

1. **authorize URL 조립** — `client_id`·`redirect_uri`는 배포 환경의 값을 넣는다:
   ```
   https://auth.atlassian.com/authorize?audience=api.atlassian.com
     &client_id=<ATLASSIAN_CLIENT_ID>
     &scope=read:jira-work%20read:jira-user%20offline_access
     &redirect_uri=<ATLASSIAN_REDIRECT_URI>
     &response_type=code&prompt=consent&state=bot-consent
   ```
   scope는 backend가 쓰는 값과 같아야 한다 — 위는 기본값(`application.yaml`의 `atlassian.scopes`)
   그대로이고, `ATLASSIAN_SCOPES` 환경변수로 오버라이드한 배포라면 그 값을 쓴다(공백은 `%20`).
   **`offline_access`가 빠지면 refresh token이 발급되지 않아 배치가 돌 수 없다.**
2. **봇 계정으로 로그인한 브라우저**(시크릿 창 권장)에서 위 URL을 열고 동의한다.
3. **code 회수** — 동의 후 브라우저는 backend 콜백을 거쳐 프론트로 리다이렉트된다.
   backend는 모르는 state라 code를 사용하지 않으므로, **DevTools Network 탭(Preserve log 켜기)**
   에서 `/callback?code=...` 요청의 `code` 값을 복사한다. 수명 5분.
4. **내부 엔드포인트로 전달** (서버 간 토큰 인증):
   ```bash
   curl -s -i -X POST <backend>/api/v1/internal/atlassian/consent \
     -H "X-Internal-Service-Token: <INTERNAL_SERVICE_TOKEN>" \
     -H "Content-Type: application/json" \
     -d '{"code":"<code>"}'
   ```
   **204**면 저장 완료. `app_credentials` 테이블에 `ATLASSIAN` 1행이 생긴다.
5. **배치 활성화** — 환경변수 `ATLASSIAN_PDR_ENABLED=true` 후 backend 재시작.
   주기·실행 시각은 기본값(매일 03:30, 7일 주기)이면 충분하다.

### 재동의가 필요한 경우

토큰 갱신이 영구 실패하면(`re-consent required` 로그) 위 1~4를 한 번 다시 하면 된다.
발생 조건: 봇이 동의를 철회했거나, 배치가 90일 이상 멈춰 refresh token이 폐기됐거나,
봇 사이트가 휴면 삭제돼 grant가 무효화된 경우. 어느 경우든 그래프 데이터는 영향 없다.

### 환경변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `ATLASSIAN_PDR_ENABLED` | `false` | 보고 배치 on/off. false면 스케줄러가 아예 뜨지 않는다. **개발 단계에서는 끈다** |
| `ATLASSIAN_PDR_CRON` | 매일 03:30 | 배치 실행 시각. 매일 돌아도 7일 게이트는 계정별로 판단된다 |
| `ATLASSIAN_PDR_CYCLE_PERIOD` | `P7D` | 보고 주기. Atlassian 규정(7일)보다 길게 잡지 않는다 |
