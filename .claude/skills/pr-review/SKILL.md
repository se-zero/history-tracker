---
name: pr-review
description: GitHub PR을 리뷰하고 결과를 PR에 코멘트로 남긴다. CI(.github/workflows/code-review.yml)에서 자동 실행되는 것이 주 용도이며, 로컬에서 `/pr-review <owner/repo/pull/N>`으로 예행 확인할 수도 있다. 답하는 질문은 하나 — "이걸 머지해도 되나?"
---

# PR Review 봇

이 스킬은 **PR이 머지 가능한지**만 판정한다. 로컬 `branch-review`와 역할이 갈려 있다.

| | 로컬 `branch-review` | **이 스킬** |
|---|---|---|
| 답할 질문 | "뭘 더 고칠까?" | **"머지해도 되나?"** |
| 독자 | 작성자 혼자 | **팀 — 코멘트가 영구히 남는다** |
| 오탐 비용 | 거의 0 | **높다 (팀원이 읽고 판단해야 함)** |
| 수정 비용 | 0 | 커밋 추가 + 재리뷰 |

**오탐 1건의 비용이 팀원의 시간이다.** 확신이 없으면 등급을 낮춰서 종합 코멘트에 적되,
**인라인으로 달지 않는다.**

## 시작 전 필수

**`.claude/review-severity.md`를 읽는다.** 등급 정의(🔴/🟠/🟡/💡), 판정 규칙,
**"필터는 찾을 때가 아니라 달 때 건다"** 원칙이 거기에 있다. 이 스킬에 복사해 두지 않는다.

## 출력 규칙 (전 과정)

- **한국어**로 쓴다.
- **짧게 쓴다.** 인라인 코멘트는 3~5문장, 종합 코멘트는 화면 한 장. 근거를 늘어놓지 말고 결론을 쓴다.
- **서브에이전트를 쓰지 않는다.** 차원이 4개뿐이고 PR 1건이라 한 컨텍스트에서 본다.
  (차원별 분리는 무엇을 놓치는지 데이터가 쌓인 뒤에 결정한다.)
- 자기 결과를 다시 검증하라는 지시는 이 문서에 **일부러 없다.** 과잉 검증이 생긴다.

---

## 0단계: 대상 파악과 중단 조건

인자 형식: `/pr-review [--comment] [<owner>/<repo>/pull/<N>]`

- `--comment`: PR에 실제로 게시한다. **없으면 결과를 출력만 하고 아무것도 게시하지 않는다** (로컬 예행용).
- 대상이 없으면 `GITHUB_REPOSITORY` 환경변수와 현재 브랜치의 PR에서 유추한다.

```bash
gh pr view <N> --repo <owner/repo> --json number,title,body,isDraft,baseRefName,headRefName,author,files
```

**Draft이면 여기서 중단한다.** 아무것도 게시하지 않고 "Draft이므로 건너뜁니다"만 남긴다.
Ready 전환(`ready_for_review`) 때 다시 돌게 되어 있다.

> ⚠️ **"이미 Claude 코멘트가 있으면 건너뛴다"를 하지 않는다.**
> 이 봇은 push마다(`synchronize`) 다시 돈다. 작성자가 지적을 반영한 뒤 **🟢 재판정을 받는 것**이
> 재실행의 목적이다. 기존 코멘트 유무로 중단하면 그 경로가 막힌다.

## 1단계: 입력 수집

```bash
# diff — 리뷰의 1차 입력
gh pr diff <N> --repo <owner/repo>

# 변경 파일 목록
gh pr view <N> --repo <owner/repo> --json files --jq '.files[].path'

# 커밋 메시지 (behavioral 보조 입력)
gh pr view <N> --repo <owner/repo> --json commits --jq '.commits[].messageHeadline'

# 이미 달려 있는 인라인 코멘트 — 중복 방지용 (3단계에서 씀)
gh api repos/<owner>/<repo>/pulls/<N>/comments --paginate \
  --jq '.[] | "\(.path):\(.line // .original_line)"'

# 기존 종합 코멘트 (sticky 갱신 대상)
gh api repos/<owner>/<repo>/issues/<N>/comments --paginate \
  --jq '.[] | select(.body | contains("<!-- pr-review-bot -->")) | .id'
```

**PR 본문(`body`)은 behavioral 차원의 핵심 입력이다.** 반드시 읽는다.
본문이 비어 있거나 템플릿 그대로면 behavioral 차원은 "검증 불가"로 처리하고 그 사실을 종합 코멘트에 적는다.

### 읽기 범위 — diff 안에 머물지 않는다

**레포 전체가 체크아웃되어 있으므로 어떤 파일이든 읽을 수 있다.**
diff만 보면 cross-file 차원이 원천적으로 작동하지 않는다. 최소한 다음은 **직접 열어본다.**

- 바뀐 함수·메서드의 **호출부**
- 바뀐 DTO·이벤트 필드의 **반대편 소비자** (예: `NormalizedEvent` → ai-engine `graph/event_handler.py`)
- 관련 계약 문서 (`docs/normalized-event.md`, `docs/graph-schema.md`, `docs/DB.md`)
- 변경된 서비스의 `CLAUDE.md`

