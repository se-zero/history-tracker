// record.mjs가 남긴 raw webm + 타임스탬프 json을 ffmpeg-static으로 후처리한다.
// 트림(sceneStart~sceneEnd) → 루프 크로스페이드(꼬리 0.7s를 머리에 xfade) → h264 인코딩 → 포스터 추출.
import { spawnSync } from "node:child_process";
import { existsSync, readFileSync, statSync, unlinkSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import ffmpegPath from "ffmpeg-static";
import ffprobeStatic from "ffprobe-static";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.join(__dirname, "out");
const ffprobePath = ffprobeStatic.path;

// 루프 이음새 크로스페이드 길이(초) — 꼬리 이 구간을 머리와 섞어 재생 시 이음이 안 보이게 한다.
const XFADE_SEC = 0.7;
const MAX_MP4_BYTES = 8 * 1024 * 1024;

function parseArgs(argv) {
  // crf 기본 14 — UI 텍스트 선명도 우선(기본 20에서 작은 글자가 뭉개져 보인다는 사용자
  // 지시로 하향, 2026-08-23). 용량은 그래도 8MB 상한 안에 넉넉히 들어오고, 넘으면
  // CLI로 16~20으로 후퇴할 수 있게 열어 둔다.
  const opts = { theme: "dark", variant: "basic", crf: "14" };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === "--theme") opts.theme = argv[++i];
    else if (argv[i] === "--variant") opts.variant = argv[++i];
    else if (argv[i] === "--crf") opts.crf = argv[++i];
  }
  return opts;
}

function run(bin, args) {
  const result = spawnSync(bin, args, { encoding: "utf-8" });
  if (result.status !== 0) {
    throw new Error(
      `${path.basename(bin)} 실패(코드 ${result.status}):\n${result.stderr ?? result.stdout}`,
    );
  }
  return result;
}

function ffprobeJson(filePath) {
  const result = run(ffprobePath, [
    "-v",
    "error",
    "-print_format",
    "json",
    "-show_format",
    "-show_streams",
    filePath,
  ]);
  return JSON.parse(result.stdout);
}

