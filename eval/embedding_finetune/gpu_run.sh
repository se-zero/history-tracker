#!/usr/bin/env bash
# 학교 GPU 잡에서 임베딩 파인튜닝 한 번(변형 all·ko·pre2025)을 설치부터 결과 포장까지 통째로 돌린다
# (docs/embedding-finetune.md §5, 묶음 E). 웹 터미널로만 닿는 GPU에서 왕복을 줄이려고, 사용자가 하는 일은
# 묶음(make_gpu_bundle.py가 만든 tgz)을 올리고 이 스크립트를 부르는 것뿐이다.
#
#   bash ef-bundle/gpu_run.sh start     묶음 검사 → 백그라운드 실행 시작. 브라우저를 닫아도 계속 돈다
#   bash ef-bundle/gpu_run.sh status    진행 상황. 끝났으면 내려받을 파일과 sha256
#
# 실행 하나는 $RUN_ROOT/<실행 id>/ 에 모인다: code/ data/ <변형>/ run.log status env.txt summary.json result-<id>.tgz
#
# 환경변수로 바꿀 수 있다(기본값은 아래 설정 블록). Mac에서 흐름 전체를 시험할 때 쓴다:
#   ROOT RUN_ROOT VENV HF_HOME MODEL REVISION VARIANTS MIN_FREE_GB TRAIN_EXTRA_ARGS
#   PYTHON=<파이썬>   환경 설치를 건너뛰고 이 파이썬을 쓴다      SKIP_INSTALL=1   $VENV를 그대로 쓴다
#   ALLOW_CPU=1       nvidia-smi·CUDA 확인을 건너뛴다             HF_HUB_OFFLINE=1 모델 내려받기를 건너뛴다
#
# bash 3.2(macOS)와 5.x(Linux) 모두에서 돈다 — 연관 배열·mapfile·빈 배열 확장을 쓰지 않는다.
# 파일을 source하면 main이 돌지 않고 함수만 불러와진다(eval/tests가 verify_bundle을 이렇게 시험한다).

set -euo pipefail

SELF="${BASH_SOURCE[0]}"
SELF_DIR="$(cd "$(dirname "$SELF")" && pwd)"
SELF="$SELF_DIR/$(basename "$SELF")"

# ----------------------------------------------------------------------------- 설정

ROOT="${ROOT:-/mnt/models}"
RUN_ROOT="${RUN_ROOT:-$ROOT/runs}"
VENV="${VENV:-$ROOT/venv-ef}"
# 이미 정해져 있으면 존중한다. Mac은 HF_HOME이 비어 있어 기본값($ROOT/hf, 빈 캐시)이 적용되므로, 시험 실행에서
# 받아 둔 모델을 쓰려면 HF_HOME=$HOME/.cache/huggingface를 직접 준다
export HF_HOME="${HF_HOME:-$ROOT/hf}"
export UV_PYTHON_INSTALL_DIR="${UV_PYTHON_INSTALL_DIR:-$ROOT/uv/python}"
export UV_CACHE_DIR="${UV_CACHE_DIR:-$ROOT/uv/cache}"
# 캐시(NFS)와 venv(NFS)가 같은 파일시스템이어도 하드링크가 막힐 수 있어 복사로 고정한다
export UV_LINK_MODE="${UV_LINK_MODE:-copy}"
# torch·nvidia 휠은 수백 MB라 uv의 기본 요청 제한 시간(30초)에 걸려 설치가 끊기는 일이 흔하다
export UV_HTTP_TIMEOUT="${UV_HTTP_TIMEOUT:-300}"

# torch는 CUDA 12.6 빌드를 쓴다: PyPI의 2.15.0 Linux 빌드는 CUDA 13이라 드라이버 580 이상이 필요한데 노드 드라이버를 모른다
TORCH_SPEC="${TORCH_SPEC:-torch==2.14.1}"
TORCH_INDEX="${TORCH_INDEX:-https://download.pytorch.org/whl/cu126}"
UV_SPEC="${UV_SPEC:-uv==0.13.4}"