---

## 2단계: 리뷰 — 4차원

> **찾기 단계에서는 등급으로 거르지 않는다.** 사소해 보여도 일단 다 적는다.
> 등급별 처리는 3단계에서 한다. (`review-severity.md` §3)

### ① correctness

- 코드가 의도한 대로 동작하는가?
- 엣지 케이스(null, 빈 컬렉션, 경계값, 중복 이벤트, 재시도) 누락은 없는가?
- 런타임 오류, 경쟁 조건, 오프-바이-원, 잘못된 조건문은 없는가?
- 실패 경로에서 상태가 깨지지 않는가? (부분 저장, 롤백 누락)
- 테스트가 삭제·`@Disabled`·skip 처리되지 않았는가?

### ② security + privacy

> **이 레포에서 유일하게 "머지 후 발견하면 진짜 아픈" 축이다.** 토큰·암호화 키·개인정보가 코드에 실제로 흐른다.

- 시크릿·API 키·토큰이 코드나 설정에 **하드코딩**되어 있는가?
- 토큰·암호화 키·webhook 시크릿이 **로그에 원문으로** 찍히는가? (예외 메시지·`toString()` 포함)
- **권한 검사 누락(IDOR)** — 요청자가 그 project/integration의 소유자인지 확인하는가?
- webhook 서명 검증, OAuth state 검증을 건너뛰지 않았는가?
- 저장하면 안 되는 원문(메시지 본문, 이메일, 실명)이 새로 저장·전파되는가?
- LLM 프롬프트·로그·응답 payload로 개인정보가 새어 나가는가?
- `docs/jira-personal-data-policy.md`의 삭제 규칙, 연동 해제(revoke) 시 삭제가 빠지지 않았는가?

### ③ cross-file 정합성

> 서비스가 4개(backend / pipeline-worker / ai-engine / web-dashboard)로 갈려 있고
> 계약이 파일을 넘나든다. **로컬 리뷰에서 가장 얇은 곳이라 봇의 존재 이유에 가깝다.**

- `NormalizedEvent` 필드를 바꿨는데 **ai-engine consumer/handler**가 그대로인가?
  `docs/normalized-event.md`는 갱신됐는가?
- Flyway migration과 JPA Entity가 어긋나지 않는가? (`ddl-auto: validate` — 어긋나면 기동 실패)
- backend DTO를 바꿨는데 web-dashboard의 타입·호출부가 그대로인가?
- 새 provider인데 checkpoint provider 제약(CHECK 제약·enum)에 값이 빠지지 않았는가?
- 설정 키를 추가했는데 `infra/docker/.env.example`·compose에 빠지지 않았는가?
- 그래프 스키마를 바꿨는데 조회 tool·쿼리가 옛 라벨/속성을 그대로 쓰는가?

### ④ behavioral — 글로 적힌 의도 대비 실제 동작

> **PR에서만 가능한 차원이다.** 로컬에선 의도가 대화에만 있지만, PR엔 본문·제목·커밋 메시지가
> 글로 남아 있다. 비용이 거의 0이므로 반드시 수행한다.

- **본문이 X를 한다는데 diff는 Y를 하는가?**
- 본문·시나리오에 적힌 것 중 **코드에 없는 것**은? (하겠다고 적어놓고 빠진 항목)
- diff에 있는데 본문 어디에도 설명이 없는 **의도치 않은 변경**은? (특히 설정값·의존성·테스트 설정)
- `확인 필요` 체크박스가 실제 변경 파일과 어긋나는가?
  (migration을 건드렸는데 체크가 없다 → 지적 대상)

> **behavioral은 *일치* 검사이지 *타당성* 검사가 아니다.**
> "본문이 X라 하고 diff가 X면 통과"다. **의도 자체가 옳은지는 판단하지 않는다** — 사람(리뷰어)의 몫이다.
> "이 기능을 지금 하는 게 맞나" 같은 지적은 하지 않는다.

### 보지 않는 것 — 명시적 제외

**단순화 · 재사용성 · 스타일 · 네이밍 · 아키텍처 · 컨벤션은 지적하지 않는다.**

이미 커밋·푸시된 코드다. "3줄로 줄일 수 있어요"를 달면 반영에 커밋이 하나 더 붙고,
2인 팀에선 사람이 더 빨리 판단한다. 이 영역은 **로컬 `branch-review`에서 소진되었다고 전제한다.**

동작에 영향이 없는 개선은 발견해도 **적지 않는다.** (💡 Note로도 달지 않는다.)
단, 그 "개선"이 실제 버그를 유발하면 그건 스타일이 아니라 correctness다 — 그때는 적는다.

---

## 3단계: 게시

**여기서 처음으로 등급 필터를 건다.** 2단계에서 모은 전 항목을 `review-severity.md` 기준으로
**한 번에** 훑어 등급을 확정한다(보정 지점 = 여기, 한 번만). 그 뒤 아래 규칙으로 나눈다.

