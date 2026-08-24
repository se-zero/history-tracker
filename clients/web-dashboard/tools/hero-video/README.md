# hero-video — 랜딩 히어로 영상 제작 파이프라인

랜딩 히어로 슬롯에 들어가는 **실제 제품 녹화 영상**(질문 타이핑 → 답변+출처 카드 도착 →
그래프 앰버 점등 → 카드 클릭 → 상세 → "채팅에 추가" → 후속 질문 → 재점등, ~23s 무한 루프)을
만든다. 백엔드·도커 없이 목 서버 + Vite 위에서 실앱을 Playwright로 연기·녹화하고 ffmpeg로
후처리한다. 최종 산출물은 `clients/web-dashboard/public/hero-demo-{lang}-{theme}.mp4` +
`hero-demo-{lang}-{theme}-poster.jpg` — **이 파일명이 곧 계약**이다(`HeroProductSlot.tsx`가 참조).

랜딩 영어 버전을 위해 `--lang ko|en` 축이 추가됐다. 실앱 UI는 한국어 하드코딩이므로, `en`
촬영 시에만 Playwright가 페이지에 번역기(`en-dom-overlay.mjs`)를 주입해 프레임 속 앱 크롬을
영어로 바꾼다 — **앱 소스(`src/`)는 건드리지 않는다**(사용자 확정 결정). 이에 따라 산출물
명명도 `hero-demo-{lang}-{theme}.*`로 언어 축이 하나 늘었다. 랜딩(`HeroProductSlot.tsx`)이
`useLandingLanguage()`의 lang과 테마를 조합해 이 파일명을 그대로 소비한다.

## 빠른 시작 (재촬영 전체 절차)

```bash
cd clients/web-dashboard/tools/hero-video
npm install
npx playwright install chromium   # 최초 1회

# 언어 × 테마 × 변형 조합마다 반복 — record가 목(:8097)·vite(:5197)를 알아서 띄우고 정리한다.
# --lang 기본값은 ko(생략 가능). en은 촬영 시 자동으로 번역 오버레이가 주입된다.
node record.mjs --lang ko --theme dark  --variant click && node postprocess.mjs --lang ko --theme dark  --variant click
node record.mjs --lang ko --theme light --variant click && node postprocess.mjs --lang ko --theme light --variant click
node record.mjs --lang en --theme dark  --variant click && node postprocess.mjs --lang en --theme dark  --variant click
node record.mjs --lang en --theme light --variant click && node postprocess.mjs --lang en --theme light --variant click
# basic 변형(후속 질문 없이 첫 교환까지만)이 필요하면 위 네 줄을 --variant basic으로 반복

# 랜딩 배포 — 채택 변형은 click(후속 질문 포함, 사용자 확정 2026-08-23). HeroProductSlot.tsx가
# lang×theme 조합을 그대로 파일명으로 조립하므로(`hero-demo-{lang}-{theme}.*`) public에는
# -click 접미를 뗀 4개 조합(8파일)이 올라간다.
cp out/hero-demo-ko-dark-click.mp4         ../../public/hero-demo-ko-dark.mp4
cp out/hero-demo-ko-light-click.mp4        ../../public/hero-demo-ko-light.mp4
cp out/hero-demo-ko-dark-click-poster.jpg  ../../public/hero-demo-ko-dark-poster.jpg
cp out/hero-demo-ko-light-click-poster.jpg ../../public/hero-demo-ko-light-poster.jpg
cp out/hero-demo-en-dark-click.mp4         ../../public/hero-demo-en-dark.mp4
cp out/hero-demo-en-light-click.mp4        ../../public/hero-demo-en-light.mp4
cp out/hero-demo-en-dark-click-poster.jpg  ../../public/hero-demo-en-dark-poster.jpg
cp out/hero-demo-en-light-click-poster.jpg ../../public/hero-demo-en-light-poster.jpg
```