# 기본 모델·리비전은 train_core.DEFAULT_MODEL·DEFAULT_REVISION과 같아야 한다 (eval/tests가 대조한다)
DEFAULT_MODEL="Qwen/Qwen3-Embedding-0.6B"
DEFAULT_REVISION="97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"
MODEL="${MODEL:-$DEFAULT_MODEL}"
# REVISION이 아예 없을 때만 기본 모델의 고정 해시를 쓴다. 빈 문자열로 주면 리비전 없이(최신) 받는다.
# 다른 모델에 Qwen의 해시가 붙으면 받기가 실패하므로 기본 모델일 때만 채운다
if [ "${REVISION+set}" != "set" ]; then
  if [ "$MODEL" = "$DEFAULT_MODEL" ]; then REVISION="$DEFAULT_REVISION"; else REVISION=""; fi
fi
VARIANTS="${VARIANTS:-all ko pre2025}"
MIN_FREE_GB="${MIN_FREE_GB:-20}"
TRAIN_EXTRA_ARGS="${TRAIN_EXTRA_ARGS:-}"

BUNDLE_CODE_FILES="gpu_run.sh train.py train_core.py requirements-train.txt SHA256SUMS"
BUNDLE_DATA_FILES="pairs-train.jsonl pairs-val.jsonl pairs-manifest.json"
TOTAL_STEPS=6

# ----------------------------------------------------------------------------- 공용 함수

log() { printf '[%s] %s\n' "$(date '+%m-%d %H:%M:%S')" "$*"; }
die() { printf '오류: %s\n' "$*" >&2; exit 1; }

sha256_stdin() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | cut -d' ' -f1
  else die "sha256sum도 shasum도 없어 해시를 계산할 수 없습니다"; fi
}

sha256_of() { sha256_stdin < "$1"; }

human_size() { ls -lh "$1" | awk '{print $5}'; }

# ----------------------------------------------------------------------------- 묶음 검사

verify_bundle() {
  [ -f "$SELF_DIR/SHA256SUMS" ] || die "SHA256SUMS가 없습니다 — make_gpu_bundle.py가 만든 묶음을 풀어서 그 안의 gpu_run.sh로 실행하세요"
  local out rc=0
  if command -v sha256sum >/dev/null 2>&1; then
    out="$(cd "$SELF_DIR" && sha256sum -c SHA256SUMS 2>&1)" || rc=$?
  elif command -v shasum >/dev/null 2>&1; then
    out="$(cd "$SELF_DIR" && shasum -a 256 -c SHA256SUMS 2>&1)" || rc=$?
  else
    die "sha256sum도 shasum도 없어 묶음을 검사할 수 없습니다"
  fi
  if [ "$rc" -ne 0 ]; then
    printf '%s\n' "$out" | grep -v ': OK$' >&2 || true
    die "파일이 잘렸거나 바뀌었습니다 — 다시 올려 주세요"
  fi
}

# ----------------------------------------------------------------------------- start

# 살아 있는 실행이 있으면 그 id를 출력한다. 명령을 두 번 붙여 넣으면 환경 설치가 같은 $VENV를 두고 동시에 돌아 서로 지운다
running_run_id() {
  [ -f "$RUN_ROOT/latest" ] || return 1
  local id pid
  id="$(cat "$RUN_ROOT/latest")"
  [ -f "$RUN_ROOT/$id/pid" ] || return 1
  pid="$(cat "$RUN_ROOT/$id/pid")"
  kill -0 "$pid" 2>/dev/null || return 1
  # 잡이 다시 떠서 옛 pid를 다른 프로세스가 쓰고 있을 수 있다
  ps -p "$pid" -o args= 2>/dev/null | grep -q 'gpu_run.sh' || return 1
  printf '%s\n' "$id"
}

