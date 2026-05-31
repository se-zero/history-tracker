# History Tracker — Web

History Tracker 프론트엔드 (Vite + React 18 + TypeScript).

## 개발

```bash
npm install
npm run dev      # http://localhost:5173
```

`/api/*` 요청은 `vite.config.ts`의 proxy에 의해 backend(`http://localhost:8080`)로 포워딩됩니다. 다른 주소를 쓰려면:

```bash
VITE_API_PROXY=http://localhost:8090 npm run dev
```

## 빌드

```bash
npm run build    # dist/
npm run preview  # 빌드 결과 미리보기
```

## 구조

```
src/
  api/        # 백엔드 엔드포인트별 클라이언트 + react-query 훅
  pages/      # login, onboarding, chat, sources, settings, graph
  components/ # Sidebar, Topbar, GraphVis, NodeDetail, Composer ...
  styles/     # 디자인 토큰 (HTHT 이식)
  types/      # API 응답 타입
```

디자인은 [HTHT 프로토타입](../../../HTHT)을 React + TS로 재구성한 것.
