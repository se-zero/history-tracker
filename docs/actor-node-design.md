# Actor 노드 생성 설계

## 개요

Actor 노드는 GitHub·Jira·Slack의 사용자를 하나의 노드로 통합하는 지식 그래프의 중심 개념이다.
같은 사람이 플랫폼마다 다른 ID, 이름, 이메일을 사용할 수 있기 때문에 초기 생성 시 최고 신뢰도로 동일인을 판단하는 것이 핵심이다.

**설계 원칙**: Actor 노드는 초기 1회에 최고 신뢰도로 생성한다. 이후 동일 source_id가 도착하면 alias 조회로 O(1) 처리. 비싼 판단 비용은 "처음 본 actor"에게만 발생한다.

개인정보(이름·이메일)는 Actor가 아니라 소스 계정 단위인 **ActorAlias** 노드의 `pd_*` 필드에 저장한다 — Atlassian 개인정보 보고·삭제가 "어느 소스에서 받았는지" 단위로 동작해야 하기 때문이다. Actor는 alias에서 유도한 표시 이름과 조회 키(aliases)만 갖는다.

---

## Actor 노드 스펙

```json
{
  "uuid": "",             // 노드 식별자 (UUID) — 모든 관계 연결의 기준키
  "project_id": "",       // 프로젝트 스코프 — 동일인 판단은 프로젝트 경계를 넘지 않는다
  "name": "",             // 표시 이름 — ActorAlias로부터 파생되는 값 (derive_display_name)
  "aliases": [""],        // 소스별 원본 ID 목록 (예: "GITHUB:john-doe", "JIRA:557058:abc")
  "manual_name": false,   // (수동 변경 시에만) 운영자가 표시 이름을 직접 확정했는지
  "name_updated_at": ""   // (수동 변경 시에만) 마지막 수동 변경 시각 (ISO-8601)
}
```

**프로젝트 스코프**: Actor 노드는 `project_id`로 스코프된다. 동일 인물이 두 프로젝트에 등장하면 프로젝트마다 별도 Actor 노드가 생성되며, 동일인 판단(alias/email/name 매칭)은 같은 프로젝트 안에서만 이뤄진다. ActorStore는 `make_neo4j_actor_store(project_id)`로 project_id를 바인딩해 주입된다.

관계: `CREATED` / `WROTE` / `AUTHORED` (Layer 1 — 모든 이벤트에서 생성)

---

## ActorAlias 노드 스펙

Actor가 가진 소스 계정 하나 (예: `GITHUB:john-doe`, `JIRA:557058:abc`). source_id마다 1개씩 존재하고 `(ActorAlias)-[:ALIAS_OF]->(Actor)`로 소속 Actor에 연결된다. 동일인 판단(Step 0~2)의 조회 키이자 Atlassian 개인정보 보고·삭제 단위다.

```json
{
  "project_id": "",            // 소속 프로젝트 UUID
  "source_id": "",             // 소스-스코프 계정 ID (예: "GITHUB:john-doe") — (project_id, source_id) 유니크
  "source": "",                // GITHUB | JIRA | SLACK
  "pd_name": "",               // 이 계정에서 받은 이름 — 표시 이름 유도 재료, node_search 검색 대상
  "pd_normalized_name": "",    // 정규화 이름 — Step 2 후보 조회 키
  "pd_email": "",              // 이 계정에서 받은 이메일 — Step 1 매칭 키
  "pd_updated_at": "",         // 이 개인정보를 획득한 시각 (ISO-8601)
  "pd_reported_at": "",        // Atlassian 개인정보 보고 시각 (Jira alias에만 의미)
  "pd_erased": null            // 삭제 사유 — null(정상) | "closed"(계정 폐쇄) | "access_lost"(재조회 불가)
}
```

이메일을 alias 단위로 보관하는 이유: 같은 사람이 GitHub엔 개인 이메일, Jira·Slack엔 회사 이메일을 쓸 수 있으며, 로컬파트까지 완전히 다를 수 있기 때문이다. 매칭 시에는 Actor의 전체 alias에서 `pd_email`을 모아(중복 제거) 비교한다.

```
GitHub:  johndoe@gmail.com    (개인 이메일)
Jira:    jdoe@company.com     (회사 이메일, 로컬파트도 다름)
Slack:   jdoe@company.com     (회사 이메일)
```

**인덱스** (`graph/schema.py`):

- `(project_id, source_id)` 유니크 제약 — Step 0 alias 조회를 배열 스캔 대신 인덱스 O(1)로 만들고, 동시 수집 시 같은 alias의 중복 Actor 생성을 MERGE로 막는다.
- range 인덱스 3종 — `(project_id, pd_normalized_name)`(Step 2 후보 조회), `(project_id, pd_email)`(Step 1 매칭), `(source, pd_reported_at)`(개인정보 보고 대상을 프로젝트 경계 없이 전역으로 훑는 용도라 의도적으로 project_id 미포함).
- full-text 인덱스 `node_search`가 `ActorAlias.pd_name`을 색인한다 — 표시 이름이 GitHub 기준으로 정해져도 Jira/Slack 이름으로 검색이 되게 하기 위함.