cmd_start() {
  verify_bundle

  local running
  if running="$(running_run_id)"; then
    die "이미 실행 중인 작업이 있습니다: $running — 진행은 'bash $SELF status'로 보세요"
  fi

  local run_id run_dir f
  run_id="ef-$(date +%Y%m%d-%H%M%S)"
  run_dir="$RUN_ROOT/$run_id"
  mkdir -p "$RUN_ROOT"
  mkdir "$run_dir"          # 이미 있으면 실패한다(같은 초에 두 번 시작)
  mkdir "$run_dir/code" "$run_dir/data"
  # 묶음을 푼 폴더가 아니라 복사본에서 돌린다 — 실행 중에 묶음 폴더를 지워도, 나중에 어떤 코드로 돌았는지 볼 수 있다
  for f in $BUNDLE_CODE_FILES; do cp "$SELF_DIR/$f" "$run_dir/code/$f"; done
  for f in $BUNDLE_DATA_FILES; do cp "$SELF_DIR/data/$f" "$run_dir/data/$f"; done
  printf '%s\n' "$run_id" > "$RUN_ROOT/latest"

  # pid 파일은 _run이 자기 $$로 쓴다. setsid가 새 프로세스로 갈라지면 여기서 받는 $!는 곧 사라질 부모 pid이기 때문이다
  if command -v setsid >/dev/null 2>&1; then
    nohup setsid bash "$run_dir/code/gpu_run.sh" _run "$run_dir" > "$run_dir/run.log" 2>&1 < /dev/null &
  else
    nohup bash "$run_dir/code/gpu_run.sh" _run "$run_dir" > "$run_dir/run.log" 2>&1 < /dev/null &
  fi

  echo "시작했습니다. 브라우저를 닫아도 계속 돕니다."
  echo "  실행 id  : $run_id"
  echo "  로그     : $run_dir/run.log"
  echo "  진행 보기: bash $SELF status"
}

# ----------------------------------------------------------------------------- status

cmd_status() {
  [ -f "$RUN_ROOT/latest" ] || die "실행 기록이 없습니다 ($RUN_ROOT/latest) — 먼저 start를 실행하세요"
  local run_id run_dir
  run_id="$(cat "$RUN_ROOT/latest")"
  run_dir="$RUN_ROOT/$run_id"
  [ -d "$run_dir" ] || die "실행 폴더가 없습니다: $run_dir"

  local step="(아직 없음)" alive="아니오"
  if [ -f "$run_dir/status" ]; then step="$(cat "$run_dir/status")"; fi
  if [ -f "$run_dir/pid" ] && kill -0 "$(cat "$run_dir/pid")" 2>/dev/null; then
    alive="예 (pid $(cat "$run_dir/pid"))"
  fi
  echo "실행    : $run_id"
  echo "단계    : $step"
  echo "실행 중 : $alive"

  # 실패하면 사용자가 로그 끝 30줄을 복사해 보낸다
  local lines=20
  if [ -f "$run_dir/FAILED" ]; then lines=30; fi
  if [ -f "$run_dir/run.log" ]; then
    echo "--- 로그 끝 ${lines}줄 ($run_dir/run.log) ---"
    tail -n "$lines" "$run_dir/run.log"
    echo "---"
  fi

  if [ -f "$run_dir/DONE" ]; then
    local name file
    name="$(cat "$run_dir/DONE")"
    file="$run_dir/$name"
    echo "끝났습니다. 내려받을 파일:"
    echo "  $file"
    if [ -f "$file.sha256" ]; then echo "  크기 $(human_size "$file"), sha256 $(cut -d' ' -f1 "$file.sha256")"; fi
  elif [ -f "$run_dir/FAILED" ]; then
    echo "실패했습니다:"
    cat "$run_dir/FAILED"
  elif [ "$alive" = "아니오" ] && [ -f "$run_dir/pid" ]; then
    echo "프로세스가 없는데 끝나지 않았습니다 — 잡이 다시 떴거나 강제로 종료됐을 수 있습니다. 위 로그 끝을 보내 주세요."
  fi
}

# ----------------------------------------------------------------------------- _run (백그라운드 본체)

CURRENT_STEP="시작 전"

set_status() { printf '%s\n' "$1" > "$RUN_DIR/status"; }

step() {
  CURRENT_STEP="[$1/$TOTAL_STEPS] $2"
  set_status "$CURRENT_STEP"
  log "$CURRENT_STEP"
}

on_exit() {
  local rc=$?
  trap - EXIT
  if [ "$rc" -ne 0 ] && [ ! -f "$RUN_DIR/DONE" ]; then
    local msg="실패: $CURRENT_STEP — 로그 끝 30줄을 보내 주세요"
    log "$msg (종료 코드 $rc)"
    printf '%s (종료 코드 %s)\n' "$msg" "$rc" > "$RUN_DIR/FAILED"
    set_status "$msg"
  fi
}

