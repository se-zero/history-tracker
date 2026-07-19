# Actor 수동 병합·분리 설계

자동 동일인 판단(`docs/actor-node-design.md`)의 오류를 운영자가 교정하는 기능.
핵심 설계 원칙 두 가지:

1. **수동 결정은 영속화되어 재수집(자동 판단)을 이긴다.** 병합은 ActorAlias 재연결로
   Step 0에서 자연히 유지되고, 분리는 `distinct` 결정이 Step 1/3의 자동 병합을 거부(veto)한다.
2. **병합은 복원 가능해야 한다.** 합쳐진(삭제되는) Actor의 스냅샷과, 이동한 엣지의 `merged_from`
   표식을 남겨 정확한 되돌리기(unmerge)를 지원한다.

---

## 결정 노드 (ActorDecision)

수동 결정은 Neo4j에 `ActorDecision` 노드로 영속화한다. **uuid가 아니라 alias 집합을
기준으로 기록**한다 — Actor 노드는 재수집·병합·분리로 생멸하지만 alias(소스 신원)는
안정적이기 때문이다.

```json
{
  "decision_id": "uuid",
  "project_id": "...",
  "kind": "same | distinct",
  "aliases_a": ["GITHUB:junsu"],      // same: 통합 대상(유지) 쪽 / distinct: 한쪽
  "aliases_b": ["JIRA:557058:abc"],   // same: 합쳐져 삭제된 쪽 / distinct: 다른쪽
  "emails_a": ["..."],                 // same 전용: 병합 시점 canonical emails (unmerge 복원용)
  "canonical_uuid": "...",             // same 전용
  "merged_snapshot": "{...JSON}",      // same 전용: 합쳐져 삭제된 Actor 원본 속성 전체 (unmerge 복원용)
  "note": "운영자 메모",
  "decided_at": "datetime"
}
```

- `kind=same` — 수동 병합의 기록이자 unmerge의 복원 데이터.
- `kind=distinct` — "이 두 신원은 다른 사람" 선언. resolver가 자동 병합 전에 조회해
  해당 Actor로의 병합을 거부한다. unmerge/split 시 자동 생성되어 재병합을 막는다.
- `ActorDecision`은 `project_id`를 가지므로 프로젝트 삭제 cascade(`delete_project_graph`)에서
  함께 삭제된다 — 전체 삭제는 사용자 데이터 완전 제거가 우선이다.

## 자동 파이프라인 통합 (veto)

`actor_resolver.resolve_actor`에 거부권을 추가한다.

```
Step 0: alias 조회             → 변경 없음 (병합 유지는 ALIAS_OF 재연결로 충족)
(신규)  veto 조회              → distinct 결정에서 이 source_id와 반대편 alias를 가진 Actor uuid 집합
Step 1: email 정확 매칭        → 매칭 Actor가 veto 집합에 있으면 병합하지 않고 Step 2로
Step 2: 이름 스코어링          → veto 집합에 있는 후보 제외
Step 3: LLM 판단               → (Step 2에서 걸러졌으므로 변경 없음)
Step 4: 신규 생성              → 변경 없음
```

`ActorStore`에 `lookup_vetoes(source_id) -> [actor_uuid]` 콜백을 추가한다(기본 None —
기존 mock/호출부 호환). Neo4j 구현은 `actor_store.py`의 distinct 결정 조회.

## 병합 (merge)

`POST /actors/merge` `{project_id, source_uuid, target_uuid, name?, note?}` — 두 노드를
같은 사람으로 합친다. 사용자는 합친 뒤의 표시 이름(`name`)만 입력하고, source/target은
어느 노드가 유지되는지(target)를 정하는 내부 구분일 뿐이다. 단일 트랜잭션으로:

1. 두 Actor 로드·검증 (같은 프로젝트, 서로 다른 노드).
2. 삭제될 노드(source) 속성 스냅샷 → 결정 노드에 JSON으로 보관.
3. 권한 엣지 이동 — `AUTHORED`/`CREATED`/`WROTE`(outgoing), `ASSIGNED_TO`(incoming).
   `MERGE ... ON CREATE SET r.merged_from = <source_uuid>`로 이동 엣지에만 표식을 남긴다
   (target이 원래 갖고 있던 엣지는 표식 없음 → unmerge가 원상만 되돌림).