---

## 표시 이름 유도 규칙

`Actor.name`은 alias들로부터 파생되는 표시 전용 값이다. 유도 규칙은 `derive_display_name`(`graph/actor_store.py`) 한 곳에만 두고, alias가 바뀌는 모든 경로(Step 1/3 병합, Step 4 생성, Step 0 이름 갱신, 수동 병합·취소·분리·개인정보 삭제)가 `recompute_display_name`을 거쳐 재계산한다.

폴백 체인:

```
manual_name(수동 확정 이름 유지) > GitHub 프로필 이름 > 이름 있는 alias 중 소스 활동량 최다
> GitHub login (source_id의 "GITHUB:" 뒤) > "(삭제된 사용자)"
```

- GitHub은 `pd_name`이 login과 같으면 "프로필 이름 없음, login으로 대체 수집"으로 보고 프로필 이름·활동량 단계에서 제외한다 — pipeline-worker가 프로필 이름이 비어 있을 때 login을 name에 그대로 채워 보내기 때문이다. 그 값은 마지막 GitHub login 폴백 단계에서 어차피 다시 쓰인다.
- 소스마다 고정 서열을 두지 않고 활동량으로 일반화한다 — 데이터 소스가 늘어날 때마다 서열을 다시 정의해야 하는 유지 비용 때문이다. 소스 활동량은 outgoing `AUTHORED`/`CREATED`/`WROTE` + incoming `ASSIGNED_TO` 엣지 수로 센다.
- 빈 `pd_name`은 후보에서 제외한다. 동률은 소스명 → source_id 오름차순 — 어느 alias를 고를지 결정적으로 정해야 재계산이 매번 같은 값을 낸다.

---

## 구현 위치

| 서비스 | 역할 |
|--------|------|
| `pipeline-worker` | 각 소스 API에서 actor 정보(id·name·email)를 `ActorDto`로 수집 |
| `ai-engine` | RabbitMQ 소비 → Actor 동일인 판단 파이프라인 → Neo4j MERGE |

## ActorStore 인터페이스

`actor_resolver.py`는 Neo4j를 직접 호출하지 않고 아래 인터페이스(`ActorStore`)를 주입받아, 테스트 시 mock으로 교체 가능하게 분리돼 있다. 실제 Neo4j 구현체는 `graph/actor_store.py`의 `make_neo4j_actor_store(project_id)`가 제공한다.

```python
@dataclass
class ActorStore:
    lookup_by_alias:   Callable[[str], Awaitable[Optional[dict]]]   # "GITHUB:john-doe" → Actor (+ alias_pd_name·alias_pd_erased)
    lookup_by_email:   Callable[[str], Awaitable[Optional[dict]]]   # ActorAlias.pd_email 정확 매칭
    lookup_by_name:    Callable[[str], Awaitable[list[dict]]]       # pd_normalized_name 일치 후보 목록
    lookup_activities: Callable[[dict], Awaitable[list[dict]]]      # 최근 활동 10개
    merge_actor:       Callable[[dict, str, Optional[str], str], Awaitable[None]]  # (actor, new_alias, new_email, new_name)
    create_actor:      Callable[[str, str, Optional[str]], Awaitable[dict]]        # (name, source_id, email)
    lookup_vetoes:     Optional[Callable[[str], Awaitable[list[str]]]] = None      # 수동 distinct 결정 veto
    update_alias_name: Optional[Callable[[str, str], Awaitable[None]]] = None      # Step 0 이름 갱신
```

`lookup_vetoes`는 수동 분리(distinct) 결정이 이 source_id와 병합을 금지한 Actor uuid 목록을 반환하며, resolver가 Step 1 매칭과 Step 2 후보에서 해당 Actor를 제외한다 — 수동 결정이 자동 판단을 이긴다 (`docs/actor-manual-merge.md`).

---

## Actor 동일인 판단 파이프라인

새로운 actor.id가 도착했을 때 순서대로 실행한다.

```
Step 0: alias 캐시 조회          → 이미 아는 actor면 종료 + 이름 갱신 (O(1), 비용 0)
Step 1: email 정확 매칭          → 확실한 동일인 (비용 최소)
Step 2: name 정규화 + email 교차 → 높은 신뢰도
Step 3: LLM 다중 신호 판단      → 애매한 케이스 (비용 발생, "처음 본 actor"에게만)
Step 4: 신규 Actor 노드 생성    → 판단 불가 시 fallback
```

Step 0 미스 시 수동 분리 결정의 veto 집합(`lookup_vetoes`)을 조회해 Step 1/2에서 해당 Actor를 제외한다.

