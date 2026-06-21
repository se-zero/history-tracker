"""공유 OpenAI 클라이언트.

여러 모듈이 각자 import 시점에 OpenAI()를 만들던 것을 단일 lazy 싱글턴으로 합친다.
- lazy: 최초 호출 시점에 생성 → 모듈 import만으로는 OPENAI_API_KEY가 필요 없다 (오프라인 import/테스트 가능).
- 단일 설정점: base_url·timeout·재시도 등을 이 한 곳에서만 조정한다.

호출/임베딩 모델명은 각 호출부의 인자로 남겨둔다 (클라이언트 설정과 별개 관심사).
"""

import os
from functools import lru_cache

from openai import OpenAI

# 호출 상한. 가장 긴 경로(에이전트 tool-calling)에 맞춰 통일.
_DEFAULT_TIMEOUT = 60.0


@lru_cache(maxsize=1)
def get_openai_client() -> OpenAI:
    """프로세스 전역에서 재사용하는 OpenAI 클라이언트."""
    return OpenAI(api_key=os.environ["OPENAI_API_KEY"], timeout=_DEFAULT_TIMEOUT)
