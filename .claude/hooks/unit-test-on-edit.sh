#!/usr/bin/env bash
# PostToolUse 훅: Java 파일 수정 후 대응 단위 테스트만 즉시 실행한다.
#
# - 테스트 파일(*Test.java in src/test): 해당 클래스를 직접 실행
# - 프로덕션 파일(src/main): 대응 *Test.java가 존재하면 실행, 없으면 스킵
# - 개별 테스트 통과 시: 해당 서비스를 마커에 기록 — 서비스 전체 테스트는 매 편집이 아니라
#   턴 종료 시 Stop 훅(run-suite-on-stop.sh)이 모아서 1회 실행한다.
# 판단이 불확실하거나 오류가 나면 조용히 통과해 정상 작업을 막지 않는다.

SKIP_DIR_MARKERS=("/dto/" "/domain/" "/config/")
FAIL_KEYWORDS=("FAILED" "ERROR" "Exception" "expected:" "but was:" "AssertionError" "tests were" "BUILD FAILED")

# 편집된 파일(norm)로부터 실행할 테스트 파일 경로를 stdout 으로 반환한다. 없으면 빈 문자열.
resolve_test_path() {
  local norm="$1"

  case "$norm" in
    *"/src/test/java/"*Test.java) printf '%s' "$norm"; return ;;
  esac

  case "$norm" in
    *"/src/main/java/"*.java)
      for marker in "${SKIP_DIR_MARKERS[@]}"; do
        case "$norm" in *"$marker"*) return ;; esac
      done
      local base=${norm##*/}
      [ "$base" = "package-info.java" ] && return
      case "$base" in *Application.java) return ;; esac
      local test_norm=${norm/\/src\/main\//\/src\/test\/}
      test_norm="${test_norm%.java}Test.java"
      [ -f "$test_norm" ] && printf '%s' "$test_norm"
      ;;
  esac
}

# 매칭되는 keyword 가 든 줄만 추려서 최대 limit 개 출력한다.
print_failures() {
  local output="$1" limit=15 shown=0 line s
  while IFS= read -r line; do
    s="${line#"${line%%[![:space:]]*}"}"   # left trim
    [ -z "$s" ] && continue
    for kw in "${FAIL_KEYWORDS[@]}"; do
      if [[ "$s" == *"$kw"* ]]; then
        printf '  %s\n' "$s"
        shown=$((shown + 1))
        break
      fi
    done
    if [ "$shown" -ge "$limit" ]; then
      printf '  ... (이하 생략)\n'
      break
    fi
  done <<< "$output"
}

input=$(cat)

tool=$(printf '%s' "$input" | jq -r '.tool_name // empty' 2>/dev/null)
case "$tool" in Write|Edit) ;; *) exit 0 ;; esac

file=$(printf '%s' "$input" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
[ -z "$file" ] && exit 0

# 턴 종료 시 전체 테스트를 모아 돌리기 위한 세션 식별자 (Stop 훅과 마커 파일을 공유)
session_id=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)

# jq 가 이미 이스케이프를 풀었으므로 단일 백슬래시를 슬래시로 정규화한다 (Windows 경로 대응).
norm=$(printf '%s' "$file" | sed 's#\\#/#g')

test_path=$(resolve_test_path "$norm")
[ -z "$test_path" ] && exit 0

# service_root: services/backend | services/pipeline-worker 까지의 경로
service_root=""
for svc in services/backend services/pipeline-worker; do
  case "$test_path" in
    *"$svc"*) service_root="${test_path%%"$svc"*}${svc}"; break ;;
  esac
done

# fqn: /src/test/java/ 이후를 패키지 표기로 변환
marker="/src/test/java/"
case "$test_path" in
  *"$marker"*)
    rel="${test_path#*"$marker"}"
    rel="${rel%.java}"
    fqn="${rel//\//.}"
    ;;
  *) fqn="" ;;
esac

[ -z "$service_root" ] && exit 0
[ -z "$fqn" ] && exit 0

class_name=${test_path##*/}; class_name=${class_name%.java}
case "$service_root" in
  *"services/backend") svc_name="backend" ;;
  *) svc_name="pipeline-worker" ;;
esac

# 1단계: 해당 테스트 클래스만 실행
printf '\n[unit-test] %s 실행 중...\n' "$class_name"
output=$(cd "$service_root" && timeout 180 ./gradlew test "--tests=$fqn" -q 2>&1)
code=$?

if [ "$code" -eq 124 ]; then
  printf '[unit-test] 타임아웃 (180초) — %s\n' "$class_name"
  exit 0
fi
if [ "$code" -ne 0 ]; then
  printf '[unit-test] ✗ %s 실패\n' "$class_name"
  print_failures "$output"
  exit 0
fi

passed=$(printf '%s' "$output" | grep -c " PASSED")
summary=""
[ "$passed" -gt 0 ] && summary=" (${passed}개 케이스)"
printf '[unit-test] ✓ %s 통과%s\n' "$class_name" "$summary"

# 2단계: 서비스 전체 테스트는 매 편집마다 돌리지 않는다.
# 단위 테스트가 통과한 서비스 경로를 세션 마커에 적어 두면, 턴 종료 시 Stop 훅이 모아서 1회 실행한다.
STATE_DIR="/tmp/claude-tdd-guard"
mkdir -p "$STATE_DIR" 2>/dev/null
marker_file="$STATE_DIR/${session_id:-default}.services"
if ! grep -qxF "$service_root" "$marker_file" 2>/dev/null; then
  printf '%s\n' "$service_root" >> "$marker_file"
fi
printf '[unit-test] %s 전체 테스트는 턴 종료 시 1회 실행 예정\n' "$svc_name"
exit 0
