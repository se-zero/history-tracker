# Actor 수동 병합·분리 설계

자동 동일인 판단(`docs/actor-node-design.md`)의 오류를 운영자가 교정하는 기능.
핵심 설계 원칙 두 가지:

1. **수동 결정은 영속화되어 재수집(자동 판단)을 이긴다.** 병합은 ActorAlias 재연결로
   Step 0에서 자연히 유지되고, 분리는 `distinct` 결정이 Step 1/3의 자동 병합을 거부(veto)한다.
2. **병합은 복원 가능해야 한다.** 개인정보는 Actor가 아니라 ActorAlias에 있고 병합으로
   삭제되는 게 아니라 `ALIAS_OF`만 옮겨가므로, 스냅샷 없이 결정 노드의 alias 목록과 이동
   엣지의 `merged_from` 표식만으로 정확한 되돌리기(unmerge)를 지원한다.

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
  "aliases_a": ["GITHUB:junsu"],      // same: canonical의 병합 전 aliases / distinct: 한쪽
  "aliases_b": ["JIRA:557058:abc"],   // same: 합쳐져 삭제된 쪽 aliases / distinct: 다른쪽
  "canonical_uuid": "...",             // same 전용: 살아남은 Actor uuid
  "merged_uuid": "...",                // same 전용: 삭제된 Actor uuid (unmerge 복원 대상)
  "note": "운영자 메모",
  "decided_at": "datetime"
}
```

- `kind=same` — 수동 병합의 기록이자 unmerge의 복원 데이터. **스냅샷은 없다** — 개인정보는
  ActorAlias에 있고 병합으로 삭제된 적이 없으므로, `merged_uuid`로 Actor를 재생성하고
  `aliases_b`의 `ALIAS_OF`를 되돌리면 alias에서 다시 읽어 복원된다.
- `kind=distinct` — "이 두 신원은 다른 사람" 선언. resolver가 자동 병합 전에 조회해
  해당 Actor로의 병합을 거부한다. unmerge/split 시 자동 생성되어 재병합을 막는다.
- `ActorDecision`은 `project_id`를 가지므로 프로젝트 삭제 cascade(`delete_project_graph`)에서
  함께 삭제된다 — 전체 삭제는 사용자 데이터 완전 제거가 우선이다.
- `GET /actors/decisions` 응답의 `aliases_a`/`aliases_b`는 저장된 source_id 그대로가 아니라
  조회 시점에 현재 ActorAlias에서 `{source, name, erased}`로 변환해 반환한다 — 결정 노드
  자체에는 이름을 저장하지 않아 개인정보 사본이 여러 곳에 남지 않는다.

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

`POST /actors/merge` `{project_id, uuid_a, uuid_b, note}` — 두 노드를 같은 사람으로 합친다.
표시 이름은 입력받지 않는다 — 유도 규칙(`derive_display_name`)이 다시 계산하며, 운영자가 직접
정하고 싶으면 `/actors/rename`을 쓴다. 어느 노드가 살아남는지(canonical)는 활동 엣지 수로
자동 결정된다 — outgoing `AUTHORED`/`CREATED`/`WROTE` + incoming `ASSIGNED_TO` 합을 세어 많은
쪽을 canonical로 살린다(삭제되는 쪽의 엣지만 옮기면 되므로 항상 최소 작업량). 동수면 uuid
사전순 작은 쪽 — 인자 순서(`uuid_a`/`uuid_b`)를 바꿔도 항상 같은 결과를 낸다. 단일 트랜잭션으로:

1. 두 Actor 로드·검증 (같은 프로젝트, 서로 다른 노드).
2. 활동 엣지 수(outgoing `AUTHORED`/`CREATED`/`WROTE` + incoming `ASSIGNED_TO`)를 세어
   canonical/merged 결정.
3. 권한 엣지 이동 — merged → canonical. `MERGE ... ON CREATE SET r.merged_from = <merged_uuid>`로
   이동 엣지에만 표식을 남긴다(canonical이 원래 갖고 있던 엣지는 표식 없음 → unmerge가 이동분만
   되돌림).
4. ActorAlias `ALIAS_OF` 재연결 → 이후 재수집 이벤트는 Step 0에서 canonical로 귀속.
5. canonical의 `aliases` 합집합 갱신, `ActorDecision(kind=same)` 생성 — `aliases_a`(canonical의
   병합 전 aliases)·`aliases_b`(merged의 aliases)·`canonical_uuid`·`merged_uuid` 기록. merged
   노드 `DETACH DELETE`.
6. canonical 표시 이름 재계산(`recompute_display_name`).

## 병합 취소 (unmerge)

`POST /actors/unmerge` `{project_id, decision_id}` — same 결정을 스냅샷 없이 alias로 복원한다.
개인정보는 alias에 있고 병합으로 삭제된 적 없이 `ALIAS_OF` 화살표만 옮겨갔으므로, 되돌린 뒤
alias에서 다시 읽으면 정확히 복원된다.

1. 결정 노드에서 `canonical_uuid`·`merged_uuid`·`aliases_b` 조회. `merged_uuid`가 없으면
   (구버전 스냅샷 결정) 즉시 거부한다 — 아래 "한계/가드" 참고.
2. canonical에 아직 `ALIAS_OF`로 붙어 있는 `aliases_b`만 복원 대상(movable)으로 좁힌다 — 병합
   후 분리(split)로 다른 Actor에 재배치된 alias는 되돌릴 대상이 없다.
3. `merged_uuid`로 Actor 재생성, movable alias의 `ALIAS_OF`를 그쪽으로 되돌린다.
4. `merged_from == merged_uuid` 표식이 붙은 권한 엣지만 원 Actor로 반환.
5. canonical의 aliases에서 movable만 제거.
6. same 결정 삭제 + **distinct 결정 자동 생성**(`aliases_a`=canonical 잔여, `aliases_b`=movable)
   — 다음 수집에서 자동 파이프라인이 같은 병합을 반복하지 않게 한다.
7. canonical·복원된 Actor 양쪽 표시 이름 재계산.

**한계/가드**: 병합 취소 전에 분리(split)로 alias가 재배치됐으면 canonical에 남은 alias만
되돌리고(부분 복원), 하나도 안 남았으면(movable 없음) 400으로 거부한다.

## 자동 병합 교정 분리 (split)

`POST /actors/split` `{project_id, actor_uuid, source_ids}` — 자동 파이프라인이 잘못 합친
Actor에서 alias 일부를 새 Actor로 떼어낸다 (수동 병합 이력이 없는 경우용). 표시 이름은
입력받지 않는다 — 유도 규칙이 alias 기준으로 재계산하며, 직접 정하고 싶으면 `/actors/rename`을
쓴다.

1. `source_ids ⊂ actor.aliases` 검증 (전부 떼어내는 것은 금지 — 최소 1개 잔류).
2. 새 Actor 생성, ActorAlias 재연결 — 떼어낸 alias가 자기 개인정보(이메일 포함)를 그대로
   갖고 가므로 "emails는 원 Actor에 남긴다"는 별도 처리가 필요 없다.
3. **소스 단위 휴리스틱 재귀속**: 떼어낸 alias의 소스(GITHUB 등)가 원 Actor에 더 이상
   없으면, 그 소스의 이벤트 노드로 향한 권한 엣지를 새 Actor로 옮긴다. 같은 소스 alias가
   남아 있으면 어느 신원의 활동인지 판별 불가 → 옮기지 않는다(보수적).
4. distinct 결정 자동 생성(재병합 방지).
5. 새 Actor·원 Actor 양쪽 표시 이름 재계산.

## 이름 변경 (rename)

`POST /actors/rename` `{project_id, actor_uuid, name}` — Actor 표시 이름을 운영자가 정정한다.

- `name`은 trim 후 빈 값이면 거부한다.
- `Actor.name`·`Actor.manual_name`(=true)·`Actor.name_updated_at` 3필드만 갱신한다. 검색·Step 2
  후보 조회는 ActorAlias.pd_normalized_name을 타므로 이 이름은 표시 전용이며 매칭 키로 쓰이지
  않는다 — 운영자 라벨을 매칭 키로 함께 갱신하면 오매칭·검색 축소를 만든다. ActorAlias는 건드리지
  않는다.
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
- `graph/actor_store.py` — `lookup_vetoes` Neo4j 구현(`make_neo4j_actor_store`에 바인딩), 표시
  이름 유도·재계산(`derive_display_name`/`recompute_display_name` — 병합·복원·분리가 공유)
- `graph/actor_resolver.py` — veto 통합 (Step 1 차단, Step 2 후보 제외)
- `graph/schema.py` — `ActorDecision.decision_id` 유니크 제약
