---
name: pipeline-inspect
description: 로컬 Docker 스택의 데이터 파이프라인 상태를 진단한다(읽기 전용). 사용자가 "수집이 안돼", "각 노드별 몇 개 수집됐어", "수집 현황 확인", "큐에 쌓였는지/처리 안 된 것 확인", "checkpoint 확인", "neo4j 데이터/노드 확인", "임베딩값 확인", "REFERENCE 엣지 생성됐어?", "integration 저장됐는지 DB 확인", "docker 로그 보는 명령어", "왜 다 안 수집됐지" 등 수집·큐·그래프·임베딩 상태를 점검하거나 컨테이너 로그를 보려 할 때 사용한다. 컨테이너 이름·포트·점검 쿼리를 매번 다시 찾지 않도록 고정 레퍼런스와 진단 흐름을 제공한다.
---

# Pipeline Inspect 스킬

`infra/docker`로 띄운 로컬 스택(postgres·neo4j·rabbitmq·ai-engine·pipeline-worker)의
**데이터 파이프라인 상태를 읽기 전용으로 진단**한다. 수집이 덜 됐거나, 큐가 적체됐거나,
그래프/임베딩/시맨틱 엣지가 안 생긴 원인을 찾을 때 쓴다.

> **원칙: 기본은 읽기 전용.** SELECT / MATCH RETURN / list_queues 등 조회만 한다.
> `DELETE`·`UPDATE`·체크포인트 리셋·`docker restart` 같은 상태 변경은 **실행 전 사용자에게 확인**한다.

## 고정 레퍼런스 (컨테이너·포트·인증)

| 대상 | 컨테이너명 | 호스트 포트 | 접속 |
|------|-----------|------------|------|
| PostgreSQL | `history-tracker-postgres` | 5432 | user/db 모두 `history_tracker` |
| Neo4j | `history-graph-neo4j` | 7474(브라우저) / 7687(bolt) | `neo4j` / `password1234` |
| RabbitMQ | `history-graph-rabbitmq` | 15672(관리 UI) | — |
| ai-engine | `history-graph-ai-engine` | 8000 | — |
| pipeline-worker | `history-tracker-pipeline-worker` | 8081 | — |
| backend | `history-tracker-backend` | 8080 | — |
| web | `history-tracker-web` | 5173 | — |

- **Neo4j 비밀번호**: 기본값 `password1234`로 시도한다. `.env` 파일은 읽지 않는다 — 인증 실패가 나면 비밀번호를 바꾼 것이므로 사용자에게 직접 물어본다.
- Neo4j 브라우저 빈 화면이면 `http://localhost:7474` (7687은 드라이버 전용이라 브라우저로 열면 빈 화면이 정상).
- 큐/익스체인지: 큐 `history.events`, 익스체인지 `history.exchange`, 라우팅 `event.#`.

## 0단계: 프로젝트 UUID 확보

대부분의 그래프/체크포인트 조회는 `project_id`로 스코프해야 한다. 먼저 UUID를 얻는다.

```bash
docker exec history-tracker-postgres psql -U history_tracker -d history_tracker -c \
  "SELECT id, title FROM projects ORDER BY created_at DESC;"
```

사용자가 어떤 프로젝트인지 모르면 목록을 보여주고 고르게 한다. 이하 명령의 `<PID>`에 이 UUID를 넣는다.

## 진단 메뉴 (증상별)

### A. 수집 현황 — 노드 종류별 개수

"각 노드별 몇 개 수집됐어 / 왜 다 안 됐지"에 답한다. 모든 도메인 노드는 `project_id` 속성을 가진다.

```bash
docker exec history-graph-neo4j cypher-shell -u neo4j -p password1234 \
  "MATCH (n) WHERE n.project_id = '<PID>' RETURN labels(n) AS label, count(*) AS cnt ORDER BY cnt DESC;"
```

노드 라벨: `Actor` · `Issue` · `Communication` · `PullRequest` · `ChangeSet` · `File`.
(비밀번호는 기본값 `password1234`. 인증 실패 시에만 사용자에게 물어 교체한다 — `.env`는 읽지 않는다.)

### B. 큐 적체 — RabbitMQ에 처리 안 된 메시지

"큐에 쌓였어 / 아직 처리 안 된 것"에 답한다. `messages_ready`가 소비 대기분이다.

```bash
docker exec history-graph-rabbitmq rabbitmqctl list_queues name messages messages_ready messages_unacknowledged
```

- `history.events`의 `messages_ready`가 계속 줄지 않으면 ai-engine consumer가 멈췄거나 직렬 처리 적체.
- consumer는 `prefetch_count=1` 직렬 소비라, 초기 대량 수집 중에는 자연히 쌓였다 줄어든다.

### C. Checkpoint — 증분 수집 커서

재수집이 안 되거나 같은 데이터가 반복 수집될 때 커서를 본다.

```bash
docker exec history-tracker-postgres psql -U history_tracker -d history_tracker -c \
  "SELECT provider, cursor_key, cursor_value, updated_at FROM checkpoints WHERE project_id = '<PID>' ORDER BY updated_at DESC;"
```

