#!/bin/bash
# TDD Guard Hook — PreToolUse[Edit|Write]
# services/*/src/main 의 Java 프로덕션 코드를 작성하려 할 때, 대응 테스트 파일이 먼저 존재하는지 체크.
# 테스트 없이 구현 코드를 작성하려 하면 차단. 판단이 불확실하면 통과시켜 정상 작업을 막지 않는다.

INPUT=$(cat)

# TDD 가드 비활성화 스위치 — 리팩터링/설정/주석 등 테스트가 불필요한 작업 스프린트에서 끈다.
# settings.json 최상위 "env": { "TDD_GUARD": "off" } 로 세션 단위 설정한다.
[ "${TDD_GUARD:-on}" = "off" ] && exit 0

FILE_PATH=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty')

# 파일 경로가 없으면 통과
if [ -z "$FILE_PATH" ]; then
  exit 0
fi

# Windows 경로의 백슬래시를 슬래시로 정규화 (jq 가 이미 이스케이프는 풀어준 상태)
NORM=$(echo "$FILE_PATH" | sed 's#\\#/#g')

# services/*/src/main/java 아래의 .java 프로덕션 파일만 대상 — 그 외 통과
case "$NORM" in
  *services/*/src/main/java/*.java)
    ;;
  *)
    exit 0
    ;;
esac

# 비즈니스 로직 단위 테스트 대상이 아닌 계층 — 통과
# controller=얇은 위임층, repository=영속성 인터페이스(@DataJpaTest는 통합 테스트), dto/domain/config=데이터·설정
case "$NORM" in
  */dto/*|*/domain/*|*/config/*|*/controller/*|*/repository/*)
    exit 0
    ;;
esac

# 엔트리포인트·예외·설정·프로퍼티 등 로직 없는 파일은 테스트 불필요 — 통과
case "$NORM" in
  *Application.java|*/package-info.java|*Exception.java|*Config.java|*Properties.java)
    exit 0
    ;;
esac

# 본문이 빈 "순수 데이터 캐리어" record 는 폴더와 무관하게 통과 (예: service 계층의 결과 record).
# Cursor 처럼 본문에 메서드·컴팩트 생성자 등 로직이 있는 record 는 일반 클래스처럼 TDD 대상으로 둔다.
# PreToolUse라 파일이 아직 없으므로 Write 는 content, Edit 는 기존 파일을 검사 대상으로 삼는다.
BASE=$(basename "$NORM" .java)
RECORD_SRC=$(echo "$INPUT" | jq -r '.tool_input.content // empty')
if [ -z "$RECORD_SRC" ] && [ -f "$NORM" ]; then
  RECORD_SRC=$(cat "$NORM")
fi
if printf '%s' "$RECORD_SRC" | grep -Eq "\brecord[[:space:]]+${BASE}\b"; then
  # 타입 본문 중괄호 블록이 하나뿐이면(메서드/컴팩트 생성자 없음) 로직 없는 데이터 캐리어 — 통과
  brace_count=$(printf '%s' "$RECORD_SRC" | tr -cd '{' | wc -c | tr -d '[:space:]')
  if [ "$brace_count" -le 1 ]; then
    exit 0
  fi
fi

# 대응 테스트 파일 경로: src/main -> src/test, Foo.java -> FooTest.java
TEST_FILE=$(echo "$NORM" | sed 's#/src/main/#/src/test/#; s#\.java$#Test.java#')
TEST_NAME=$(basename "$TEST_FILE")

# 테스트 파일이 있으면 통과
if [ -f "$TEST_FILE" ]; then
  exit 0
fi

cat << EOF
{
  "hookSpecificOutput": {
    "hookEventName": "PreToolUse",
    "permissionDecision": "deny",
    "permissionDecisionReason": "TDD 강제: 대응 테스트 '${TEST_NAME}' 가 없습니다. 테스트 우선 -> 실패 확인 -> 구현 순서를 지켜야 합니다.\n\n[구현 subagent인 경우] 이 테스트를 직접 작성하지 마세요. 구현자가 자기 테스트를 쓰면 '통과하는가'만 보게 되어 결함을 구분하지 못하는 테스트가 됩니다. 여기서 작업을 멈추고 '테스트 '${TEST_NAME}' 가 없어 진행할 수 없음'을 보고하세요. 오케스트레이터가 테스트 작성을 먼저 진행시킵니다.\n\n[오케스트레이터인 경우] spring-test-writer subagent에 이 클래스의 테스트를 먼저 위임하고, 실패(red)를 확인한 뒤 구현을 위임하세요.\n\n테스트가 불필요한 변경(리팩터링/설정/주석 등)이면 사용자에게 알리고 TDD 가드를 꺼달라고 요청하세요 (settings.json 의 env 에 TDD_GUARD=off 추가). 가드를 끄기 전에는 같은 편집을 재시도해도 계속 차단됩니다."
  }
}
EOF

exit 0
