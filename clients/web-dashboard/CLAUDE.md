# CLAUDE.md — web-dashboard

## 역할

사용자 웹 프론트엔드 (Vite + React 18 + TypeScript, :5173). onboarding·sources·그래프·chat·settings 화면을 제공하며,
backend(:8080) API만 호출한다. 전체 아키텍처는 루트 [CLAUDE.md](../../CLAUDE.md) 참고.

## 실행 / 빌드

전체 스택은 `infra/docker`의 docker-compose로 띄운다(루트 CLAUDE.md). 로컬 단독 실행:

```bash
npm install
npm run dev        # http://localhost:5173, /api/* 는 backend로 proxy (vite.config.ts)
npm run build      # tsc -b && vite build → dist/
npm run typecheck  # tsc -b --noEmit
npm run preview    # 빌드 결과 미리보기
```

- API 베이스는 `VITE_API_BASE_URL`(기본 `/api/v1`), dev proxy 대상은 `VITE_API_PROXY`로 바꾼다.
- 테스트 러너는 아직 없다. **변경 후 검증은 `npm run typecheck && npm run build`** 가 기준선이다.

## 디렉터리 구조

```
src/
  main.tsx          부트스트랩 — QueryClient · BrowserRouter · ThemeProvider · ErrorBoundary
  App.tsx           라우트 정의 + AuthGate(인증 가드) + 루트 리다이렉트

  api/              backend 엔드포인트별 axios 클라이언트 (리소스 단위로 얇게)
    client.ts         axios 인스턴스 + 인터셉터 (access 토큰 부착, 401 시 쿠키 refresh·rotation 재시도)
    그 외는 리소스당 모듈 1개 (auth · projects · conversations · integrations · github · graph · actors)

  hooks/            React Query 캡슐화 레이어 (컴포넌트는 여기로만 서버 상태 접근)
    queryKeys.ts      중앙 키 팩토리 — 모든 queryKey의 단일 출처
    그 외는 리소스당 use* 훅 1개. 연동만 셋으로 갈린다 —
    useIntegrations(조회·연결·해제) / useIntegrationOAuth(동의 리다이렉트, provider는 mutate 인자) /
    useSelectionFlow(연동 대상 다단 선택 — provider가 선언한 단계·후보 구독, 일괄 확정)

  components/
    ui/             프리미티브 — MonoChip · InlineError · Field
    shell/          AppShell(라우팅·가드) · Sidebar · Topbar · ProjectSwitcher · ConversationList
    sources/        GitHubCard(설치 기반 전용) · OAuthSourceCard(OAuth 소스 공용 행 — backend가
                    선언한 선택 단계를 그대로 렌더, provider별 카드를 만들지 않는다) ·
                    sourceCatalog(소스 메타 단일 출처 — 9종의 마크·설명이 등재돼 있고, 항목은
                    status로 갈리는 판별 유니온이다. 신규 소스의 프론트 작업은 보통 항목을 추가하고
                    status를 "wired"로 두면서 connect·deletedData를 채우는 게 전부다(연동 전에 타일
                    자리만 먼저 잡으려면 "planned"로 등재했다가 나중에 바꾼다). **"wired"라고
                    선언하면 두 필드가 없을 때 컴파일이 깨진다** — 반쪽 배선(연결 버튼이 no-op이거나
                    해제 고지가 뭉뚱그려지는 무증상 실패)을 타입으로 막는 자리다.
                    선택 필드 consentSideEffect는 "동의 승인만으로 provider 쪽에 생겨 서버가 되돌리지
                    못하는 것"을 적는다(Discord: 봇이 서버에 추가된다) — already_connected 안내에
                    덧붙는다. 공용 문구 코드에 provider 분기를 두지 않으려고 여기에 선언형으로 둔다
                    — 전체 순서는 docs/integration-abstraction.md 「커넥터 엔드투엔드 체크리스트」)
                    · IngestStatus · ActorManagementCard
                    useOAuthCallbackError — 동의 후 돌아온 리다이렉트의 실패 안내(URL 쿼리 캡처)
                    DisconnectIntegration — 해제 버튼 + 사전 경고 다이얼로그(연동 행 공용).
                    해제는 수집된 그래프까지 지우는 파괴적 동작이라 무엇이 삭제·유지되는지 먼저 보여준다
    chat/           ChatStream · Message · Composer · ChatEmpty · ThinkingState · RelatedGraphPanel(답변 근거 서브그래프 패널) · messageStructured
    settings/       DangerZone(프로젝트 삭제·회원 탈퇴) · PlanCard(계정 플랜·전환 코드)
    graph/          WorkUnitCanvas(작업 단위 뷰 Canvas 렌더러) · ClusterDetail(열린 작업 단위 묶음 패널) · NodeDetail
                    GraphVis(d3-force SVG) — 채팅 RelatedGraphPanel 전용(그래프 탐색 페이지는 작업 단위 뷰로 대체됨)
    search/         SearchDialog — ⌘K 대화 검색(제목·메시지 본문, AppShell에서 마운트)
    landing/        공개 페이지 전용(랜딩 섹션들 · LandingHeader · LandingFooter)
                    LegalLayout — 약관·개인정보 공통 셸(헤더/푸터 재사용 + 산문 컬럼)
                    useLandingTheme — 랜딩 계열 다크/라이트 토글(앱 ThemeProvider와 독립)
    BranchSelect · Icons · StatusView · ErrorBoundary

  pages/            라우트 진입점 — 얇게. 데이터 오케스트레이션만, 마크업은 components/<feature>/로
    Onboarding · Chat · Sources · Settings · Account · GraphPage(작업 단위 뷰, 내비 라벨은 "그래프 확인" — 그래프 재구축 트리거 포함) ·
    Actors · Landing · Terms · Privacy · AuthCallback · NotFound
    ※ Landing은 비로그인 공개 소개 페이지(`/landing`) — AuthGate 밖이고 DESIGN.md를 기준으로 만든다.
    ※ Terms(`/terms`)·Privacy(`/privacy`)도 AuthGate 밖 공개 라우트다. 랜딩과 같은 `.lp` 스코프를
      쓰며 헤더·푸터를 공유한다(LegalLayout). 내용은 실제 수집 항목·권한 scope·보유 기간을
      반영하므로 **수집 코드나 purge 설정이 바뀌면 이 두 페이지도 함께 고친다**.
      본문은 언어별 컴포넌트로 갈린다 — 이용약관은 `components/landing/legal/`의
      `TermsBodyKo.tsx`·`TermsBodyEn.tsx`, 개인정보처리방침도 같은 방식으로
      `PrivacyBodyKo.tsx`·`PrivacyBodyEn.tsx`로 갈려 있다 — 위 내용이 바뀌면 해당 언어별
      파일도 함께 고친다.
      Privacy 하나로 연동 앱 심사의 개인정보처리방침 URL을 모두 감당한다(서비스별로 나누면
      문서가 갈라진다). 제2조에 소스별 앵커를 두며 — `#github`·`#slack`·`#jira`·`#discord`·
      `#google-chat`·`#notion` — 앞의 셋은 **이미 심사에 제출돼 있어 그 id는 바꾸지 않는다**(바꾸면
      제출된 링크가 깨진다). **새 커넥터를 배선하면 제1조 자격증명 행·수집 기록 목록과
      제2조 소스 블록을 함께 추가한다** — 고지 없이 수집하는 상태가 배포 기준 공백이다.
      Google Chat의 `directory.readonly`는 민감 범위라 OAuth 검증에서 이 URL을 요구한다.

  lib/              순수 유틸 — format(날짜·이니셜) · graphLayout(d3 시뮬레이션) · projectMark
                    workUnitLayout(작업 단위 배치: 작업 단위 force + 구성 노드 반경) · canvasColor(CSS 토큰 → Canvas RGB)
                    heroBackdropGraph · howItWorksGraph · graphExplorerPreview 는 랜딩 전용 도식 데이터
                    remarkLocalTime — 답변 본문의 UTC ISO를 뷰어 현지 시간으로 바꿔 그리는 remark 플러그인.
                    **시각 표시는 전적으로 프론트 책임이다** — ai-engine은 UTC ISO 정준값만 보낸다
                    (서버가 타임존을 굳히면 저장된 답변이 그 타임존에 영구히 묶인다, docs/tools.md).
                    로캘은 format.ts의 `UI_LOCALE` 한 곳에서만 정한다 — 언어 분리(i18n) 도입 시
                    이 상수만 설정 훅으로 교체하면 포맷터가 함께 따라온다. 타임존은 로캘과 별개로
                    기기 설정이 자동 적용되므로 어디에도 하드코딩하지 않는다.
                    **언어 분리 작업 전에 docs/i18n.md를 읽는다** — 로캘·타임존을 묶으면 안 되는
                    이유와 시각 표시 계약(날짜 단독·코드블록 미변환 등)이 거기 있다
  auth/             AuthProvider(세션 상태) · tokenStorage(access는 메모리만. 레거시 localStorage 키는 기동 시 삭제)
  theme/            ThemeProvider (다크/라이트)
  types/            api.ts · graph.ts (백엔드 응답 타입)
  styles/           index.css(@import 진입점) + 기능별 분할 CSS, tokens.css(디자인 토큰)
