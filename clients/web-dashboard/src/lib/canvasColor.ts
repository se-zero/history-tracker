/**
 * CSS 변수 색을 Canvas가 쓸 수 있는 RGB 성분으로 해석한다.
 *
 * Canvas의 fillStyle은 CSS 색 문자열을 그대로 받지만, 글로우 그라디언트는 같은 색의
 * 알파만 바꿔가며 여러 스톱을 만들어야 해서 성분 값(r,g,b)이 필요하다.
 * tokens.css의 색은 oklch()라 문자열 파싱이 까다로워, 1x1 오프스크린 캔버스에 칠하고
 * 되읽는 방식을 쓴다 — 브라우저가 지원하는 모든 색 표기에 안전하다.
 *
 * 이 방식 덕분에 색의 단일 출처는 여전히 tokens.css다(hex 하드코딩 없음).
 */

export type Rgb = [number, number, number];

const cache = new Map<string, Rgb>();

let probe: CanvasRenderingContext2D | null = null;

function probeCtx(): CanvasRenderingContext2D | null {
  if (probe) return probe;
  const canvas = document.createElement("canvas");
  canvas.width = 1;
  canvas.height = 1;
  probe = canvas.getContext("2d", { willReadFrequently: true });
  return probe;
}

function parseColor(color: string): Rgb | null {
  const ctx = probeCtx();
  if (!ctx || !color) return null;
  // 못 읽는 값이 오면 fillStyle 대입이 무시되므로, 직전에 넣어둔 기준색이 그대로 남는다.
  // 그 경우를 감지하려고 서로 다른 두 기준색으로 두 번 시도한다.
  ctx.fillStyle = "#000000";
  ctx.fillStyle = color;
  const first = ctx.fillStyle;
  ctx.fillStyle = "#ffffff";
  ctx.fillStyle = color;
  if (ctx.fillStyle !== first) return null;

  ctx.clearRect(0, 0, 1, 1);
  ctx.fillRect(0, 0, 1, 1);
  const d = ctx.getImageData(0, 0, 1, 1).data;
  return [d[0], d[1], d[2]];
}

/**
 * `--node-pr` 같은 토큰 이름을 RGB로 해석한다.
 * 테마에 따라 값이 달라지므로 캐시 키에 테마를 포함한다.
 */
export function resolveVarRgb(varName: string, theme: string): Rgb {
  const key = `${theme}:${varName}`;
  const hit = cache.get(key);
  if (hit) return hit;

  const raw = getComputedStyle(document.documentElement)
    .getPropertyValue(varName)
    .trim();
  const rgb = parseColor(raw) ?? [140, 140, 150];
  cache.set(key, rgb);
  return rgb;
}

/** `var(--node-pr)` 형태의 값에서 토큰 이름만 뽑는다. NODE_TYPE_INFO.cssVar가 이 형태다. */
export function varName(cssVar: string): string {
  const m = /var\(\s*(--[\w-]+)\s*\)/.exec(cssVar);
  return m ? m[1] : cssVar;
}

export function rgba(c: Rgb, alpha: number): string {
  return `rgba(${c[0]},${c[1]},${c[2]},${alpha})`;
}

/** 성분을 배율로 밝히거나 어둡게 한다 (오브 코어를 하이라이트할 때 사용). */
export function shade(c: Rgb, factor: number): Rgb {
  return [
    Math.max(0, Math.min(255, Math.round(c[0] * factor))),
    Math.max(0, Math.min(255, Math.round(c[1] * factor))),
    Math.max(0, Math.min(255, Math.round(c[2] * factor))),
  ];
}