step_preflight() {
  step 1 "사전 점검 (GPU·디스크)"
  { echo "시작: $(date '+%Y-%m-%d %H:%M:%S')"; echo "호스트: $(hostname)"; } > "$RUN_DIR/env.txt"

  if [ "${ALLOW_CPU:-}" = "1" ]; then
    echo "ALLOW_CPU=1 — nvidia-smi·CUDA 확인을 건너뜁니다" >> "$RUN_DIR/env.txt"
  else
    command -v nvidia-smi >/dev/null 2>&1 || die "nvidia-smi가 없습니다 — GPU 잡이 맞는지 확인하세요 (CPU로 시험하려면 ALLOW_CPU=1)"
    nvidia-smi -L >> "$RUN_DIR/env.txt" 2>&1 || die "nvidia-smi -L이 실패했습니다 — GPU가 잡에 붙어 있는지 확인하세요"
    # MIG 조각에서는 일부 항목이 N/A로 나올 수 있어 이 조회가 실패해도 멈추지 않는다 (기록용이다)
    nvidia-smi --query-gpu=name,driver_version,memory.total --format=csv,noheader >> "$RUN_DIR/env.txt" 2>&1 \
      || echo "(nvidia-smi --query-gpu 조회 실패)" >> "$RUN_DIR/env.txt"
  fi
  cat "$RUN_DIR/env.txt"

  local free_kb free_gb
  free_kb="$(df -Pk "$ROOT" | awk 'NR==2 {print $4}')"
  free_gb=$(( ${free_kb:-0} / 1024 / 1024 ))
  log "$ROOT 빈 공간 ${free_gb}GB (필요 ${MIN_FREE_GB}GB)"
  [ "$free_gb" -ge "$MIN_FREE_GB" ] || die "$ROOT 빈 공간이 ${free_gb}GB뿐입니다 — ${MIN_FREE_GB}GB 이상 필요합니다 (모델·환경·체크포인트)"
}

env_hash() {
  { cat "$SELF_DIR/requirements-train.txt"; printf '%s\n%s\n' "$TORCH_SPEC" "$TORCH_INDEX"; } | sha256_stdin
}

install_env() {
  local want
  want="$(env_hash)"
  if [ -x "$VENV/bin/python" ] && [ -f "$VENV/.ef-ready" ] && [ "$(cat "$VENV/.ef-ready")" = "$want" ]; then
    log "환경이 이미 준비돼 있습니다 ($VENV) — 설치를 건너뜁니다"
    return 0
  fi
  log "환경을 만듭니다: uv $UV_SPEC → Python 3.12 → $TORCH_SPEC ($TORCH_INDEX) → requirements-train.txt"
  export PIP_DISABLE_PIP_VERSION_CHECK=1
  python3 -m pip install --quiet "$UV_SPEC"
  python3 -m uv python install 3.12
  rm -rf "${VENV:?}"   # 설치가 중간에 끊겨 반쯤 만들어진 환경이 남았을 수 있다
  python3 -m uv venv --python 3.12 "$VENV"
  python3 -m uv pip install --python "$VENV/bin/python" "$TORCH_SPEC" --index-url "$TORCH_INDEX"
  python3 -m uv pip install --python "$VENV/bin/python" -r "$SELF_DIR/requirements-train.txt"
  printf '%s\n' "$want" > "$VENV/.ef-ready"
  log "환경 준비 완료"
}

# 실제로 커널이 도는지까지 본다 — is_available()은 참인데 첫 연산에서 "no kernel image"로 죽는 빌드·드라이버 조합이 있다
CHECK_CUDA_CODE='import sys, torch
print("python", sys.version.split()[0])
print("torch", torch.__version__, "cuda", torch.version.cuda)
ok = torch.cuda.is_available()
print("cuda_available", ok)
if ok:
    print("device", torch.cuda.get_device_name(0))
    print("bf16_supported", torch.cuda.is_bf16_supported())
    x = torch.ones(8, 8, device="cuda") @ torch.ones(8, 8, device="cuda")
    torch.cuda.synchronize()
    print("matmul_ok", float(x.sum()) == 512.0)