async function main() {
  const { theme, variant, crf } = parseArgs(process.argv.slice(2));
  const rawWebm = path.join(OUT_DIR, `raw-${theme}-${variant}.webm`);
  const rawJsonPath = path.join(OUT_DIR, `raw-${theme}-${variant}.json`);
  if (!existsSync(rawWebm) || !existsSync(rawJsonPath)) {
    throw new Error(
      `record.mjs 산출물이 없습니다: ${rawWebm} / ${rawJsonPath} (먼저 record를 실행하세요)`,
    );
  }

  const marks = JSON.parse(readFileSync(rawJsonPath, "utf-8"));
  const trimStart = marks.sceneStartOffsetSec;
  const trimEnd = marks.sceneEndOffsetSec;
  const trimDuration = trimEnd - trimStart;
  if (!(trimDuration > XFADE_SEC * 2)) {
    throw new Error(
      `씬 길이(${trimDuration.toFixed(2)}s)가 크로스페이드 길이(${XFADE_SEC * 2}s)보다 짧습니다.`,
    );
  }

  const variantSuffix = variant === "click" ? "-click" : "";
  const trimmedMp4 = path.join(OUT_DIR, `.trimmed-${theme}-${variant}.mp4`);
  const finalMp4 = path.join(OUT_DIR, `hero-demo-${theme}${variantSuffix}.mp4`);

  console.log(`[postprocess] 트림: ${trimStart.toFixed(2)}s ~ ${trimEnd.toFixed(2)}s`);
  // 1) 트림 — 입력 시크(-ss before -i)는 빠르지만 키프레임 근처로 스냅될 수 있다.
  // 계약상 ±0.2s 오차는 허용이라 재인코딩 없이 정확도보다 속도를 택한다. 다음 단계(xfade)의
  // 입력으로 쓰이므로 여기서 h264로 한 번 구워 둔다. 중간 산출물이라 crf 10(시각적 무손실)
  // 으로 굽는다 — 품질 옵션 없이 두면 libx264 기본(crf 23)으로 한 번 손실이 생기고, 최종
  // 인코딩이 그 손실본을 재인코딩하는 2세대 손실이 되어 작은 글자가 뭉개진다(2026-08-23
  // 실사용 지적의 원인). 파일이 커지지만 최종 인코딩 직후 지운다.
  run(ffmpegPath, [
    "-y",
    "-ss",
    String(trimStart),
    "-i",
    rawWebm,
    "-t",
    String(trimDuration),
    "-an",
    "-c:v",
    "libx264",
    "-pix_fmt",
    "yuv420p",
    "-crf",
    "10",
    "-preset",
    "veryfast",
    trimmedMp4,
  ]);

  const trimmedInfo = ffprobeJson(trimmedMp4);
  const trimmedDuration = Number(trimmedInfo.format.duration);
  const xfadeOffset = trimmedDuration - XFADE_SEC * 2;

  console.log(`[postprocess] 루프 크로스페이드: ${XFADE_SEC}s (offset ${xfadeOffset.toFixed(2)}s)`);
  // 2) 루프 이음새 — 트림된 클립을 두 갈래로 나눠, 몸통(X~D)과 꼬리(0~X, 원본 머리)를
  // xfade로 이어 붙인다. 결과는 [몸통 대부분] + [꼬리→머리 크로스페이드]로, 파일의
  // 시작점이 원본 재생 순서상 X초만큼 밀리지만(루프에는 "진짜 시작"이 없어 무해하다),
  // 파일 끝→처음 전환이 매끄러워진다. 총 길이는 X초만큼 줄어든다(요구사항 그대로).
  // 최종 해상도는 슬롯 표시 크기(1200×633)의 정확히 2배(2400×1266)로 Lanczos 다운스케일한다.
  // 캡처 원본(3200×1688)을 그대로 내보내면 브라우저 <video>의 조악한 bilinear 축소가
  // 2.67:1(일반 모니터)·1.33:1(레티나) 같은 어중간한 비율을 실시간으로 처리하며 가는 글자
  // 획을 끊어먹는다(기본 배율에서 "글씨가 깨져 보임" — 실사용 확인). 2400×1266이면
  // 레티나는 1:1(무스케일), 일반 모니터는 2:1 정수 축소라 둘 다 깨끗하다. 고품질 축소는
  // 여기(Lanczos) 한 번만 일어난다. 고밀도 캡처 자체는 record.mjs의 launch args
  // --force-device-scale-factor=2가 담당(상세 사유는 그쪽 주석 참고).
  run(ffmpegPath, [
    "-y",
    "-i",
    trimmedMp4,
    "-filter_complex",
    [
      "[0:v]split=2[main][tail]",
      `[main]trim=start=${XFADE_SEC}:end=${trimmedDuration},setpts=PTS-STARTPTS[body]`,
      `[tail]trim=start=0:end=${XFADE_SEC},setpts=PTS-STARTPTS[head]`,
      `[body][head]xfade=transition=fade:duration=${XFADE_SEC}:offset=${xfadeOffset}[xf]`,
      "[xf]scale=2400:1266:flags=lanczos[scaled]",
    ].join(";"),
    "-map",
    "[scaled]",
    "-an",
    "-c:v",
    "libx264",
    "-pix_fmt",
    "yuv420p",
    "-crf",
    crf,
    "-preset",
    "slow",
    "-movflags",
    "+faststart",
    finalMp4,
  ]);
  unlinkSync(trimmedMp4); // 중간 산출물 — xfade 입력으로만 쓰이고 최종본엔 필요 없다.

  const finalInfo = ffprobeJson(finalMp4);
  const finalDuration = Number(finalInfo.format.duration);
  const finalStream = finalInfo.streams.find((s) => s.codec_type === "video");
  const finalBytes = statSync(finalMp4).size;

  console.log(
    `[postprocess] 완료: ${finalMp4} — ${finalDuration.toFixed(2)}s, ` +
      `${finalStream?.width}x${finalStream?.height}, ${(finalBytes / 1024 / 1024).toFixed(2)}MB`,
  );
  if (finalBytes > MAX_MP4_BYTES) {
    console.warn(
      `[postprocess] 경고: ${path.basename(finalMp4)}이 8MB를 초과했습니다 (${(finalBytes / 1024 / 1024).toFixed(2)}MB).`,
    );
  }

  // 3) 포스터 — 변형별 파일명(variantSuffix)으로 항상 생성한다. 랜딩의 reduced-motion
  // 폴백이 채택 변형(click)의 포스터를 필요로 하므로 basic 한정 제약을 걷어냈다.
  const posterJpg = path.join(OUT_DIR, `hero-demo-${theme}${variantSuffix}-poster.jpg`);
  run(ffmpegPath, [
    "-y",
    "-ss",
    String(marks.posterOffsetSec),
    "-i",
    rawWebm,
    "-frames:v",
    "1",
    "-vf",
    "scale=2400:1266:flags=lanczos", // 영상과 같은 이유(브라우저 축소 앨리어싱 회피)·같은 크기
    "-q:v",
    "2", // reduced-motion 사용자가 이 정지 이미지를 보므로 영상 crf 하향(14)과 함께 상향
    posterJpg,
  ]);
  console.log(`[postprocess] 포스터: ${posterJpg} (${(statSync(posterJpg).size / 1024).toFixed(0)}KB)`);
}

main().catch((err) => {
  console.error("[postprocess] 실패:", err);
  process.exit(1);
});
