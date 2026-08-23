// 히어로 영상 촬영 스크립트 — mock-server(:8097)와 vite dev(:5197)를 자체 포트로 띄우고,
// 실앱 채팅 흐름을 Playwright chromium으로 연기·녹화한다. 앱 소스는 건드리지 않는다
// (수동 검증용 :8099/:5199 스택과 겹치지 않게 자체 포트를 쓴다).
import { spawn } from "node:child_process";
import { mkdirSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { chromium } from "playwright";

import { FOLLOWUP_QUESTION, SCRIPTED_QUESTION } from "./scenario.mjs";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB_DASHBOARD_DIR = path.resolve(__dirname, "..", "..");
const OUT_DIR = path.join(__dirname, "out");

const MOCK_PORT = 8097;
const VITE_PORT = 5197;
const MOCK_URL = `http://localhost:${MOCK_PORT}`;
const APP_URL = `http://localhost:${VITE_PORT}`;

// 점등 안무 상한(IGNITE_SEQ_CAP=3 기준 2,040ms, lib/igniteSchedule.ts) + 여유.
const IGNITE_WAIT_MS = 2600;
const HOLD_AFTER_IGNITE_MS = 2000;
const NODE_DETAIL_HOLD_MS = 2200;
// "채팅에 추가" 클릭 후 NodeDetail을 닫고(자동으로 닫히지 않는다) 후속 질문을 타이핑하기
// 전, 퇴장 애니메이션이 끝날 시간을 준다.
const AFTER_CLOSE_HOLD_MS = 800;

function parseArgs(argv) {
  // --layout: 앱이 잡히는 CSS 레이아웃 폭(높이는 히어로 슬롯 비율 1280:675 ≈ 1.9:1로 자동).
  // 16:9가 아니라 슬롯 비율로 찍는 이유: object-fit: cover의 상하 크롭(각 ~3.2%)이 정확히
  // 상단 브레드크럼 바·하단 AI 고지 라인을 잘라먹는 것이 실렌더에서 확인됐다 — 비율을
  // 슬롯과 맞추면 크롭이 사실상 0이 된다. 녹화 실픽셀은 항상 레이아웃의 2배다.
  // 값이 작을수록 슬롯(1200px)에서 UI가 크게, 클수록 작게 보인다 — 크기 체감 튜닝 손잡이.
  const opts = { theme: "dark", variant: "basic", layout: 1600 };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--theme") opts.theme = argv[++i];
    else if (argv[i] === "--variant") opts.variant = argv[++i];
    else if (argv[i] === "--layout") opts.layout = Number(argv[++i]);
  }
  if (!["dark", "light"].includes(opts.theme)) {
    throw new Error(`알 수 없는 --theme 값: ${opts.theme}`);
  }
  if (!["basic", "click"].includes(opts.variant)) {
    throw new Error(`알 수 없는 --variant 값: ${opts.variant}`);
  }
  if (!Number.isInteger(opts.layout) || opts.layout < 1180 || opts.layout > 2560) {
    throw new Error(`--layout은 1180~2560 정수여야 합니다(1180 미만은 앱 레일이 접힘): ${opts.layout}`);
  }
  return opts;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function randomInt(min, max) {
  return Math.floor(min + Math.random() * (max - min));
}

// 컴포저에 텍스트를 한 글자씩 입력한다(첫 질문·후속 질문 공용) — 글자당 지터로 실타이핑처럼 보이게 한다.
async function typeIntoComposer(page, text) {
  const textarea = page.locator(".composer textarea");
  await textarea.click();
  for (const ch of text) {
    await page.keyboard.type(ch);
    await sleep(randomInt(45, 90));
  }
}

async function pingOnce(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 800);
  try {
    const res = await fetch(url, { signal: controller.signal });
    return res.status < 500;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function waitForHttp(url, child, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  let exitError = null;
  if (child) {
    child.once("exit", (code) => {
      if (code !== 0 && code !== null) {
        exitError = new Error(`프로세스가 코드 ${code}로 조기 종료됨 (${url} 대기 중)`);
      }
    });
  }
  while (Date.now() < deadline) {
    if (exitError) throw exitError;
    if (await pingOnce(url)) return;
    await sleep(300);
  }
  throw new Error(`${url} 준비 대기 시간 초과(${timeoutMs}ms)`);
}

// Windows에서 shell:true로 띄운 프로세스(vite)는 자식 kill()이 래퍼 cmd.exe만 죽이고
// 실제 node 프로세스는 남는 경우가 있어, 프로세스 트리 전체를 taskkill로 정리한다.
function killTree(child) {
  if (!child || child.killed) return;
  if (process.platform === "win32") {
    spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], { stdio: "ignore" });
  } else {
    child.kill("SIGTERM");
  }
}