컬럼: `(project_id, provider, cursor_key)` 복합 PK, `cursor_value`는 TIMESTAMPTZ, `provider`는 `github|jira|slack`.
재수집을 강제하려면 해당 행 삭제/과거로 UPDATE가 필요한데 **이는 쓰기라 사용자 확인 후** 실행한다.

### D. Integration 저장 확인

Slack/Jira 키나 GitHub 연동이 DB에 들어갔는지 본다.

```bash
docker exec history-tracker-postgres psql -U history_tracker -d history_tracker -c \
  "SELECT id, provider, status FROM integrations WHERE project_id = '<PID>';"
# 컬럼이 헷갈리면 스키마부터: \d integrations
docker exec history-tracker-postgres psql -U history_tracker -d history_tracker -c "\d integrations"
```

> 컬럼명이 확실치 않으면 추측하지 말고 먼저 `\d <table>`로 스키마를 확인한 뒤 SELECT한다.

### E. 임베딩 / 시맨틱 엣지 (REFERENCE·TRIGGERED_BY)

"임베딩값 확인 / REFERENCE 엣지가 0개인 이유" 진단. 임베딩 저장 위치가 노드/관계로 나뉜다.

- `Communication.embedding` — 슬랙 메시지 임베딩
- `Issue.embedding` — 이슈 임베딩
- `(:ChangeSet)-[:MODIFIED]->(:File)` 관계의 `r.embedding` — diff 요약 임베딩
- 시맨틱 엣지: `(ChangeSet)-[:REFERENCE]->(Communication)` (= MODIFIED.embedding ↔ Communication.embedding),
  `(ChangeSet)-[:TRIGGERED_BY]->(Issue)` (= MODIFIED.embedding ↔ Issue.embedding)

```bash
# 임베딩 보유 수
docker exec history-graph-neo4j cypher-shell -u neo4j -p password1234 \
  "MATCH (c:Communication) WHERE c.project_id='<PID>' RETURN count(c.embedding) AS comm_emb;
   MATCH (:ChangeSet {project_id:'<PID>'})-[r:MODIFIED]->(:File) RETURN count(r.embedding) AS modified_emb;"

# 시맨틱 엣지 생성 수
docker exec history-graph-neo4j cypher-shell -u neo4j -p password1234 \
  "MATCH (:ChangeSet {project_id:'<PID>'})-[r:REFERENCE]->(:Communication) RETURN count(r) AS reference_cnt;
   MATCH (:ChangeSet {project_id:'<PID>'})-[t:TRIGGERED_BY]->(:Issue) RETURN count(t) AS triggered_cnt;"
```

엣지가 0이면: ① 양쪽 임베딩이 둘 다 있어야 생성된다(한쪽이라도 비면 0), ② Layer 4 빌드(유휴 디바운스 자동 또는 '그래프 재구축' 수동 트리거)가 아직 안 돌았을 수 있다.
관련 수동 트리거는 ai-engine `routers/admin.py`(REFERENCE 빌드, Communication 임베딩 보정)와 backend `POST /api/v1/projects/{id}/graph/build`.

### F. 컨테이너 로그

```bash
cd infra/docker
./dev.sh logs -f ai-engine          # consumer/그래프 빌드 로그
./dev.sh logs -f pipeline-worker     # 수집/정규화/발행 로그
./dev.sh ps                          # 컨테이너 상태
```

`./dev.sh`는 `docker compose --profile app` 래퍼다. 서비스명은 compose 기준(`ai-engine`, `pipeline-worker`, `backend`, `neo4j`, `rabbitmq`, `postgres`, `web-dashboard`)이며 위 컨테이너명과 다르다.

## 증상 → 점검 매핑

| 증상 | 먼저 볼 곳 | 그다음 |
|------|-----------|--------|
| "특정 노드가 일부만 수집됨" | A(노드 카운트) | F(pipeline-worker 로그) → C(checkpoint) |
| "수집을 눌렀는데 그래프에 안 보임" | B(큐 적체) | F(ai-engine 로그) → A |
| "같은 데이터가 두 번 수집됨" | C(checkpoint) | F(pipeline-worker 로그) |
| "REFERENCE/TRIGGERED_BY 엣지 0개" | E(임베딩 보유 수) | F(ai-engine 빌드 로그) |
| "슬랙/지라 키 저장 여부" | D(integrations) | — |
| "neo4j 브라우저가 빈 화면" | 7474로 접속(7687 아님) | 레퍼런스 표 |

## 진단 리포트 출력

점검 후에는 raw 출력을 그대로 던지지 말고 요약한다.

- **확인된 상태**: 각 점검 항목의 핵심 수치(노드 카운트, 큐 깊이, 엣지 수 등)
- **이상 징후**: 기대와 다른 지점 (예: "Communication 임베딩 401 / REFERENCE 0 → 매칭 임계 미달 또는 빌드 미실행")
- **추정 원인 → 다음 행동**: 무엇을 더 보거나 어떤 트리거를 돌려야 하는지
- 상태 변경(체크포인트 삭제·재수집 트리거 등)이 필요하면 **실행 전에 사용자에게 제안하고 확인**받는다.
