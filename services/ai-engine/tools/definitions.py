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
                "Jira 이슈 기준으로 Slack 논의 → Jira 생성 → 커밋 → PR 머지 순서를 "
                "UTC 기준 오름차순으로 반환한다. 타임라인 순서 검증에 사용."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "jira_key": {
                        "type": "string",
                        "description": "Jira 티켓 키",
                    }
                },
                "required": ["jira_key"],
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
]