// 이미 해당 포트에 떠 있으면(수동 검증 스택 재사용 목적이 아니라 이 도구를 반복 실행할 때
// 이전 프로세스가 남아 있는 경우) 재사용하고, 아니면 새로 띄운다.
async function ensureService(label, url, spawnFn) {
  if (await pingOnce(url)) {
    console.log(`[record] ${label} 이미 실행 중 — 재사용 (${url})`);
    return null;
  }
  console.log(`[record] ${label} 기동 중…`);
  const child = spawnFn();
  child.stdout?.on("data", (d) => process.stdout.write(`[${label}] ${d}`));
  child.stderr?.on("data", (d) => process.stderr.write(`[${label}] ${d}`));
  await waitForHttp(url, child, 30000);
  console.log(`[record] ${label} 준비 완료 (${url})`);
  return child;
}

async function main() {
  const { theme, variant, layout } = parseArgs(process.argv.slice(2));
  const layoutH = Math.round((layout * 675) / 1280);
  mkdirSync(OUT_DIR, { recursive: true });

  const npmCmd = process.platform === "win32" ? "npm.cmd" : "npm";

  const mockChild = await ensureService("mock", `${MOCK_URL}/api/v1/me`, () =>
    spawn(process.execPath, ["mock-server.mjs"], {
      cwd: __dirname,
      env: { ...process.env, PORT: String(MOCK_PORT) },
    }),
  );
  const viteChild = await ensureService("vite", APP_URL, () =>
    spawn(npmCmd, ["run", "dev", "--", "--port", String(VITE_PORT), "--strictPort"], {
      cwd: WEB_DASHBOARD_DIR,
      env: { ...process.env, VITE_API_PROXY: MOCK_URL },
      shell: process.platform === "win32",
    }),
  );

  let browser;
  try {
    // 폐기된 방식 둘 — context 옵션 deviceScaleFactor:2는 이 Chromium 스크린캐스트 경로에서
    // 무시된다(레이아웃 뷰포트 크기만 그려지고 나머지 캔버스는 회색 패딩, page.screenshot()은
    // DSF를 반영하지만 recordVideo는 별개 경로). CSS zoom:2는 레이아웃 폭은 1280으로 줄이지만
    // 앱 셸이 100vh 기반이라 vh 단위가 zoom 영향을 안 받아 셸이 실제 창의 2배 높이로 넘치고,
    // 타이핑 포커스 스크롤에 상단(사이드바·대화 목록)이 프레임 밖으로 밀려났다(mp4 프레임
    // 실측으로 확인). 대신 브라우저 프로세스 레벨 DSF(launch args)를 쓴다 — 레이아웃·vh는
    // viewport 그대로(1280×720 CSS px)로 계산되고, 스크린캐스트가 이 DSF를 존중해 합성만
    // 2560×1440 실픽셀로 래스터한다(프로브로 grey padding 없음·사이드바 실픽셀 520·vh 정상
    // 확인 완료). 레이아웃 폭은 --layout(기본 1600 — parseArgs가 단일 출처)으로 조절하고 녹화 실픽셀은 항상 그 2배 —
    // 값이 작을수록 슬롯(실표시 1200px)에서 UI가 크게 보인다(크기 체감은 사용자 육안 확정).
    browser = await chromium.launch({
      headless: true,
      args: ["--force-device-scale-factor=2"],
    });
    const context = await browser.newContext({
      viewport: { width: layout, height: layoutH },
      recordVideo: { dir: OUT_DIR, size: { width: layout * 2, height: layoutH * 2 } },
      reducedMotion: "no-preference",
    });
    await context.addInitScript((themeArg) => {
      localStorage.setItem("ht.access_token", "mock-access-hero-video");
      localStorage.setItem("ht.refresh_token", "mock-refresh-hero-video");
      localStorage.setItem("ht.theme", themeArg);
      localStorage.setItem("chat:graphPanel", "1");
      localStorage.setItem("chat:graphPanelWidth", "420");
    }, theme);

    // 웜업 페이지 — vite 콜드스타트(모듈 트랜스폼 최초 1회)와 첫 데이터 로딩을 여기서
    // 흡수한다. 브라우저의 첫 페이지는 CDP 스크린캐스트 파이프라인 자체의 초기화 지연도
    // 겹쳐 recordingStartAt(Date.now())과 실제 영상 t=0 사이 오차가 커진다 — 본 촬영은
    // 이미 렌더러가 데워진 두 번째 페이지에서 시작해 그 오차를 줄인다. 이 페이지의
    // 영상은 트림 기준과 무관하므로 폐기한다.
    const warmupPage = await context.newPage();
    const warmupVideo = warmupPage.video();
    await warmupPage.goto(APP_URL);
    await warmupPage.waitForURL(/\/projects\/.+\/chat/);
    await warmupPage.getByText("무엇을 알아볼까요?").waitFor();
    await warmupPage.evaluate(() => document.fonts.ready);
    await warmupPage.close();

    const page = await context.newPage();
    const recordingStartAt = Date.now();

    console.log(`[record] ${theme}/${variant} 촬영 시작`);
    await page.goto(APP_URL);
    await page.waitForURL(/\/projects\/.+\/chat/);
    await page.getByText("무엇을 알아볼까요?").waitFor();

    await page.evaluate(() => document.fonts.ready);
    await sleep(500);

    // ── sceneStart: 루프 이음새 앞머리용 빈 화면 유지 ──
    const sceneStartAt = Date.now();
    await sleep(2000);

    await typeIntoComposer(page, SCRIPTED_QUESTION);
    await sleep(400);
    await page.keyboard.press("Enter");

    await page.waitForSelector('.msg.assistant[data-role="ASSISTANT"]', {
      timeout: 20000,
    });
    await sleep(IGNITE_WAIT_MS);

    // 점등 완료 직후(유지 구간 진입 시점) — postprocess의 포스터 프레임 기준.
    const posterAt = Date.now();
    await sleep(HOLD_AFTER_IGNITE_MS);

    if (variant === "click") {
      const thirdCard = page.locator(".cite-card").nth(2);
      await thirdCard.hover();
      await sleep(400);
      await thirdCard.click();
      await page.waitForSelector(".node-detail", { timeout: 5000 });
      await sleep(NODE_DETAIL_HOLD_MS);

      // "채팅에 추가" — onAddToChat은 상세를 자동으로 닫지 않으므로 칩 부착을 확인한 뒤 직접 닫는다.
      await page.locator(".node-detail .nd-actions button").click();
      await page.waitForSelector(".composer-chips", { timeout: 5000 });
      await page.locator(".node-detail .nd-head button.icon-btn").click();
      await sleep(AFTER_CLOSE_HOLD_MS);

      await typeIntoComposer(page, FOLLOWUP_QUESTION);
      await sleep(400);
      await page.keyboard.press("Enter");

      // 두 번째 교환(assistant 메시지 2개째) 대기 — 같은 대화에 이어붙는 재질문이라 카운트로 구분한다.
      await page.waitForFunction(
        () => document.querySelectorAll('.msg.assistant[data-role="ASSISTANT"]').length >= 2,
        { timeout: 20000 },
      );
      await sleep(IGNITE_WAIT_MS);
      await sleep(HOLD_AFTER_IGNITE_MS);
    }

    // ── sceneEnd: 루프 이음새 꼬리 기준점 ──
    const sceneEndAt = Date.now();
    await sleep(500);

    await context.close();

    const [warmupVideoPath, savedPath] = await Promise.all([
      warmupVideo.path(),
      page.video().path(),
    ]);
    unlinkSync(warmupVideoPath);
    const rawWebm = path.join(OUT_DIR, `raw-${theme}-${variant}.webm`);
    renameSync(savedPath, rawWebm);

    const toOffsetSec = (t) => Math.max(0, (t - recordingStartAt) / 1000);
    const raw = {
      theme,
      variant,
      recordingStartAt,
      sceneStartAt,
      posterAt,
      sceneEndAt,
      sceneStartOffsetSec: toOffsetSec(sceneStartAt),
      posterOffsetSec: toOffsetSec(posterAt),
      sceneEndOffsetSec: toOffsetSec(sceneEndAt),
    };
    const rawJsonPath = path.join(OUT_DIR, `raw-${theme}-${variant}.json`);
    writeFileSync(rawJsonPath, JSON.stringify(raw, null, 2));

    console.log(`[record] 완료: ${rawWebm}`);
    console.log(`[record] 타임스탬프: ${rawJsonPath}`);
  } finally {
    if (browser) await browser.close();
    killTree(viteChild);
    killTree(mockChild);
  }
}

main().catch((err) => {
  console.error("[record] 실패:", err);
  process.exit(1);
});