```

## 코딩 규칙

- **서버 상태는 `hooks/` 레이어로만** 접근한다. 컴포넌트에서 `useQuery`/`useMutation`에 **리터럴 queryKey를 쓰지 않는다** —
  키는 `hooks/queryKeys.ts` 팩토리에서만 만든다. 새 조회/뮤테이션은 `hooks/`에 `use*` 훅으로 추가하고 무효화도 그 안에 둔다.
- **페이지(`pages/`)는 얇게** 유지한다. 화면 마크업·로컬 상태·하위 컴포넌트는 `components/<feature>/`에 둔다.
  거대 페이지를 만들지 말 것(이전 SourcesPage 622줄을 책임별로 분해한 게 현재 구조다).
- 반복되는 인라인 스타일·에러 문구·폼 래퍼는 **`components/ui` 프리미티브**(MonoChip·InlineError·Field)를 쓴다.
- **스타일은 글로벌 className + CSS 변수**다. 색·간격·radius는 `styles/tokens.css`의 변수만 쓰고 **hex 하드코딩 금지**.
  규칙은 해당 `styles/<feature>.css`에 추가하고 `styles/index.css`에 `@import`로 등록한다(외부 폰트 @import는 index.css 최상단).
- import 경로는 `@/` alias를 쓴다 (`@/components/...`).
- **backend API만 호출**한다(`api/`). snake_case ↔ camelCase 매핑은 `api/` 모듈에서 처리하고, 컴포넌트는 camelCase만 본다.
- 인증 토큰은 `api/client.ts` 인터셉터(자동 refresh·rotation, 401 처리)에 위임한다 — 컴포넌트에서 토큰을 직접 다루지 않는다.
  access는 메모리, refresh는 httpOnly 쿠키(`ht_refresh`, Path `/api/v1/auth`). **API 베이스는 같은 오리진
  (`/api/v1`)이어야 쿠키가 붙는다** — `VITE_API_BASE_URL`을 다른 호스트로 두면 세션이 유지되지 않는다.
  기존 localStorage 키(`ht.access_token`·`ht.refresh_token`)는 모듈 로드 시 지운다.
- 비동기 상태 업데이터(`setState((prev) => ...)`) 안에서 **가변 ref(`someRef.current`)를 다시 읽지 않는다** — 실행이 지연되어
  그 사이 ref가 바뀌면 터진다. 값을 미리 지역 변수로 캡처해 클로저에 가둔다.
- 주석은 한국어로 작성한다 (코드베이스 관행).
- UI/프론트엔드 작업 시 DESIGN.md를 먼저 읽고 모든 시각 결정을 거기서 파생시킨다.