| | 인라인 코멘트 | 종합 코멘트 |
|---|---|---|
| 🔴 Critical | ✅ (diff 라인에 걸 수 있으면) | 요약 1줄 |
| 🟠 Major | ✅ (diff 라인에 걸 수 있으면) | 요약 1줄 |
| 🟡 Minor | ❌ | 접어서 |
| 💡 Note | ❌ | 접어서 |

### 3-1. 인라인 코멘트

**도구: `mcp__github_inline_comment__create_inline_comment`.**
`gh api`로 review 객체를 직접 만들지 않는다.

지켜야 할 제약 네 가지:

1. **Critical / Major만.** Minor까지 인라인으로 달면 2인 팀 PR이 코멘트 30개짜리가 된다.
2. **총 8건 상한.** 인라인+종합의 지적 합계가 8건을 넘으면, 넘는 분량은 종합 코멘트 본문에
   목록으로만 적는다. 상한에 걸렸다는 사실 자체를 종합 코멘트에 명시한다.
3. **앵커 제약 — diff hunk 안의 라인에만 달 수 있다.**
   "이 필드를 바꿨는데 ai-engine consumer는 안 고쳤다"처럼 **그 파일이 diff에 없는 지적**은
   인라인이 불가능하다. **종합 코멘트 본문에 파일 경로와 함께** 적는다.
   → cross-file 지적 상당수가 여기 해당한다. 잃어버리지 않도록 주의한다.
4. **중복 방지 — 기존 인라인과 같은 `파일:라인`이면 건너뛴다.**
   1단계에서 조회한 목록과 대조한다. 이 봇은 push마다 다시 도는데, 안 고쳐진 지적은
   그때마다 또 달리게 된다. 건너뛴 항목은 종합 코멘트의 "미해결" 목록에 남긴다.

도구 호출이 실패하면(라인이 hunk 밖 등) **조용히 버리지 말고** 해당 지적을 종합 코멘트로 옮긴다.

### 3-2. 종합 코멘트 (sticky — 하나를 계속 갱신)

**push마다 새 글이 쌓이지 않게 기존 코멘트를 수정한다.**

```bash
# 본문을 파일로 쓴 뒤 (따옴표·개행 문제 회피)
# 기존 코멘트가 있으면 PATCH
gh api -X PATCH repos/<owner>/<repo>/issues/comments/<comment_id> -F body=@review.md
# 없으면 POST
gh api -X POST  repos/<owner>/<repo>/issues/<N>/comments      -F body=@review.md
```

본문 형식 (첫 줄의 마커는 **반드시** 포함 — 다음 실행이 이걸로 자기 코멘트를 찾는다):

```markdown
<!-- pr-review-bot -->
## 🟠 수정 필요 — Major 2건

<한 문단. 이 PR이 무엇을 하는지, 왜 이 판정인지.>

### 🔴 Critical (N)
- **`파일:라인`** 한 줄 요약 ([인라인](링크))

### 🟠 Major (N)
- **`파일:라인`** 한 줄 요약 ([인라인](링크))
- **[diff 밖] `services/ai-engine/graph/event_handler.py`** — 한 줄 요약
  <인라인을 걸 수 없어 여기 적는 항목. 파일 경로를 반드시 쓴다.>

<details><summary>🟡 Minor (N) · 💡 Note (N)</summary>

- **`파일:라인`** 한 줄 요약

</details>

### behavioral
- 본문 대비: <일치 / 어긋난 점>
- 본문에 있으나 코드에 없음: <항목 또는 "없음">
- `확인 필요` 체크 대비 변경 파일: <일치 / 어긋남>

---
<sub>차원: correctness · security+privacy · cross-file · behavioral —
단순화·네이밍·아키텍처는 로컬 리뷰의 몫이라 보지 않습니다.
등급 정의: `.claude/review-severity.md`</sub>
```

- 제목의 판정은 **기계적으로** 낸다: `Critical>0 → 🔴 머지 금지` / `Major>0 → 🟠 수정 필요` / `그 외 → 🟢 머지 가능`
- **🟢일 때도 종합 코멘트를 갱신한다.** 작성자가 지적을 반영한 뒤 받는 🟢이 재실행의 목적이다.
- 지적이 하나도 없으면 본문을 짧게 — 판정 한 줄 + 무엇을 봤는지 한 줄이면 충분하다.

### 3-3. 하지 않는 것

- **승인(approve)·변경 요청(request changes)을 제출하지 않는다.** 승인은 사람(팀원 B)이 한다.
- **머지하지 않는다.** 머지는 작성자 A가 한다.
- 코드를 수정하거나 커밋·푸시하지 않는다. **리뷰만 한다.**
- 라벨을 붙이거나 리뷰어를 지정하지 않는다.

---

## 4단계: 로그 출력

게시한 뒤(또는 `--comment` 없이 예행일 때) 실행 로그에 한국어 요약을 남긴다.

```
판정: 🟠 수정 필요
Critical 0 · Major 2 · Minor 3 · Note 1
인라인 게시 2건 / 중복으로 건너뜀 1건 / diff 밖이라 종합으로 옮김 1건
종합 코멘트: 갱신 (id=12345)
```