4. ActorAlias `ALIAS_OF` 재연결 → 이후 재수집 이벤트는 Step 0에서 통합 노드로 귀속.
5. target의 `aliases`/`emails` 합집합 갱신, `confidence = 1.0` (수동 확정).
   `name`이 있으면 `name`·`normalized_name`도 갱신(`manual_name=true`로 자동 갱신 방지).
6. `ActorDecision(kind=same)` 생성, source 노드 `DETACH DELETE`.

## 병합 취소 (unmerge)

`POST /actors/unmerge` `{project_id, decision_id}` — same 결정을 정확히 되돌린다.

1. 스냅샷으로 합쳐져 삭제됐던 Actor 재생성(원 uuid).
2. 해당 alias들의 `ALIAS_OF`를 되돌리고, `merged_from` 표식 엣지를 원 Actor로 반환.
3. canonical의 aliases/emails에서 합쳐졌던 분 제거(`emails_a` 기준으로 원래 보유분은 보존).
4. same 결정 삭제 + **distinct 결정 자동 생성** — 다음 수집에서 자동 파이프라인이
   같은 병합을 반복하지 않게 한다.

한계: 병합 이후 새로 수집된 이벤트는 표식이 없어 canonical에 남는다(스냅샷 시점 복원).

## 자동 병합 교정 분리 (split)

`POST /actors/split` `{project_id, actor_uuid, source_ids, name?}` — 자동 파이프라인이
잘못 합친 Actor에서 alias 일부를 새 Actor로 떼어낸다 (수동 병합 이력이 없는 경우용).

1. `source_ids ⊂ actor.aliases` 검증 (전부 떼어내는 것은 금지 — 최소 1개 잔류).
2. 새 Actor 생성(이름은 `name` 또는 alias에서 유도), ActorAlias 재연결.
3. **소스 단위 휴리스틱 재귀속**: 떼어낸 alias의 소스(GITHUB 등)가 원 Actor에 더 이상
   없으면, 그 소스의 이벤트 노드로 향한 권한 엣지를 새 Actor로 옮긴다. 같은 소스 alias가
   남아 있으면 어느 신원의 활동인지 판별 불가 → 옮기지 않는다(보수적).
4. distinct 결정 자동 생성(재병합 방지). emails는 신원 귀속 판별이 불가해 원 Actor에 남긴다.

## 이름 변경 (rename)

`POST /actors/rename` `{project_id, actor_uuid, name}` — Actor 표시 이름을 운영자가 정정한다.

- `name`은 trim 후 빈 값이면 거부한다.
- `Actor.name`과 `Actor.normalized_name`을 함께 갱신해 UI 표시와 이름 기반 후보 검색을 맞춘다.
- 병합/분리 결정과 달리 동일인 판단을 바꾸는 선언이 아니므로 `ActorDecision`은 만들지 않는다.

## API

| 라우트 | 역할 |
|---|---|
| `GET /graph/actors?project_id=` | Actor 목록 + 활동 수 (관리 UI용, graph 라우터) |
| `POST /actors/merge` | 수동 병합 |
| `POST /actors/rename` | Actor 표시 이름 변경 |
| `POST /actors/unmerge` | 수동 병합 취소 (same 결정 복원) |
| `POST /actors/split` | 자동 병합 교정 분리 |
| `GET /actors/decisions?project_id=` | 결정 이력 (감사·unmerge 대상 조회) |
| `DELETE /actors/decisions/{decision_id}` | distinct 결정 철회 (자동 재병합 다시 허용) |

backend는 `/api/v1/projects/{projectId}/actors*`로 인가 후 프록시하고,
web-dashboard는 Sources 화면의 액터 관리 카드에서 호출한다.

## 코드 위치

- `graph/actor_admin.py` — 병합·복원·분리·이름 변경·목록·결정 조회 (운영 쓰기 경로)
- `graph/actor_store.py` — `lookup_vetoes` Neo4j 구현 (`make_neo4j_actor_store`에 바인딩)
- `graph/actor_resolver.py` — veto 통합 (Step 1 차단, Step 2 후보 제외)
- `graph/schema.py` — `ActorDecision.decision_id` 유니크 제약
