"""Document → Issue text DESCRIBED_IN 링크에 관한 공용 정책 상수.

수집 가드(graph.event_handler._handle_document)와 소급 정리
(graph.maintenance.clear_bulk_document_issue_links)가 이 모듈의 값을 같이 읽어야 한다 —
두 경로가 각자 상수를 들고 있으면 한쪽만 바뀌었을 때 "새로 들어오는 이벤트는 막는데
이미 들어온 오염은 다른 기준으로 지운다(또는 그 반대)"는 불일치가 생긴다.
"""

import os

# Document → Issue text DESCRIBED_IN 상한 (issueKeys distinct + issueExternalRefs distinct 합).
# 실측: QA 문서처럼 이슈 키를 나열만 한 문서는 text 엣지 10~29개를 만드는 반면, 실제로
# 특정 이슈를 설명하는 정상 문서는 1~3개에 그친다 — 3과 10 사이에 뚜렷한 간극이 있어 그 사이인
# 5를 기본값으로 둔다. text 엣지는 confidence=1.0 고정이라 읽기 필터(0.5)를 무조건 통과해
# 진짜 설계 문서를 밀어내므로, 이 상한이 없으면 색인성 문서가 그래프 품질을 갉아먹는다.
DOCUMENT_ISSUE_REF_LIMIT = int(os.environ.get("DOCUMENT_ISSUE_REF_LIMIT", "5"))
