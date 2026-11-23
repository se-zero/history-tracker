#!/usr/bin/env bash
# infra/terraform/check.sh
# 모든 Terraform 루트·모듈에 fmt·validate·test를 실제 AWS에 닿지 않게 돌린다.
#
# 무엇을 검사하나
#   fmt       infra/terraform 전체 (인자와 무관)
#   init      -backend=false라 원격 상태 backend에 접속하지 않는다
#   validate  구문·참조 검사
#   test      tests/*.tftest.hcl이 있는 디렉터리만
#   js        functions/*.test.mjs가 있는 디렉터리만(node --test, CloudFront Functions 코드)
#
# 왜 두 겹으로 막나
#   terraform test는 mock_provider가 없으면 진짜 provider로 돌아 실제 계정에 리소스를 만든다.
#   1) 모든 *.tftest.hcl에 mock_provider "aws"가 있는지 먼저 확인하고, 하나라도 없으면 실행하지 않는다.
#   2) 그래도 새는 경우를 대비해 가짜 자격 증명으로 terraform을 돌린다.
#      한계: alias가 붙은 provider는 같은 alias의 mock_provider가 따로 있어야 한다.
#      빠진 alias는 진짜 provider로 돌다가 이 가짜 자격 증명에서 실패한다(계정에는 닿지 않는다).
#
# 설정 (환경변수)
#   TF_PLUGIN_CACHE_DIR  기본 $HOME/.terraform.d/plugin-cache — AWS provider가 744MB라
#                        디렉터리마다 내려받지 않고 공유한다
#
# 사용법
#   infra/terraform/check.sh                     bootstrap, envs/*, modules/* 중 *.tf가 있는 곳 전부
#   infra/terraform/check.sh bootstrap           infra/terraform 기준 상대 경로만
#   infra/terraform/check.sh modules/network envs/staging

set -euo pipefail

# 어디서 실행해도 같게 동작하도록 스크립트 위치를 기준으로 한다.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() { echo "ERROR: $*" >&2; exit 1; }

# ── 안전장치 1: 모든 테스트에 mock_provider가 있는지 ──────────────────
# 빠진 채로 terraform test를 돌리면 실제 계정에 리소스가 만들어지므로 가장 먼저 막는다.
# 줄 앞 공백만 허용해 주석 처리된 줄(# mock_provider ...)은 인정하지 않는다.
missing=""
while IFS= read -r f; do
  if ! grep -Eq '^[[:space:]]*mock_provider[[:space:]]+"aws"' "$f"; then
    missing="$missing$f"$'\n'
  fi
done < <(find "$ROOT" -name '.terraform' -prune -o -name '*.tftest.hcl' -type f -print | sort)
if [ -n "$missing" ]; then
  echo "mock_provider \"aws\"가 없는 테스트 파일:" >&2
  printf '%s' "$missing" >&2
  die "terraform test가 실제 AWS 계정에 리소스를 만들 수 있어 중단합니다"
fi

# ── 안전장치 2: 가짜 자격 증명 ───────────────────────────────────
# 진짜 자격 증명을 줄 수 있는 경로(프로필·세션·역할·웹 ID·컨테이너·인스턴스 메타데이터)를 모두 끊는다.
unset AWS_PROFILE AWS_DEFAULT_PROFILE AWS_SESSION_TOKEN AWS_SECURITY_TOKEN \
  AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE \
  AWS_CONTAINER_CREDENTIALS_FULL_URI AWS_CONTAINER_CREDENTIALS_RELATIVE_URI
export AWS_ACCESS_KEY_ID=mock
export AWS_SECRET_ACCESS_KEY=mock
export AWS_CONFIG_FILE=/dev/null
export AWS_SHARED_CREDENTIALS_FILE=/dev/null
export AWS_EC2_METADATA_DISABLED=true

export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-$HOME/.terraform.d/plugin-cache}"
mkdir -p "$TF_PLUGIN_CACHE_DIR"

# ── 대상 디렉터리 ────────────────────────────────────────────────
# 인자가 없으면 *.tf가 있는 bootstrap·envs/*·modules/* 전부. 아직 없는 디렉터리는 건너뛴다.
has_tf() { ls "$1"/*.tf >/dev/null 2>&1; }

targets=()
if [ "$#" -gt 0 ]; then
  for d in "$@"; do
    [ -d "$ROOT/$d" ] || die "$d 는 infra/terraform 아래 디렉터리가 아닙니다"
    has_tf "$ROOT/$d" || die "$d 에 *.tf가 없습니다"
    targets+=("$d")
  done
else
  # 글롭은 현재 디렉터리 기준으로 풀리므로 $ROOT를 붙여 풀고, 결과에서 $ROOT/를 떼어 상대 경로로 맞춘다.
  for p in "$ROOT/bootstrap" "$ROOT"/envs/* "$ROOT"/modules/*; do
    if [ -d "$p" ] && has_tf "$p"; then
      targets+=("${p#"$ROOT"/}")
    fi
  done
fi
[ "${#targets[@]}" -gt 0 ] || die "검사할 디렉터리가 없습니다"

# ── fmt ──────────────────────────────────────────────────────────
echo "==> fmt -check -recursive"
terraform -chdir="$ROOT" fmt -check -recursive || die "fmt 실패 — terraform -chdir=$ROOT fmt -recursive 로 고치세요"

# ── init · validate · test ───────────────────────────────────────
for d in "${targets[@]}"; do
  echo "==> $d"

  # init 출력은 길어서 성공하면 숨기고, 실패했을 때만 보여 준다.
  init_log="$(mktemp)"
  if ! terraform -chdir="$ROOT/$d" init -backend=false -input=false >"$init_log" 2>&1; then
    cat "$init_log" >&2
    rm -f "$init_log"
    die "$d: init 실패"
  fi
  rm -f "$init_log"

  terraform -chdir="$ROOT/$d" validate || die "$d: validate 실패"

  if ls "$ROOT/$d"/tests/*.tftest.hcl >/dev/null 2>&1; then
    terraform -chdir="$ROOT/$d" test || die "$d: test 실패"
  fi

  # CloudFront Functions 같은 JS 코드의 단위 테스트(node 내장 테스트 러너). 함수가 틀리면 사이트 전체가
  # 502·403이 되는데 가짜 provider로는 코드 동작을 못 본다. node가 없으면 건너뛰되 건너뛴 것을 알린다.
  if ls "$ROOT/$d"/functions/*.test.mjs >/dev/null 2>&1; then
    if command -v node >/dev/null 2>&1; then
      js_tests=()
      for f in "$ROOT/$d"/functions/*.test.mjs; do
        js_tests+=("$f")
      done
      node --test "${js_tests[@]}" || die "$d: JS 함수 테스트 실패"
    else
      echo "경고: node가 없어 $d/functions의 JS 테스트를 건너뜁니다" >&2
    fi
  fi
done

echo
echo "통과한 디렉터리:"
for d in "${targets[@]}"; do
  echo "  $d"
done
