from tools.queries.explore import NODE_LABELS

TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "get_issue_context",
            "description": (
                "Jira 이슈 키로 관련 커밋, PR, Slack/GitHub 논의를 한 번에 조회한다. "
                "크로스 소스 연결(Jira→커밋→PR→Slack) 확인에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "jira_key": {
                        "type": "string",
                        "description": "Jira 티켓 키 (예: HT-12)",
                    }
                },
                "required": ["jira_key"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_changeset_context",
            "description": (
                "Git commit hash로 해당 커밋의 변경 이유를 조회한다. "
                "연결된 Jira 이슈, Slack 논의, PR, 파일별 diff 요약(diffSummary)을 반환. "
                "코드가 왜 바뀌었는지 파악할 때 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "hash": {
                        "type": "string",
                        "description": "Git commit hash",
                    }
                },
                "required": ["hash"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "find_expert",
            "description": (
                "특정 파일 또는 디렉토리에 가장 많이 기여한 사람을 식별한다. "
                "최근 6개월 커밋에 2배 가중치를 적용해 현재 담당자를 우선 반환. "
                "'이 모듈 가장 잘 아는 사람이 누구야?' 질문에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path_prefix": {
                        "type": "string",
                        "description": "파일 경로 또는 디렉토리 접두어 (예: src/auth/ 또는 src/auth/token.py)",
                    }
                },
                "required": ["path_prefix"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_timeline",
            "description": (
                "'언제', '어떤 순서로', '시간순으로', '과정' 류 질문의 기본 도구. "
                "Slack 논의·Jira 생성/완료·커밋·PR 오픈/머지를 UTC 오름차순으로 반환하며 "
                "각 이벤트에 event_meaning 라벨이 붙는다(시각만 보고 추정하지 말 것). "
                "스코프는 넷 중 하나를 고른다 — jira_key(이슈) / path(파일) / actor(사람) / "
                "전부 생략(프로젝트 전체). from_time·to_time으로 기간을 좁힐 수 있다. "
                "'이 프로젝트가 어떤 순서로 만들어졌어', 'OO가 5월에 뭐 했어'처럼 특정 "
                "엔티티가 없는 시간순 질문에도 쓴다."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "jira_key": {
                        "type": "string",
                        "description": "이슈 스코프 — Jira 티켓 키 (예: HT-45). 자식 이슈(CHILD_OF)까지 포함한다",
                    },
                    "path": {
                        "type": "string",
                        "description": (
                            "파일 스코프 — 저장소 내 **파일 경로**만 (예: src/auth/token.py). "
                            "그 파일을 바꾼 커밋과 담은 PR을 시간순으로. "
                            "커밋 해시·PR 번호·이슈 키를 여기 넣지 말 것 "
                            "(커밋 하나는 get_changeset_context를 쓴다)"
                        ),
                    },
                    "actor": {
                        "type": "string",
                        "description": "사람 스코프 — 이름·alias·이메일 중 하나",
                    },
                    "from_time": {
                        "type": "string",
                        "description": "조회 시작 시각 ISO-8601 (예: 2026-05-01T00:00:00Z). 생략 시 처음부터",
                    },
                    "to_time": {
                        "type": "string",
                        "description": "조회 종료 시각 ISO-8601. 생략 시 끝까지",
                    },
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "rank_issues",
            "description": (
                "이슈 전체를 지표로 정렬해 상위를 반환한다. '가장 오래/많이 논의된 티켓', "
                "'가장 오래 걸린 이슈', '논의가 제일 활발했던 작업'처럼 **전체를 비교해 1등을 "
                "뽑는** 질문에 쓴다. get_timeline은 특정 스코프의 시간축이라 전수 비교를 못 하므로 "
                "이런 랭킹 질문에 쓰면 안 된다(어쩌다 걸린 이슈를 최상위로 오답낸다). "
                "각 결과에 discussion_count와 duration_days가 함께 실린다."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "by": {
                        "type": "string",
                        "enum": ["discussion", "duration"],
                        "description": (
                            "정렬 기준. discussion=논의(DISCUSSED_IN) 수, "
                            "duration=생성→종료 경과일(종료된 이슈만). "
                            "'논의'·'댓글'·'많이 얘기된'이면 discussion, '오래 걸린'·'기간이 긴'이면 duration"
                        ),
                    },
                    "top_k": {
                        "type": "integer",
                        "description": "반환할 상위 개수 (기본 5, 최대 20)",
                        "default": 5,
                    },
                },
                "required": ["by"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "search_by_keyword",
            "description": (
                "자연어 키워드로 의미적으로 유사한 Slack/GitHub 메시지와 Jira 이슈를 탐색한다. "
                "특정 hash나 Jira key를 모를 때 진입점을 찾는 용도. "
                "결과에 연결된 커밋 hash와 Jira key가 포함되므로 이후 다른 도구 호출에 활용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "keyword": {
                        "type": "string",
                        "description": "검색할 자연어 키워드 또는 문장 (예: '결제 리팩토링', '인증 토큰 만료')",
                    },
                    "top_k": {
                        "type": "integer",
                        "description": "각 인덱스에서 반환할 최대 후보 수 (기본 5)",
                        "default": 5,
                    },
                    "threshold": {
                        "type": "number",
                        "description": "최소 코사인 유사도 (기본 0.30)",
                        "default": 0.30,
                    },
                },
                "required": ["keyword"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_actor_activity",
            "description": (
                "특정 사람의 커밋, PR, Slack 메시지, Jira 이슈 활동을 2계층으로 조회한다: "
                "detail(본문 포함 인용 대상 — 커밋·PR은 최신순, 메시지는 질문 관련도순)과 "
                "context(나머지 활동의 시간순 개요 — 본문 없음). 각 항목은 kind 필드로 구분. "
                "인용은 detail에서, context 항목을 인용하려면 kind별 상세 도구"
                "(commit→get_changeset_context, message→get_thread_context, "
                "pull_request→get_pr_context)로 본문을 조회한 뒤 인용. "
                "이름, GitHub login, 이메일 중 하나로 검색 가능. "
                "'junsu가 이번 주에 뭐 했어?' 같은 질문에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "identifier": {
                        "type": "string",
                        "description": "이름, alias(GitHub login 등), 또는 이메일 중 하나 (예: john-dev, jkim@co.com, John Kim)",
                    },
                    "from_time": {
                        "type": "string",
                        "description": "조회 시작 시각 ISO-8601 (예: 2026-05-01T00:00:00Z). 생략하면 전체 조회.",
                    },
                    # limit은 의도적으로 스키마에 없다 — LLM이 습관적으로 20을 넣어
                    # 조회 창을 옛 컷 크기로 되돌리는 것을 봉인 (detail 크기는 서버 예산이 결정)
                },
                "required": ["identifier"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_file_history",
            "description": (
                "특정 파일의 변경 이력을 2계층으로 반환한다: detail(질문 관련도 상위 커밋 — "
                "message·diffSummary·연결 이슈/PR 포함, 인용 대상)과 context(나머지 이력의 시간순 "
                "개요 — hash·요약만). 관련 있는 옛 커밋도 최신순 컷에 밀리지 않고 detail로 올라온다. "
                "'auth.py가 어떻게 변해왔는지' 파악할 때 사용. 인용은 detail에서, context 커밋을 "
                "인용하려면 그 hash로 get_changeset_context를 호출해 본문을 조회한 뒤 인용. "
                "strict path match가 비면 자동으로 basename → stem 순서로 fuzzy fallback 수행. "
                "다수 후보가 매칭되면 {message, candidates}을 반환 — candidates 중 정확한 경로로 재호출. "
                "단일 fuzzy 매칭이면 결과에 _resolved_via / _resolved_path가 부여되어, "
                "evidence에는 LLM이 추정한 path가 아닌 _resolved_path를 사용해야 함."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {
                        "type": "string",
                        "description": "파일 경로 (예: src/auth/token.py)",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "관련도 산정 대상 커밋 상한 (미지정 시 전체 이력). 보통 지정 불필요.",
                    },
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_missing_context",
            "description": (
                "Jira 이슈와도 Slack 논의와도 연결되지 않은 '고아 커밋'을 탐지한다. "
                "데이터 공백 구간 확인, 컨텍스트 없는 커밋 식별에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "from_time": {
                        "type": "string",
                        "description": "조회 시작 시각 ISO-8601. 생략하면 전체.",
                    },
                    "to_time": {
                        "type": "string",
                        "description": "조회 종료 시각 ISO-8601. 생략하면 현재.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "최대 반환 수 (기본 50)",
                        "default": 50,
                    },
                },
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "inspect_actor",
            "description": (
                "Actor 통합 결과를 확인한다. "
                "jkim@co.com(Jira), john-dev(GitHub), John Kim(Slack)이 하나의 노드로 통합됐는지, "
                "통합 confidence는 얼마인지 반환. Identity Resolution 검증에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "identifier": {
                        "type": "string",
                        "description": "이름, alias, 또는 이메일 중 하나",
                    }
                },
                "required": ["identifier"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_conflict_context",
            "description": (
                "하나의 커밋에 대해 Jira, Slack, PR이 각각 다른 맥락을 설명할 때 "
                "이를 출처별로 나란히 반환한다. "
                "컨텍스트 충돌 처리, 다중 관점 제시에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "hash": {
                        "type": "string",
                        "description": "Git commit hash",
                    }
                },
                "required": ["hash"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_recent_activity",
            "description": (
                "'최근에 뭐가 바뀌었어?', '이번 주 변경사항은?' 처럼 범위가 모호한 질문에 사용. "
                "지정 기간 내 커밋, PR, Jira 이슈, Slack 메시지를 최신순으로 반환."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "from_time": {
                        "type": "string",
                        "description": "시작 시각 ISO-8601 (예: 2026-05-03T00:00:00Z). '최근 7일'이면 현재 기준 7일 전 계산.",
                    },
                    "to_time": {
                        "type": "string",
                        "description": "종료 시각 ISO-8601. 생략하면 현재.",
                    },
                    "limit": {
                        "type": "integer",
                        "description": "최대 반환 수 (기본 30)",
                        "default": 30,
                    },
                },
                "required": ["from_time"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_pr_context",
            "description": (
                "PR 번호로 해당 PR에 포함된 커밋, 연결 Jira 이슈, Slack 논의, 파일 변경을 조회한다. "
                "PR 번호만 알고 있을 때 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "pr_number": {
                        "type": "integer",
                        "description": "GitHub PR 번호",
                    }
                },
                "required": ["pr_number"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "get_thread_context",
            "description": (
                "Slack 스레드를 conversation_id로 완전히 조회한다. "
                "스레드 전체 맥락 파악, 스레드 전파 검증에 사용. "
                "Slack 스레드 전용 — GitHub Issue나 다른 소스에는 사용 불가."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "conversation_id": {
                        "type": "string",
                        "description": "Slack 스레드 루트 메시지 ts (예: 1773799131.000200)",
                    }
                },
                "required": ["conversation_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_graph_query",
            "description": (
                "**전용 도구가 없는 질문에만** 쓰는 마지막 수단. 지식 그래프에 Cypher를 직접 "
                "실행한다. 속성으로 거르기(status·issue_type·state·channel), 개수 세기·집계, "
                "여러 조건을 엮는 조인(예: 'A가 만든 이슈 중 B가 논의한 것'), 이슈 외 노드 "
                "랭킹(예: '가장 많이 바뀐 파일')처럼 다른 도구로는 표현할 수 없는 질문에 쓴다.\n"
                "**쓰지 말아야 할 때(중요)**: 이슈 하나→get_issue_context, 커밋 하나→"
                "get_changeset_context, PR 하나→get_pr_context, 스레드→get_thread_context, "
                "파일 이력→get_file_history, 사람 활동→get_actor_activity, 시간순→get_timeline, "
                "이슈 랭킹→rank_issues, 키워드 탐색→search_by_keyword. 이 도구들이 커버하는 "
                "질문에 Cypher를 쓰면 더 나쁜 답이 나온다(본문·인용 원문이 빠진다). 전용 도구를 "
                "먼저 시도하고, 그 결과가 비었거나 질문이 위 유형일 때만 이 도구를 쓴다.\n"
                "제약: 읽기 전용 단일 쿼리(MATCH / OPTIONAL MATCH / WHERE / WITH / RETURN / "
                "ORDER BY / SKIP / LIMIT)만 된다. 모든 노드 패턴에 라벨이 있어야 하고, "
                "project_id 조건은 서버가 자동 주입하므로 쓰지 않는다. LIMIT을 안 쓰면 서버가 "
                "붙인다. 스키마는 시스템 프롬프트의 [그래프 스키마] 참고.\n"
                "**RETURN 설계 규칙**: 답변 근거로 인용하려면 식별자가 필요하다 — 커밋은 hash, "
                "PR은 pr_number, 이슈는 jira_key, 메시지는 conversation_id를 occurredAt·본문과 "
                "함께 RETURN한다. 개수만 세는 질의도 근거가 된 노드의 식별자를 함께 반환한다. "
                "노드 전체(RETURN n)보다 필요한 속성만 고르는 편이 좋다."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "cypher": {
                        "type": "string",
                        "description": (
                            "실행할 Cypher. 예: MATCH (i:Issue) WHERE i.status <> '완료' "
                            "RETURN i.jira_key, i.title, i.status ORDER BY i.createdAt"
                        ),
                    },
                    "purpose": {
                        "type": "string",
                        "description": "이 쿼리로 확인하려는 것 한 줄 (기록용 — 실행에는 영향 없음)",
                    },
                },
                "required": ["cypher", "purpose"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "describe_graph",
            "description": (
                "run_graph_query를 쓰기 전에 특정 노드 라벨의 실제 데이터 분포를 확인한다. "
                "노드 수, 실제로 존재하는 속성 목록, 주요 속성의 실제 값과 빈도를 반환한다. "
                "status·issue_type·channel 같은 값은 프로젝트마다 다르므로, 값으로 거르는 "
                "쿼리를 쓰기 전에 이 도구로 실제 값을 확인한다 "
                "(예: 완료 상태가 'Done'인지 '완료'인지)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "label": {
                        "type": "string",
                        "enum": sorted(NODE_LABELS),
                        "description": "조회할 노드 라벨",
                    }
                },
                "required": ["label"],
            },
        },
    },
]
