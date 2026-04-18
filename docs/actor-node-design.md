# Actor 노드 생성 설계

## 개요

Actor 노드는 GitHub·Jira·Slack의 사용자를 하나의 노드로 통합하는 지식 그래프의 중심 개념이다.
같은 사람이 플랫폼마다 다른 ID, 이름, 이메일을 사용할 수 있기 때문에 초기 생성 시 최고 신뢰도로 동일인을 판단하는 것이 핵심이다.

**설계 원칙**: Actor 노드는 초기 1회에 최고 신뢰도로 생성한다. 이후 동일 source_id가 도착하면 alias 조회로 O(1) 처리. 비싼 판단 비용은 "처음 본 actor"에게만 발생한다.

---

## Actor 노드 스펙

```json
{
  "name": "",           // 표시 이름 (가장 신뢰도 높은 소스 기준)
  "aliases": [""],      // 소스별 원본 ID 목록 (예: "GITHUB:john-doe", "JIRA:557058:abc")
  "emails": [""],       // 확인된 모든 이메일 목록 (복수 허용)
  "confidence": 1.0     // alias 통합 신뢰도 (LLM 판단 케이스에만 < 1.0)
}
```

`emails`를 배열로 설계하는 이유: 같은 사람이 GitHub엔 개인 이메일, Jira·Slack엔 회사 이메일을 쓸 수 있으며, 로컬파트까지 완전히 다를 수 있기 때문이다.

```
GitHub:  johndoe@gmail.com    (개인 이메일)
Jira:    jdoe@company.com     (회사 이메일, 로컬파트도 다름)
Slack:   jdoe@company.com     (회사 이메일)
```

관계: `CREATED` / `WROTE` / `AUTHORED` (Layer 1 — 모든 이벤트에서 생성)

---

## 구현 위치

| 서비스 | 역할 |
|--------|------|
| `pipeline-worker` | `ActorDto`에 email 추가, 각 소스 API에서 email 수집 |
| `ai-engine` | RabbitMQ 소비 → Actor 동일인 판단 파이프라인 → Neo4j MERGE |

## 현재 구현 상태

| 파일 | 상태 | 내용 |
|------|------|------|
| `services/ai-engine/graph/actor_resolver.py` | ✓ 완료 | Step 0~4 파이프라인 로직. `ActorStore` 인터페이스로 Neo4j 분리 |
| `services/ai-engine/graph/actor_llm.py` | ✓ 완료 | Step 3 LLM 판단. `asyncio.to_thread()`로 비동기 래핑 |
| `services/ai-engine/graph/consumer.py` | ✓ 완료 | RabbitMQ 소비자 |
| `services/ai-engine/graph/event_handler.py` | △ 뼈대 | nodeType별 분기만 존재, `resolve_actor()` 연결 미완 |
| Neo4j ActorStore 구현체 | ✗ 미구현 | `ActorStore` 인터페이스의 실제 Neo4j 쿼리 구현 필요 |

### ActorStore 인터페이스

`actor_resolver.py`는 Neo4j를 직접 호출하지 않고 아래 인터페이스를 주입받는다.
Neo4j 구현체 작성 시 이 6개 메서드를 모두 구현해야 한다.

```python
@dataclass
class ActorStore:
    lookup_by_alias:    Callable[[str], Awaitable[Optional[dict]]]   # "GITHUB:john-doe" → Actor
    lookup_by_email:    Callable[[str], Awaitable[Optional[dict]]]   # 이메일 정확 매칭
    lookup_by_name:     Callable[[str], Awaitable[list[dict]]]       # 정규화 이름 후보 목록
    lookup_activities:  Callable[[dict], Awaitable[list[dict]]]      # 최근 활동 10개
    merge_actor:        Callable[[dict, str, Optional[str], float], Awaitable[None]]
    create_actor:       Callable[[str, list, list, float], Awaitable[dict]]
```

### 테스트

```bash
# actor_llm.py 단독 테스트 (Neo4j 없이)
cd services/ai-engine
OPENAI_API_KEY=sk-... python test_actor_llm.py ../../test_actor_cases.json
# 입력: existing_actor(수동 작성) + new_actor(실제 이벤트 데이터)
# 출력: test_actor_cases_results.json
```

---

## Actor 동일인 판단 파이프라인

새로운 actor.id가 도착했을 때 순서대로 실행한다.

```
Step 0: alias 캐시 조회          → 이미 아는 actor면 종료 (O(1), 비용 0)
Step 1: email 정확 매칭          → 확실한 동일인 (비용 최소)
Step 2: name 정규화 + email 교차 → 높은 신뢰도
Step 3: LLM 다중 신호 판단      → 애매한 케이스 (비용 발생, "처음 본 actor"에게만)
Step 4: 신규 Actor 노드 생성    → 판단 불가 시 fallback
```

### Step 0 — Alias 캐시 조회

source-scoped ID (예: `GITHUB:john-doe`)가 이미 어떤 Actor의 aliases에 있으면 즉시 반환.

```cypher
MATCH (a:Actor)
WHERE $source_id IN a.aliases
RETURN a
```