인코딩만 다시 하려면(crf 조정 등) `out/raw-{lang}-*.webm`이 남아 있는 한 record 없이
`postprocess.mjs`만 다시 돌리면 된다. **구 명명(`raw-{theme}-{variant}.webm`, lang 축 도입
이전)은 새 postprocess.mjs와 호환되지 않는다** — 파일명 규칙이 바뀌었으므로 필요하면
`--lang ko`로 재촬영한다.

## 무엇을 고치려면 어디를 보나

| 고치고 싶은 것 | 위치 |
|---|---|
| 카피·데모 데이터 전부(질문·답변·카드·그래프 노드·레일 대화·프로젝트명·사람 이름) | `scenario.mjs` — `getScenario(lang)`이 유일한 진입점. 텍스트 필드만 `{ ko, en }`로 갈리고 그래프 구조(노드 id·엣지·시드 순서)는 언어와 무관한 단일 출처 |
| `en` 촬영 시 앱 크롬(사이드바·컴포저·카드 등)을 영어로 보이게 하는 치환 사전·주입 함수 | `en-dom-overlay.mjs` — 앱 소스는 그대로 두고 촬영 시에만 DOM 텍스트를 바꾼다(아래 절 참고) |
| 장면 타이밍(대기·유지, 타이핑 속도), 클릭 장면 구성 | `record.mjs` 상단 상수 + 장면 각본 |
| UI 크기감(슬롯에서 앱이 얼마나 크게 보이나) | `record.mjs --layout <폭>` — 기본 1600. 작을수록 크게 보인다. **1280은 "너무 크다", 1920은 "너무 작다"로 반려된 확정값이니 바꿀 땐 사용자 확인** |
| 압축 품질 | `postprocess.mjs --crf <n>` — 기본 14 (글자 선명도 우선, 2026-08-23 하향. 중간 트림 인코딩도 crf 10 고정 — 2세대 손실 방지, postprocess.mjs 주석 참고) |
| "생각 중" 길이 | `mock-server.mjs`의 `THINK_DELAY_MS`(기본 2000ms) |

## 영어(`--lang en`) 촬영 — 앱 크롬 치환

실앱 컴포넌트는 한국어가 하드코딩돼 있어(i18n 미도입, `docs/i18n.md`), 영어 버전을 찍으려면
녹화 중에만 DOM을 바꿔치기한다. `en-dom-overlay.mjs`가 두 가지를 내보낸다: 치환 엔트리
배열(`EN_OVERLAY_ENTRIES`)과, `context.addInitScript`로 페이지에 직렬화돼 주입되는
자기완결 함수(`installEnOverlay`) — 렌더 전(초기화 스크립트)에 실행되므로 첫 페인트부터
영어로 보이고 한국어 프레임이 유출되지 않는다.

엔트리는 세 종류다.
- `text` — 텍스트 노드 전체와 trim 후 완전 일치할 때만 치환한다(부분 문자열 치환 금지 —
  "대화"처럼 짧은 문자열이 다른 문구 안에 우연히 포함돼 오발하는 것을 막는다).
- `pattern` — `formatRelative`(`lib/format.ts`) 산출인 `N분 전`/`N시간 전`/`N일 전`처럼
  숫자가 바뀌는 문구를 정규식으로 잡아 `1 day ago`/`2 days ago`식으로 단수/복수를 맞춰
  완전히 풀어 쓴다("just now"/"5m ago" 같은 축약형은 쓰지 않는다).
- `attr` — 컴포저 placeholder처럼 텍스트 노드가 아니라 요소 속성인 경우.

