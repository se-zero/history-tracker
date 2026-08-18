#!/usr/bin/env bash
# Stop 훅: 이번 턴에 단위 테스트가 통과한 서비스들의 전체 테스트를 1회 모아서 실행한다.
#
# unit-test-on-edit.sh(PostToolUse)가 /tmp/claude-tdd-guard/<session>.services 에 적어 둔 서비스 경로를
# 모아 실행한다. 코드를 안 건드린 턴(질문만 한 경우 등)에는 마커가 없으므로 조용히 통과한다.
#
# 출력 규약: 사람이 읽는 진행 로그는 stderr 로 보내고, stdout 은 Stop 차단 JSON 전용으로 비워 둔다
# (stdout 에 비-JSON 텍스트가 섞이면 차단 결정 파싱이 깨지기 때문).
# 전체 테스트가 실패하면 Claude 에게 회귀를 알려 고치게 한다. 단 Stop 훅으로 이미 재진입한
# 경우(stop_hook_active)에는 무한 반복을 막기 위해 차단하지 않는다.

FAIL_KEYWORDS=("FAILED" "ERROR" "Exception" "expected:" "but was:" "AssertionError" "tests were" "BUILD FAILED")

# 실패 키워드가 든 줄만 최대 limit 개 추려 stdout 으로 반환한다.
extract_failures() {
  local output="$1" limit=15 shown=0 line s
  while IFS= read -r line; do
    s="${line#"${line%%[![:space:]]*}"}"
    [ -z "$s" ] && continue
    for kw in "${FAIL_KEYWORDS[@]}"; do
      if [[ "$s" == *"$kw"* ]]; then
        printf '  %s\n' "$s"; shown=$((shown + 1)); break
      fi
    done
    if [ "$shown" -ge "$limit" ]; then printf '  ... (이하 생략)\n'; break; fi
  done <<< "$output"
}

input=$(cat)
session_id=$(printf '%s' "$input" | jq -r '.session_id // empty' 2>/dev/null)
stop_active=$(printf '%s' "$input" | jq -r '.stop_hook_active // false' 2>/dev/null)

marker_file="/tmp/claude-tdd-guard/${session_id:-default}.services"
[ -f "$marker_file" ] || exit 0   # 이번 턴에 기록된 서비스 없음 → 통과

# 마커 소비: 중복 제거 후 즉시 삭제 (다음 턴에 중복 실행 방지)
services=$(sort -u "$marker_file")
rm -f "$marker_file"
[ -z "$services" ] && exit 0

failed=""
fail_detail=""
while IFS= read -r service_root; do
  [ -z "$service_root" ] && continue
  [ -d "$service_root" ] || continue
  svc_name=${service_root##*/}

  printf '\n[suite] %s 전체 테스트 실행 중...\n' "$svc_name" >&2
  output=$(cd "$service_root" && timeout 300 ./gradlew test -q 2>&1)
  code=$?

  # 병렬 위임 중 서브에이전트의 gradle과 경합하면 test-results 파일 경합으로 실패한다
  # (build/test-results/.../in-progress-results-*.bin NoSuchFileException). 리그레션이 아니므로
  # 잠시 기다렸다 1회 재시도하고, 재시도도 같은 충돌이면 차단하지 않는다.
  # 대신 마커를 되살려 다음 턴 종료 시 이 서비스의 전체 테스트가 다시 돌게 한다.
  if [ "$code" -ne 0 ] && [ "$code" -ne 124 ] && printf '%s' "$output" | grep -q "in-progress-results"; then
    printf '[suite] %s gradle 동시 실행 충돌 감지 — 20초 후 재시도\n' "$svc_name" >&2
    sleep 20
    output=$(cd "$service_root" && timeout 300 ./gradlew test -q 2>&1)
    code=$?
    if [ "$code" -ne 0 ] && printf '%s' "$output" | grep -q "in-progress-results"; then
      printf '[suite] %s 여전히 동시 실행 충돌 — 리그레션 아님, 차단 생략 (다음 턴 종료 시 재실행)\n' "$svc_name" >&2
      mkdir -p "${marker_file%/*}" 2>/dev/null
      printf '%s\n' "$service_root" >> "$marker_file"
      continue
    fi
  fi

  if [ "$code" -eq 124 ]; then
    printf '[suite] %s 타임아웃 (300초) — 건너뜀\n' "$svc_name" >&2
    continue
  fi
  if [ "$code" -eq 0 ]; then
    printf '[suite] ✓ %s 전체 통과\n' "$svc_name" >&2
  else
    printf '[suite] ✗ %s 전체 테스트 실패\n' "$svc_name" >&2
    failed="${failed}${svc_name} "
    fail_detail="${fail_detail}[${svc_name}]"$'\n'"$(extract_failures "$output")"$'\n'
  fi
done <<< "$services"

# 실패가 없으면 통과
[ -z "$failed" ] && exit 0

# 이미 Stop 훅 재진입(직전 차단으로 인한 재시도)이면 더 차단하지 않고 통과 — 무한 반복 방지
if [ "$stop_active" = "true" ]; then
  printf '[suite] 전체 테스트 여전히 실패: %s(재검사 차단 생략)\n' "$failed" >&2
  exit 0
fi

# Claude 에게 회귀를 알려 수정하게 한다. reason 은 jq 로 안전하게 JSON 인코딩 (개행·따옴표 처리).
reason="턴 종료 전체 테스트 실패: ${failed}"$'\n'"${fail_detail}"$'\n'"리그레션입니다. 실패한 테스트를 확인하고 수정하세요."
jq -nc --arg r "$reason" '{decision:"block",reason:$r}'
exit 0
