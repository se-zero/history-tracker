"""
임베딩 유틸리티 테스트.

실행 방법:
  OPENAI_API_KEY=sk-... python test_embedder.py

출력:
  각 케이스별 코사인 유사도 점수 및 판정 결과
"""

import asyncio
import os
import sys

from graph.embedder import cosine_similarity, embed_batch, embed_text

CASES = [
    {
        "label": "관련 있는 쌍 (한국어)",
        "a": "[수정] `OptimisticLockException` 처리 로직 추가 — 재시도 횟수 3회로 제한",
        "b": "낙관적 락 충돌 났을 때 재시도 로직 없어서 계속 터짐. 고쳐야 할 것 같아요",
        "expect": "threshold 초과 (REFERENCE 엣지 생성 대상)",
    },
    {
        "label": "관련 있는 쌍 (한/영 혼용)",
        "a": "[추가] JWT 토큰 만료 검증 미들웨어 추가",
        "b": "auth middleware for JWT expiry validation — should we add this to the pipeline?",
        "expect": "threshold 초과 (REFERENCE 엣지 생성 대상)",
    },
    {
        "label": "무관한 쌍",
        "a": "[수정] `OptimisticLockException` 처리 로직 추가",
        "b": "오늘 점심 메뉴 뭐예요? 저는 김치찌개 먹고 싶은데",
        "expect": "낮음 — threshold 미만이어야 함",
    },
    {
        "label": "유사한 도메인, 다른 내용",
        "a": "[수정] 비관적 락 방식의 문제점 발견, 추가 조사 필요",
        "b": "낙관적 락으로 교체 완료했습니다. PR 올렸어요",
        "expect": "경계 근처 — 도메인 겹치지만 내용 다름, false positive 주의",
    },
    {
        "label": "동일 의미 (한/영)",
        "a": "결제 API 타임아웃 버그 수정",
        "b": "fix payment API timeout bug",
        "expect": "threshold 초과 (REFERENCE 엣지 생성 대상)",
    },
   
    {
        "label": "직접 원인-결과 (threshold 초과해야 함)",
        "a": "[추가] 결제 요청 실패 시 재시도 큐에 넣는 로직 추가",
        "b": "결제 실패 건이 그냥 버려지고 있어요. 재시도 처리가 없는 것 같은데 추가해야 할 것 같아요",
        "expect": "threshold 초과 (REFERENCE 엣지 생성 대상)",
    },
    {
        "label": "같은 파일 다른 목적 (threshold 경계)",
        "a": "[수정] `UserService.login()` 에서 비밀번호 해싱 알고리즘 bcrypt로 교체",
        "b": "로그인 속도가 너무 느려요. 병목이 어디인지 확인해봐야 할 것 같아요",
        "expect": "경계 근처 — 같은 login 메서드지만 목적이 다름",
    },
    {
        "label": "기술 용어 겹침, 내용 무관 (false positive 체크)",
        "a": "[추가] Redis 캐시 TTL 설정 — 세션 만료 시간 30분",
        "b": "Redis 써본 사람 있어요? 다른 프로젝트에서 써봤는데 세팅이 복잡하더라고요",
        "expect": "낮음 — Redis 용어 겹치지만 실제 연관 없음 (false positive 주의)",
    },
    {
        "label": "간접 연관 (threshold 경계)",
        "a": "[제거] 사용되지 않는 `LegacyAuthFilter` 클래스 삭제",
        "b": "구버전 인증 방식 완전히 제거하기로 했어요. 관련 코드 정리 필요합니다",
        "expect": "threshold 초과 (REFERENCE 엣지 생성 대상)",
    },
    {
        "label": "완전 무관 (숫자/기호 위주)",
        "a": "[수정] 상수값 변경: `MAX_RETRY = 3` → `MAX_RETRY = 5`",
        "b": "내일 오후 2시에 배포 예정입니다. 참고해 주세요",
        "expect": "낮음 (<0.20) — threshold 미만이어야 함",
    },
]


async def test_similarity() -> None:
    print("=" * 60)
    print("코사인 유사도 테스트")
    print("=" * 60)

    passed = 0
    for i, case in enumerate(CASES):
        vec_a, vec_b = await asyncio.gather(embed_text(case["a"]), embed_text(case["b"]))
        score = cosine_similarity(vec_a, vec_b)

        print(f"\n[케이스 {i + 1}] {case['label']}")
        print(f"  A: {case['a'][:60]}")
        print(f"  B: {case['b'][:60]}")
        print(f"  유사도: {score:.4f}  (예상: {case['expect']})")
        passed += 1

    print(f"\n총 {passed}/{len(CASES)}개 케이스 실행 완료")


async def test_batch() -> None:
    print("\n" + "=" * 60)
    print("embed_batch 테스트 (API 1회 호출로 N개 처리)")
    print("=" * 60)

    texts = [case["a"] for case in CASES]
    texts_with_empty = ["", texts[0], "  ", texts[1]]

    vectors = await embed_batch(texts_with_empty)

    print(f"\n입력 {len(texts_with_empty)}개 중:")
    for i, (t, v) in enumerate(zip(texts_with_empty, vectors)):
        label = f'"{t[:30]}"' if t.strip() else "(빈 문자열)"
        print(f"  [{i}] {label} → 벡터 길이: {len(v)} ({'정상' if len(v) == 1536 else '빈 리스트' if len(v) == 0 else '오류'})")


async def main() -> None:
    if not os.environ.get("OPENAI_API_KEY"):
        print("실행 방법: OPENAI_API_KEY=sk-... python test_embedder.py")
        sys.exit(1)

    await test_similarity()
    await test_batch()


if __name__ == "__main__":
    asyncio.run(main())
