# Actor 노드 생성 설계

## 개요

Actor 노드는 GitHub·Jira·Slack의 사용자를 하나의 노드로 통합하는 지식 그래프의 중심 개념이다.
같은 사람이 플랫폼마다 다른 ID, 이름, 이메일을 사용할 수 있기 때문에 초기 생성 시 최고 신뢰도로 동일인을 판단하는 것이 핵심이다.

**설계 원칙**: Actor 노드는 초기 1회에 최고 신뢰도로 생성한다. 이후 동일 source_id가 도착하면 alias 조회로 O(1) 처리. 비싼 판단 비용은 "처음 본 actor"에게만 발생한다.

---

## Actor 노드 스펙

```json
{
  "uuid": "",            // 노드 식별자 (UUID) — 모든 관계 연결의 기준키
  "project_id": "",      // 프로젝트 스코프 — 동일인 판단은 프로젝트 경계를 넘지 않는다
  "name": "",            // 표시 이름 (가장 신뢰도 높은 소스 기준)
  "normalized_name": "", // 이름 정규화 결과 — Step 2 후보 조회 키
  "aliases": [""],       // 소스별 원본 ID 목록 (예: "GITHUB:john-doe", "JIRA:557058:abc")
  "emails": [""],        // 확인된 모든 이메일 목록 (복수 허용)
  "confidence": 1.0      // alias 통합 신뢰도 (LLM 판단 케이스에만 < 1.0)
}
```

`emails`를 배열로 설계하는 이유: 같은 사람이 GitHub엔 개인 이메일, Jira·Slack엔 회사 이메일을 쓸 수 있으며, 로컬파트까지 완전히 다를 수 있기 때문이다.

**프로젝트 스코프**: Actor 노드는 `project_id`로 스코프된다. 동일 인물이 두 프로젝트에 등장하면 프로젝트마다 별도 Actor 노드가 생성되며, 동일인 판단(alias/email/name 매칭)은 같은 프로젝트 안에서만 이뤄진다. ActorStore는 `make_neo4j_actor_store(project_id)`로 project_id를 바인딩해 주입된다.

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

## ActorStore 인터페이스

`actor_resolver.py`는 Neo4j를 직접 호출하지 않고 아래 인터페이스(`ActorStore`)를 주입받아, 테스트 시 mock으로 교체 가능하게 분리돼 있다. 실제 Neo4j 구현체는 `builder.py`의 `make_neo4j_actor_store(project_id)`가 제공한다.

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
MATCH (a:Actor {project_id: $project_id})
WHERE $source_id IN a.aliases
RETURN a
```

→ 존재하면: 해당 Actor 반환, 이후 관계(CREATED/WROTE/AUTHORED) 생성만 수행  
→ 없으면: Step 1으로

### Step 1 — Email 정확 매칭

pipeline-worker가 가져온 email이 기존 Actor의 `emails` 배열과 교집합이 있는지 확인.

```cypher
MATCH (a:Actor {project_id: $project_id})
WHERE $new_email IN a.emails
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
score += best_localpart_ratio * 0.3   # 이메일 로컬파트 유사도(SequenceMatcher 최댓값), 최대 +0.3
score += 0.2 if 이메일_도메인_일치 else 0.0   # 같은 도메인 = 동일 조직 신호
# new_email이 없거나 후보에 email이 없으면 base 0.5만 적용
```

후보를 점수 내림차순으로 정렬해 **상위 `_MAX_LLM_CANDIDATES`(3)명**을 Step 3(LLM)에 차례로 넘긴다.
점수가 `0.4` 미만인 후보를 만나면 루프를 중단하고, MERGE되는 후보가 없으면 Step 4(신규 생성)로 간다.

### Step 3 — LLM 다중 신호 판단 (활동 맥락 포함)

Step 2에서 판단이 애매한 케이스. **이름·이메일뿐만 아니라 기존 Actor의 실제 활동 내용과 신규 이벤트 내용을 함께 제공**한다.

사람은 이름·이메일을 바꿀 수 있지만, 작업하는 도메인·기술 스택·관심사는 일관성이 있어 신뢰도가 크게 높아진다.

**Neo4j에서 기존 Actor 활동 조회**:

```cypher
MATCH (a:Actor {uuid: $actor_uuid})-[:AUTHORED|WROTE|CREATED]->(n)
WHERE n.occurredAt IS NOT NULL
RETURN labels(n)[0] AS nodeType, n.source, n.title, n.message, n.body, n.channel, n.occurredAt
ORDER BY n.occurredAt DESC
LIMIT 10
```

**LLM에 제공하는 신호** (실제 프롬프트 전문·few-shot 예시는 `actor_llm.py._build_prompt` 참고):

- 두 사용자의 이름·이메일·플랫폼(aliases)
- 기존 Actor의 최근 활동 10건(날짜·소스·채널·제목/본문) + 신규 이벤트 내용
- 판단 기준(중요도 순): ① 이름 표기 변형(한/영, 성·이름 역전, 닉네임/대소문자/구분자 차이) ② 이메일 로컬파트 유사도·동일 도메인 ③ 활동 시기·도메인·기술 스택 겹침(시간이 가까울수록 강한 신호) ④ 같은 생태계(회사 도메인·채널·레포)
- 한국 이름 동명이인 주의: 둘 이상의 독립 신호가 일치하지 않으면 confidence를 낮추도록 지시
- 응답: `{same_person, confidence, key_signals, reason}` JSON (`response_format=json_object`, `temperature=0`)

→ `same_person=true` AND `confidence ≥ 0.9`: MERGE, Actor의 `confidence` 필드에 저장  
→ 미달: 다음 후보로, 모두 미달이면 Step 4 (신규 노드 생성)

> **닭-달걀 문제**: 기존 Actor에 활동 내용이 없으면 이름·이메일만으로 판단하며 confidence가 낮게 나오는 것이 정상이다. 이 경우 각각 별도 Actor 노드로 생성된다.

LLM 호출은 **"처음 보는 actor"에게만** 발생한다. 이후 같은 actor.id는 Step 0에서 종료.

### Step 4 — 신규 Actor 노드 생성

신뢰도 있는 매칭 없음 → 새 Actor 노드 생성.

```cypher
CREATE (a:Actor {
  uuid: $uuid,                       // 신규 생성 UUID
  project_id: $project_id,
  name: $name,
  normalized_name: $normalized_name, // normalize_name($name)
  aliases: [$source_id],             // "GITHUB:john-doe"
  emails: [$email],                  // email이 null이면 빈 배열 []
  confidence: 1.0
})
```