### Step 0 — Alias 캐시 조회

source-scoped ID (예: `GITHUB:john-doe`)가 이미 ActorAlias로 등록돼 있으면 즉시 반환.

```cypher
MATCH (al:ActorAlias {project_id: $project_id, source_id: $source_id})-[:ALIAS_OF]->(a:Actor)
RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases,
       al.pd_name AS alias_pd_name, al.pd_erased AS alias_pd_erased
```

→ 존재하면: 해당 Actor 반환, 이후 관계(CREATED/WROTE/AUTHORED) 생성만 수행. 단, 이번 이벤트의 이름이 `alias_pd_name`과 다르면 `update_alias_name`으로 alias의 `pd_name`·`pd_normalized_name`을 갱신하고 표시 이름을 재계산한다 — 표시 이름이 첫 수집 값에 영구히 고정되는 것을 막는다.

- `pd_erased == "closed"`(계정 폐쇄로 삭제)면 갱신하지 않는다 — 삭제된 이름이 자동 경로로 되살아나는 것을 막는 유일한 방어선이다.
- `pd_erased == "access_lost"`(재조회 불가로 삭제)는 갱신 대상이다 — 갱신 시 `pd_erased`가 null로 돌아가는 재수집 복구 경로다.

→ 없으면: veto 집합 조회 후 Step 1으로

### Step 1 — Email 정확 매칭

pipeline-worker가 가져온 email이 기존 Actor의 어느 alias `pd_email`과든 일치하는지 확인. 여러 Actor가 걸리면 첫 1명만 본다.

```cypher
MATCH (al:ActorAlias {project_id: $project_id, pd_email: $email})-[:ALIAS_OF]->(a:Actor)
WITH a LIMIT 1
MATCH (other:ActorAlias)-[:ALIAS_OF]->(a)
RETURN a.uuid AS uuid, a.name AS name, a.aliases AS aliases,
       [x IN collect(DISTINCT other.pd_email) WHERE x IS NOT NULL] AS emails
```

→ 매칭 시: `merge_actor`로 해당 Actor의 aliases에 `$source_id`를 추가하고, 새 ActorAlias 노드를 MERGE해 이 계정의 개인정보(`pd_name`·`pd_normalized_name`·`pd_email`)를 저장한 뒤 표시 이름을 재계산한다.
→ 매칭 Actor가 veto 집합에 있으면: 병합하지 않고 Step 2로.
→ 없으면: Step 2로

### Step 2 — Name 정규화 + Email 교차 판단

이름 정규화 후 `pd_normalized_name`이 일치하는 alias의 Actor를 후보로 찾고(veto 집합 제외), email 신호를 보조 가중치로 활용한다. 각 후보의 emails는 그 Actor의 전체 alias에서 모은 `pd_email` 목록이다.

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

→ `same_person=true` AND `confidence ≥ 0.9`: `merge_actor`로 MERGE — 판단 결과는 alias 연결(`ALIAS_OF`)로만 남는다  
→ 미달: 다음 후보로, 모두 미달이면 Step 4 (신규 노드 생성)

> **닭-달걀 문제**: 기존 Actor에 활동 내용이 없으면 이름·이메일만으로 판단하며 confidence가 낮게 나오는 것이 정상이다. 이 경우 각각 별도 Actor 노드로 생성된다.

LLM 호출은 **"처음 보는 actor"에게만** 발생한다. 이후 같은 actor.id는 Step 0에서 종료.

### Step 4 — 신규 Actor 노드 생성

신뢰도 있는 매칭 없음 → 새 Actor 노드 생성. ActorAlias를 MERGE하고 alias가 아직 어떤 Actor에도 안 붙어 있을 때만 Actor를 만든다 — `(project_id, source_id)` 유니크 제약이 동시 MERGE를 직렬화하므로, 같은 alias로 동시에 들어온 두 이벤트 중 하나만 Actor를 만들고 둘 다 같은 Actor를 돌려받는다 (동시 수집의 중복 Actor 생성 race 방지).

```cypher
MERGE (al:ActorAlias {project_id: $project_id, source_id: $source_id})
FOREACH (_ IN CASE WHEN NOT EXISTS { (al)-[:ALIAS_OF]->(:Actor) } THEN [1] ELSE [] END |
    CREATE (a:Actor {
        uuid: $uuid,                 // 신규 생성 UUID
        project_id: $project_id,
        name: $display_name,         // derive_display_name — 첫 alias 하나로 유도
        aliases: [$source_id]        // "GITHUB:john-doe"
    })
    MERGE (al)-[:ALIAS_OF]->(a)
)
SET al.pd_name = $name,
    al.pd_normalized_name = $normalized,   // normalize_name($name)
    al.pd_email = $email,                  // 없으면 null
    al.pd_updated_at = datetime(),
    al.pd_erased = null,
    al.source = $source
```