sys.exit(0 if ok else 3)'

check_cuda() {
  local out rc=0 driver
  out="$("$PY" -c "$CHECK_CUDA_CODE" 2>&1)" || rc=$?
  printf '%s\n' "$out" | tee -a "$RUN_DIR/env.txt"
  if [ "$rc" -ne 0 ]; then
    driver="$(nvidia-smi --query-gpu=driver_version --format=csv,noheader 2>/dev/null | head -n 1 || true)"
    die "CUDA를 쓸 수 없습니다 — 드라이버가 CUDA 12.6을 지원하지 않습니다 (드라이버 ${driver:-버전 모름}, 525 이상 필요). 위 출력을 보내 주세요"
  fi
}

step_env() {
  step 2 "파이썬 환경"
  if [ -n "${PYTHON:-}" ]; then
    PY="$PYTHON"
    log "PYTHON 지정 — 환경 설치를 건너뜁니다: $PY"
  elif [ "${SKIP_INSTALL:-}" = "1" ]; then
    PY="$VENV/bin/python"
    [ -x "$PY" ] || die "SKIP_INSTALL=1인데 $PY 가 없습니다"
    log "SKIP_INSTALL=1 — 환경 설치를 건너뜁니다: $PY"
  else
    PY="$VENV/bin/python"
    install_env
  fi
  if [ "${ALLOW_CPU:-}" != "1" ]; then check_cuda; fi
}

step_model() {
  step 3 "모델 내려받기"
  if [ "${HF_HUB_OFFLINE:-}" = "1" ]; then
    log "HF_HUB_OFFLINE=1 — 내려받기를 건너뜁니다 (HF_HOME=$HF_HOME)"
    return 0
  fi
  log "$MODEL (revision ${REVISION:-없음}) → $HF_HOME"
  "$PY" -c 'import os
from huggingface_hub import snapshot_download
print("모델 경로:", snapshot_download(os.environ["MODEL"], revision=os.environ.get("REVISION") or None))'
  # 받은 뒤에는 오프라인으로 돈다. 변형마다 모델을 다시 읽을 때 남는 Hub 조회(저장소에 없는 선택 파일 확인 등)가
  # 학교망이 잠깐 끊긴 순간에 걸려 그 변형이 실패하지 않게 한다. 1단계 Mac 서버도 같은 방식으로 돌렸다
  export HF_HUB_OFFLINE=1
  log "모델을 받았습니다 — 이후 학습은 받아 둔 파일만 씁니다 (HF_HUB_OFFLINE=1)"
}

step_train() {
  local total=0 ok=0 i=0 v rc
  for v in $VARIANTS; do total=$((total + 1)); done
  step 4 "학습 (변형: $VARIANTS)"
  for v in $VARIANTS; do
    i=$((i + 1))
    set_status "$CURRENT_STEP — 변형 $v ($i/$total)"
    log "변형 $v 시작 ($i/$total)"
    mkdir -p "$RUN_DIR/$v"
    rm -f "$RUN_DIR/$v/FAILED" "$RUN_DIR/$v/metrics.json"
    # 한 변형이 실패해도 다음 변형으로 넘어간다 — 세 번 중 하나가 죽었다고 두 번의 학습 시간을 버리지 않는다
    if "$PY" "$SELF_DIR/train.py" train \
        --data-dir "$RUN_DIR/data" --variant "$v" --model "$MODEL" --revision "$REVISION" \
        --out "$RUN_DIR/$v" $TRAIN_EXTRA_ARGS; then
      ok=$((ok + 1))
      log "변형 $v 끝"
    else
      rc=$?
      log "변형 $v 실패 (종료 코드 $rc) — 다음 변형으로 넘어갑니다"
      # train.py가 사유를 못 남기고 죽은 경우(메모리 부족으로 강제 종료 등) summarize가 실패로 읽게 표시를 대신 남긴다
      if [ ! -f "$RUN_DIR/$v/FAILED" ]; then
        echo "train.py가 종료 코드 $rc로 끝났습니다 (run.log 참고)" > "$RUN_DIR/$v/FAILED"
      fi
    fi
  done
  [ "$ok" -gt 0 ] || die "모든 변형이 실패했습니다 — run.log를 보세요"
  log "학습 끝: 성공 $ok / $total"
}