→ 존재하면: 해당 Actor 반환, 이후 관계(CREATED/WROTE/AUTHORED) 생성만 수행  
→ 없으면: Step 1으로

### Step 1 — Email 정확 매칭

pipeline-worker가 가져온 email이 기존 Actor의 `emails` 배열과 교집합이 있는지 확인.

```cypher
MATCH (a:Actor)
WHERE ANY(e IN a.emails WHERE e = $new_email)
RETURN a
```

→ 매칭 시: 해당 Actor에 `$source_id` alias 추가, 신규 email도 `emails`에 추가  
→ 없으면: Step 2로

### Step 2 — Name 정규화 + Email 교차 판단

이름 정규화 후 기존 Actor와 비교하고, email 신호를 보조 가중치로 활용한다.

**Name 정규화**:

```python
def normalize_name(name: str) -> str:
    # "John Doe" → "johndoe"
    # "김철수 (BE)" → "김철수"
    return re.sub(r"[^a-z0-9가-힣]", "", name.lower().strip())
```

**스코어링**:

```python
score = 0.5  # 이름 정규화 매칭 (lookup_by_name 조건이므로 항상 적용)
score += SequenceMatcher(None, new_localpart, candidate_localpart).ratio() * 0.5
# 이메일 로컬파트 유사도를 비율 그대로 반영 (최대 +0.5)
# 도메인(@company.com 등)은 식별력이 낮아 제외

if score >= 0.4:  # Step 3으로 (LLM 최종 확인)
if score < 0.4:   # Step 4로 (신규 노드 생성)
```

### Step 3 — LLM 다중 신호 판단 (활동 맥락 포함)

Step 2에서 판단이 애매한 케이스. **이름·이메일뿐만 아니라 기존 Actor의 실제 활동 내용과 신규 이벤트 내용을 함께 제공**한다.

사람은 이름·이메일을 바꿀 수 있지만, 작업하는 도메인·기술 스택·관심사는 일관성이 있어 신뢰도가 크게 높아진다.

**Neo4j에서 기존 Actor 활동 조회**:

```cypher
MATCH (a:Actor)-[:AUTHORED|WROTE|CREATED]->(n)
WHERE $candidate_actor_id IN a.aliases
RETURN n.message, n.title, n.body
ORDER BY n.occurredAt DESC
LIMIT 10
```

**LLM 프롬프트 구조**:

```python
prompt = f"""
다음 두 사용자가 동일인인지 판단해주세요.

[사용자 A — 기존 등록된 Actor]
- 이름: {existing_actor.name}
- 이메일들: {existing_actor.emails}
- 플랫폼: {existing_actor.aliases}
- 최근 활동 내용:
  · [GITHUB 커밋] "fix: 결제 서비스 낙관적 락 적용"
  · [GITHUB PR]   "payment service 동시성 리팩토링"
  · [SLACK]       "내일 결제 API 배포 예정입니다"

[사용자 B — 신규 이벤트의 Actor]
- 이름: {new_actor.name}
- 이메일: {new_email or "없음"}
- 플랫폼: {new_event.source}
- 이번 이벤트 내용:
  · [{new_event.source} {new_event.nodeType}]
    제목: {current_event_content['title'] or '없음'}
    본문: {current_event_content['body'] or '없음'}

판단 기준 (중요도 순):
1. 이름의 한/영 표기 변형, 성/이름 순서 역전, 닉네임 패턴 (john-doe↔john_doe, 대소문자, 구분자 차이 포함)
2. 이메일 로컬파트 유사도
3. 활동 내용의 도메인·기술 스택·관심사가 겹치는가
4. 같은 플랫폼 생태계(회사 도메인, 같은 채널/레포)에 속하는가

활동 내용이 없거나 너무 일반적이면 이름·이메일 신호에만 의존하고 confidence를 낮게 설정해주세요.

JSON으로 응답:
{{
  "same_person": true/false,
  "confidence": 0.0~1.0,
  "key_signals": ["활동 도메인 일치", "이메일 로컬파트 유사"],
  "reason": "..."
}}
"""
```

→ confidence ≥ 0.85: MERGE, Actor의 `confidence` 필드에 저장  
→ confidence < 0.85: Step 4 (신규 노드 생성)

> **닭-달걀 문제**: 기존 Actor에 활동 내용이 없으면 이름·이메일만으로 판단하며 confidence가 낮게 나오는 것이 정상이다. 이 경우 각각 별도 노드를 생성하고, 이후 이벤트가 쌓이면 배치 재판단이 가능하다.

LLM 호출은 **"처음 보는 actor"에게만** 발생한다. 이후 같은 actor.id는 Step 0에서 종료.

### Step 4 — 신규 Actor 노드 생성

신뢰도 있는 매칭 없음 → 새 Actor 노드 생성.

```cypher
CREATE (a:Actor {
  name: $name,
  emails: [$email],      // email이 null이면 빈 배열 []
  aliases: [$source_id], // "GITHUB:john-doe"
  confidence: 1.0
})
```