**경고 — 조용한 실패**: 사전의 원문(`from`)은 실앱 컴포넌트에서 그대로 복사한 것이다. 그
컴포넌트의 한국어 문구가 바뀌면 이 사전은 예외 없이 그냥 매치를 놓치고 넘어간다(치환 실패
= 에러 없음). 그래서 `--lang en` 재촬영 뒤에는 반드시 산출 mp4 프레임을 뽑아 **한국어
글리프가 하나도 남아 있지 않은지** 눈으로 확인해야 한다(아래 규칙 9).

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
   영어 오버레이도 context 레벨(`addInitScript`)로 등록되므로 웜업 페이지에도 동일하게
   적용된다 — 웜업 페이지만 따로 처리할 필요는 없다.
5. **`reducedMotion: "no-preference"` 유지** — 빼면 점등 안무가 아예 찍히지 않는다.
6. **localStorage 주입에 `chat:graphPanel = "1"` 유지** — 없으면 첫 답변에서 점등이 안 난다.
7. **목 서버는 어떤 요청에도 401 금지** — 401을 내는 순간 토큰 refresh 재시도 루프에 빠진다.
   모르는 경로는 404 + 콘솔 경고(빠뜨린 계약을 촬영 전에 발견하는 장치 — 유지).
8. **시드 노드는 evidence 순서대로 `EDGES`에서 서로 이어져 있어야** 점등 엣지 드로잉이 나온다.
   `evidence` 배열 순서 = 출처 카드 순서 = subgraph 응답 `seeds` 순서 — 셋을 항상 함께 맞춘다.
   그래프 구조는 lang과 무관한 단일 출처라(`scenario.mjs`) 이 계약은 언어를 바꿔도 자동 유지된다.
9. **결과 판정은 최종 mp4 프레임으로만** — 스크립트 로그나 계산값을 믿지 말고 프레임을 뽑아
   눈으로 본다(아래는 ko/dark/click 예시 — 다른 조합은 파일명의 `{lang}-{theme}` 부분만 바꾼다):
   ```bash
   FF=./node_modules/ffmpeg-static/ffmpeg.exe
   "$FF" -ss 0       -i out/hero-demo-ko-dark-click.mp4 -frames:v 1 -y t0.jpg   # 빈 화면 대기여야 함
   "$FF" -sseof -0.1 -i out/hero-demo-ko-dark-click.mp4 -frames:v 1 -y end.jpg  # 빈 화면(디졸브 잔상은 정상)
   "$FF" -ss 8       -i out/hero-demo-ko-dark-click.mp4 -frames:v 1 -y mid.jpg  # 답변·카드·점등 온전한지
   ```
   `--lang en`으로 찍었다면 이 세 프레임 어디에도 **한국어 글자가 남아 있지 않은지**까지 함께
   확인한다 — 치환기는 실패해도 조용하다(바로 위 절 참고).

## 참고

- `out/`·`node_modules/`는 gitignore 대상. `out/raw-{lang}-*.webm` + `.json`은 지우지 말고
  두면 재촬영 없이 인코딩 재조정이 가능하다(지워져도 record 한 번이면 복구).
- 화면을 손으로 만져 보고 싶으면: 이 디렉터리에서 `PORT=8099 npm run mock`, web-dashboard에서
  `VITE_API_PROXY=http://localhost:8099 npm run dev -- --port 5199 --strictPort` →
  브라우저로 `/landing` 진입 후 localStorage에 `ht.access_token`·`ht.refresh_token`(아무 문자열),
  `ht.theme`("dark"/"light"), `chat:graphPanel`("1")을 넣고 `/`로 이동하면 로그인 없이 앱이 뜬다.
  영어로 보고 싶으면 `HERO_LANG=en PORT=8099 npm run mock`으로 목 서버를 띄운다(이 수동
  경로에는 en-dom-overlay가 주입되지 않으므로 앱 크롬 자체는 여전히 한국어다).
- 랜딩 쪽 교체 계약은 `HeroProductSlot.tsx` 상단 주석과 `docs/LANDING_BRIEF.md` §5,
  단계 전체 경위는 `docs/landing-roadmap.md`(로컬 문서) 히어로 영상 절.
