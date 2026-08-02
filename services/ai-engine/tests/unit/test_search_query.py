"""통합 검색 Lucene 쿼리 빌더 단위 테스트 (오프라인).

Neo4j 없이 도는 순수 함수만 검증한다 — 사용자 입력 → Lucene 쿼리 변환
(escape, ASCII prefix 와일드카드, 한글/복합 키 passthrough).
실제 full-text 조회·랭킹은 live Neo4j(integration) 영역.
"""

from graph.search import _SEARCH_QUERY, build_lucene_query


def test_ascii_terms_get_prefix_wildcard_and_lowercase():
    # 와일드카드 term은 Lucene이 분석(소문자화)하지 않으므로 빌더가 직접 소문자로 맞춘다
    assert build_lucene_query("auth") == "auth*"
    assert build_lucene_query("Auth Token") == "auth* AND token*"


def test_korean_terms_become_phrases():
    # cjk analyzer가 bigram으로 분석 + phrase가 인접을 강제해 substring처럼 매치된다
    assert build_lucene_query("인증 토큰") == '"인증" AND "토큰"'


def test_mixed_korean_and_ascii():
    assert build_lucene_query("로그인 fix") == '"로그인" AND fix*'


def test_composite_keys_become_phrases():
    # 'HT-104' 같은 복합 키는 phrase로 — ht·104 토큰 인접 매치라 'HT'만으로는 안 잡힌다
    assert build_lucene_query("HT-104") == '"HT-104"'
    assert build_lucene_query("a:b/c") == '"a:b/c"'
    # phrase 문법을 깨는 따옴표·백슬래시만 escape
    assert build_lucene_query('say-"hi"') == '"say-\\"hi\\""'


def test_search_query_excludes_deleted_user_actors():
    # 개인정보가 하나도 없는 "(삭제된 사용자)" Actor는 검색어와 우연히 일치해도 노이즈라 제외한다
    assert "NOT (n:Actor AND n.name = $deleted_label)" in _SEARCH_QUERY


def test_punctuation_only_terms_dropped():
    # 구두점뿐인 term은 분석 후 토큰이 없어 AND 절 전체를 0건으로 만들므로 버린다
    assert build_lucene_query("auth -") == "auth*"
    assert build_lucene_query("&& ||") == ""
    assert build_lucene_query("") == ""
    assert build_lucene_query("   ") == ""
