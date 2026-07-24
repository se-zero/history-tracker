# CLAUDE.md — web-dashboard

## 역할

사용자 웹 프론트엔드 (Vite + React 18 + TypeScript, :5173). onboarding·sources·graph·chat·settings 화면을 제공하며,
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
    auth/projects/conversations/integrations/github/graph.ts

  hooks/            React Query 캡슐화 레이어 (컴포넌트는 여기로만 서버 상태 접근)
    queryKeys.ts      중앙 키 팩토리 — 모든 queryKey의 단일 출처
    useProjects · useConversations · useIntegrations · useGithub · useGraph · useSearch

  components/
    ui/             프리미티브 — MonoChip · InlineError · Field
    shell/          AppShell(라우팅·가드) · Sidebar · Topbar · ProjectSwitcher · ConversationList
    sources/        GitHubCard · TokenIntegrationCard(Jira·Slack 공용) · JiraCard · SlackCard · IngestStatus
    chat/           ChatStream · Message · Composer · ChatEmpty · ThinkingState · RelatedGraphPanel(답변 근거 서브그래프 패널) · messageStructured
    settings/       DangerZone(프로젝트 삭제·회원 탈퇴)
    graph/          GraphVis(d3-force SVG) · NodeDetail
    search/         SearchDialog — ⌘K 통합 검색(대화 + 그래프 노드, AppShell에서 마운트)
    BranchSelect · Icons · StatusView · ErrorBoundary

  pages/            라우트 진입점 — 얇게. 데이터 오케스트레이션만, 마크업은 components/<feature>/로
    Login · Onboarding · Chat · Sources · Settings · Account · Graph · AuthCallback · NotFound

  lib/              순수 유틸 — format(날짜·이니셜) · graphLayout(d3 시뮬레이션) · projectMark
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
