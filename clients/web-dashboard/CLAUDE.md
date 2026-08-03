# CLAUDE.md — web-dashboard

## 역할

사용자 웹 프론트엔드 (Vite + React 18 + TypeScript, :5173). onboarding·sources·성좌·chat·settings 화면을 제공하며,
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
    client.ts         axios 인스턴스 + 인터셉터 (access 토큰 부착, 401 시 refresh+rotation 재시도)
    그 외는 리소스당 모듈 1개 (auth · projects · conversations · integrations · github · graph · actors)

  hooks/            React Query 캡슐화 레이어 (컴포넌트는 여기로만 서버 상태 접근)
    queryKeys.ts      중앙 키 팩토리 — 모든 queryKey의 단일 출처
    그 외는 리소스당 use* 훅 1개. 연동만 셋으로 갈린다 —
    useIntegrations(조회·연결·해제) / useIntegrationOAuth(동의 리다이렉트) / useJira(사이트·프로젝트 선택)

  components/
    ui/             프리미티브 — MonoChip · InlineError · Field
    shell/          AppShell(라우팅·가드) · Sidebar · Topbar · ProjectSwitcher · ConversationList
    sources/        GitHubCard · JiraCard · SlackCard · IngestStatus · ActorManagementCard
                    useOAuthCallbackError — 동의 후 돌아온 리다이렉트의 실패 안내(URL 쿼리 캡처)
                    DisconnectIntegration — 해제 버튼 + 사전 경고 다이얼로그(세 카드 공용).
                    해제는 수집된 그래프까지 지우는 파괴적 동작이라 무엇이 삭제·유지되는지 먼저 보여준다
    chat/           ChatStream · Message · Composer · ChatEmpty · ThinkingState · RelatedGraphPanel(답변 근거 서브그래프 패널) · messageStructured
    settings/       DangerZone(프로젝트 삭제·회원 탈퇴)
    graph/          ConstellationVis(작업 성좌 Canvas 렌더러) · ConstellationDetail(열린 성좌 패널) · NodeDetail
                    GraphVis(d3-force SVG) — 채팅 RelatedGraphPanel 전용(그래프 탐색 페이지는 성좌로 대체됨)
    search/         SearchDialog — ⌘K 대화 검색(제목·메시지 본문, AppShell에서 마운트)
    landing/        공개 페이지 전용(랜딩 섹션들 · LandingHeader · LandingFooter)
                    LegalLayout — 약관·개인정보 공통 셸(헤더/푸터 재사용 + 산문 컬럼)
                    useLandingTheme — 랜딩 계열 다크/라이트 토글(앱 ThemeProvider와 독립)
    BranchSelect · Icons · StatusView · ErrorBoundary

  pages/            라우트 진입점 — 얇게. 데이터 오케스트레이션만, 마크업은 components/<feature>/로
    Onboarding · Chat · Sources · Settings · Account · Galaxy(작업 성좌 뷰, 내비 라벨은 "그래프 확인" — 그래프 재구축 트리거 포함) ·
    Actors · Landing · Terms · Privacy · AuthCallback · NotFound
    ※ /projects/:id/graph 는 /galaxy 로 리다이렉트한다(옛 링크 호환).
    ※ Landing은 비로그인 공개 소개 페이지(`/landing`) — AuthGate 밖이고 DESIGN.md를 기준으로 만든다.
    ※ Terms(`/terms`)·Privacy(`/privacy`)도 AuthGate 밖 공개 라우트다. 랜딩과 같은 `.lp` 스코프를
      쓰며 헤더·푸터를 공유한다(LegalLayout). 내용은 실제 수집 항목·권한 scope·보유 기간을
      반영하므로 **수집 코드나 purge 설정이 바뀌면 이 두 페이지도 함께 고친다**.
      Privacy 하나로 GitHub App·Slack·Atlassian 세 앱 심사의 개인정보처리방침 URL을 모두 감당한다
      (서비스별로 나누면 문서가 갈라진다). 제출 URL이 `#github`·`#slack`·`#jira` 앵커라
      **이 id는 바꾸지 않는다** — 바꾸면 심사에 제출된 링크가 깨진다.

  lib/              순수 유틸 — format(날짜·이니셜) · graphLayout(d3 시뮬레이션) · projectMark
                    constellation(성좌 배치: 별성 force + 위성 궤도) · canvasColor(CSS 토큰 → Canvas RGB)
                    heroConstellation · howItWorksGraph · graphExplorerPreview 는 랜딩 전용 도식 데이터
  auth/             AuthProvider(세션 상태) · tokenStorage(localStorage)
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
- 비동기 상태 업데이터(`setState((prev) => ...)`) 안에서 **가변 ref(`someRef.current`)를 다시 읽지 않는다** — 실행이 지연되어
  그 사이 ref가 바뀌면 터진다. 값을 미리 지역 변수로 캡처해 클로저에 가둔다.
- 주석은 한국어로 작성한다 (코드베이스 관행).
- UI/프론트엔드 작업 시 DESIGN.md를 먼저 읽고 모든 시각 결정을 거기서 파생시킨다.