step_summarize() {
  step 5 "변형 비교 (summary.json)"
  # 성공한 변형이 없으면 summarize가 1로 끝나므로 결과를 읽기 전에 멈추지 않게 한다 — 아래에서 chosen이 비었는지로 판단한다
  "$PY" "$SELF_DIR/train.py" summarize --run-dir "$RUN_DIR" --variants "${VARIANTS// /,}" || true
  CHOSEN="$("$PY" -c 'import json, sys
print(json.load(open(sys.argv[1], encoding="utf-8")).get("chosen") or "")' "$RUN_DIR/summary.json")"
  [ -n "$CHOSEN" ] || die "성공한 변형이 없어 결과를 고를 수 없습니다"
  log "선택된 변형: $CHOSEN"
}

step_package() {
  step 6 "결과 묶기"
  local name stage v tgz digest
  name="result-$RUN_ID"
  stage="$RUN_DIR/$name"
  tgz="$RUN_DIR/$name.tgz"
  rm -rf "${stage:?}"
  mkdir "$stage"
  # 고른 변형의 모델만 담는다. 나머지 변형의 model/은 지우지 않으므로 나중에 필요하면 $RUN_DIR/<변형>/model에서 받는다
  cp -R "$RUN_DIR/$CHOSEN/model" "$stage/model"
  cp "$RUN_DIR/summary.json" "$stage/summary.json"
  for v in $VARIANTS; do
    if [ -f "$RUN_DIR/$v/metrics.json" ]; then cp "$RUN_DIR/$v/metrics.json" "$stage/metrics-$v.json"; fi
    if [ -f "$RUN_DIR/$v/manifest.json" ]; then cp "$RUN_DIR/$v/manifest.json" "$stage/manifest-$v.json"; fi
  done
  cp "$RUN_DIR/env.txt" "$stage/env.txt"
  cp "$RUN_DIR/run.log" "$stage/run.log"   # 이 시점까지의 사본이다

  tar czf "$tgz" -C "$RUN_DIR" "$name"
  rm -rf "${stage:?}"
  digest="$(sha256_of "$tgz")"
  printf '%s  %s\n' "$digest" "$name.tgz" > "$tgz.sha256"
  printf '%s\n' "$name.tgz" > "$RUN_DIR/DONE"
  set_status "끝"
  log "sha256 $digest"
  log "끝: 내려받을 파일 = $tgz ($(human_size "$tgz"), sha256 앞 8자 ${digest:0:8})"
}

cmd_run() {
  RUN_DIR="${1:?_run은 실행 폴더를 받아야 합니다}"
  RUN_ID="$(basename "$RUN_DIR")"
  trap on_exit EXIT
  cd "$RUN_DIR"
  printf '%s\n' "$$" > "$RUN_DIR/pid"

  # 진행 줄이 파이프 버퍼에 갇히지 않게 하고, 로그에 진행 막대가 쌓이지 않게 한다
  export PYTHONUNBUFFERED=1 HF_HUB_DISABLE_PROGRESS_BARS=1 TOKENIZERS_PARALLELISM=false
  export MODEL REVISION
  log "실행 $RUN_ID: 모델 $MODEL (revision ${REVISION:-없음}), 변형 $VARIANTS, 추가 인자 '${TRAIN_EXTRA_ARGS}'"

  PY=""
  CHOSEN=""
  step_preflight
  step_env
  step_model
  step_train
  step_summarize
  step_package
}

# ----------------------------------------------------------------------------- main

usage() {
  echo "사용법: bash $SELF [start|status]"
  echo "  start   묶음을 검사하고 백그라운드로 학습을 시작합니다 (기본)"
  echo "  status  가장 최근 실행의 진행 상황을 봅니다"
}

main() {
  case "${1:-start}" in
    start) cmd_start ;;
    status) cmd_status ;;
    _run) shift; cmd_run "$@" ;;
    -h|--help|help) usage ;;
    *) usage >&2; exit 2 ;;
  esac
}

# source로 불러왔을 때는 main을 돌리지 않는다
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
  main "$@"
fi
