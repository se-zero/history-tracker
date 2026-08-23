# hero-video — 랜딩 히어로 영상 제작 파이프라인

랜딩 히어로 슬롯에 들어가는 **실제 제품 녹화 영상**(질문 타이핑 → 답변+출처 카드 도착 →
그래프 앰버 점등 → 카드 클릭 → 상세 → "채팅에 추가" → 후속 질문 → 재점등, ~23s 무한 루프)을
만든다. 백엔드·도커 없이 목 서버 + Vite 위에서 실앱을 Playwright로 연기·녹화하고 ffmpeg로
후처리한다. 최종 산출물은 `clients/web-dashboard/public/hero-demo-{dark,light}.mp4` +
`hero-demo-{dark,light}-poster.jpg` — **이 파일명이 곧 계약**이다(`HeroProductSlot.tsx`가 참조).

## 빠른 시작 (재촬영 전체 절차)

```bash
cd clients/web-dashboard/tools/hero-video
npm install
npx playwright install chromium   # 최초 1회

# 4벌 생성 (테마 × 변형) — record가 목(:8097)·vite(:5197)를 알아서 띄우고 정리한다
node record.mjs --theme dark  --variant click && node postprocess.mjs --theme dark  --variant click
node record.mjs --theme light --variant click && node postprocess.mjs --theme light --variant click
node record.mjs --theme dark  --variant basic && node postprocess.mjs --theme dark  --variant basic
node record.mjs --theme light --variant basic && node postprocess.mjs --theme light --variant basic

# 랜딩 배포 — 채택 변형은 click(후속 질문 포함, 사용자 확정 2026-08-23)
cp out/hero-demo-dark-click.mp4         ../../public/hero-demo-dark.mp4
cp out/hero-demo-light-click.mp4        ../../public/hero-demo-light.mp4
cp out/hero-demo-dark-click-poster.jpg  ../../public/hero-demo-dark-poster.jpg
cp out/hero-demo-light-click-poster.jpg ../../public/hero-demo-light-poster.jpg
```

인코딩만 다시 하려면(crf 조정 등) `out/raw-*.webm`이 남아 있는 한 record 없이
`postprocess.mjs`만 다시 돌리면 된다.

## 무엇을 고치려면 어디를 보나

| 고치고 싶은 것 | 위치 |
|---|---|
| 카피·데모 데이터 전부(질문·답변·카드·그래프 노드·레일 대화·프로젝트명·사람 이름) | `scenario.mjs` — 모든 응답 JSON의 단일 출처 |
| 장면 타이밍(대기·유지, 타이핑 속도), 클릭 장면 구성 | `record.mjs` 상단 상수 + 장면 각본 |
| UI 크기감(슬롯에서 앱이 얼마나 크게 보이나) | `record.mjs --layout <폭>` — 기본 1600. 작을수록 크게 보인다. **1280은 "너무 크다", 1920은 "너무 작다"로 반려된 확정값이니 바꿀 땐 사용자 확인** |
| 압축 품질 | `postprocess.mjs --crf <n>` — 기본 20 |
| "생각 중" 길이 | `mock-server.mjs`의 `THINK_DELAY_MS`(기본 2000ms) |

## 반드시 지킬 것 — 전부 실제로 겪은 실패에서 확정한 규칙이다. 어기면 그 실패가 그대로 재발한다.

1. **뷰포트 비율은 슬롯 비율(1280:675)** — `record.mjs`가 `--layout` 폭에서 자동 계산한다.
   16:9로 바꾸면 상단 브레드크럼 바와 하단 AI 고지 라인이 잘려 나간다.
2. **고해상도 캡처는 launch args `--force-device-scale-factor=2` 한 가지만 유효** —
   context 옵션 `deviceScaleFactor`와 CSS zoom은 되는 것처럼 보여도 결과물이 깨진다
   (회색 패딩 / 화면 상단 잘림). 다른 방식으로 바꾸지 말 것.
3. **최종 인코딩 해상도는 2400×1266 고정** — 슬롯 표시 크기(1200×633)의 정확히 2배.
   다른 해상도로 내보내면 일반 모니터에서 글자가 깨져 보인다. 슬롯 표시 크기가 바뀌는
   경우에만 그 2배로 함께 조정한다.
4. **웜업 페이지 구조(첫 페이지는 버리고 두 번째 페이지에서 촬영)를 제거하지 말 것** —
   제거하면 트림 기준이 밀려 루프 시작·끝이 "빈 화면 대기"가 아니게 되고 이음새가 무너진다.
5. **`reducedMotion: "no-preference"` 유지** — 빼면 점등 안무가 아예 찍히지 않는다.
6. **localStorage 주입에 `chat:graphPanel = "1"` 유지** — 없으면 첫 답변에서 점등이 안 난다.
7. **목 서버는 어떤 요청에도 401 금지** — 401을 내는 순간 토큰 refresh 재시도 루프에 빠진다.
   모르는 경로는 404 + 콘솔 경고(빠뜨린 계약을 촬영 전에 발견하는 장치 — 유지).
8. **시드 노드는 evidence 순서대로 `EDGES`에서 서로 이어져 있어야** 점등 엣지 드로잉이 나온다.
   `evidence` 배열 순서 = 출처 카드 순서 = subgraph 응답 `seeds` 순서 — 셋을 항상 함께 맞춘다.
9. **결과 판정은 최종 mp4 프레임으로만** — 스크립트 로그나 계산값을 믿지 말고 프레임을 뽑아
   눈으로 본다:
   ```bash
   FF=./node_modules/ffmpeg-static/ffmpeg.exe
   "$FF" -ss 0       -i out/hero-demo-dark-click.mp4 -frames:v 1 -y t0.jpg   # 빈 화면 대기여야 함
   "$FF" -sseof -0.1 -i out/hero-demo-dark-click.mp4 -frames:v 1 -y end.jpg  # 빈 화면(디졸브 잔상은 정상)
   "$FF" -ss 8       -i out/hero-demo-dark-click.mp4 -frames:v 1 -y mid.jpg  # 답변·카드·점등 온전한지
   ```

## 참고

- `out/`·`node_modules/`는 gitignore 대상. `out/raw-*.webm` + `.json`은 지우지 말고 두면
  재촬영 없이 인코딩 재조정이 가능하다(지워져도 record 한 번이면 복구).
- 화면을 손으로 만져 보고 싶으면: 이 디렉터리에서 `PORT=8099 npm run mock`, web-dashboard에서
  `VITE_API_PROXY=http://localhost:8099 npm run dev -- --port 5199 --strictPort` →
  브라우저로 `/landing` 진입 후 localStorage에 `ht.access_token`·`ht.refresh_token`(아무 문자열),
  `ht.theme`("dark"/"light"), `chat:graphPanel`("1")을 넣고 `/`로 이동하면 로그인 없이 앱이 뜬다.
- 랜딩 쪽 교체 계약은 `HeroProductSlot.tsx` 상단 주석과 `docs/LANDING_BRIEF.md` §5,
  단계 전체 경위는 `docs/landing-roadmap.md`(로컬 문서) 히어로 영상 절.
