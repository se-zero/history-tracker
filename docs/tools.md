# Query Tools — 검증 시나리오 기반 도구 설계

LLM이 Neo4j 지식 그래프를 조회하기 위한 Tool Calling 도구 정의.
파라미터는 LLM이 자연어 질문에서 추출해 전달한다.

---

## 도구 목록

| # | 도구 | 역할 | 커버 시나리오 |
|---|------|------|--------------|
| 1 | `get_issue_context` | Jira 이슈 중심 탐색 | 시나리오 5 (크로스 소스 연결) |
| 2 | `get_changeset_context` | 커밋 중심 탐색 | 시나리오 2 (코드 변경 이유) |
| 3 | `find_expert` | 파일/디렉토리 전문가 식별 | 시나리오 12 (전문가 식별) |
| 4 | `get_timeline` | 이슈별 이벤트 순서 조회 | 시나리오 1 (타임라인 순서 보존) |
| 5 | `search_by_keyword` | 자연어/키워드 시맨틱 검색 | 시나리오 2, 5 |
| 6 | `get_actor_activity` | 사람 중심 활동 조회 | 시나리오 12 |
| 7 | `get_file_history` | 파일 변경 이력 조회 | 시나리오 2 |
| 8 | `check_missing_context` | 연결 부재 커밋 탐지 | 시나리오 4, 11 |
| 9 | `inspect_actor` | Actor 통합 상태 확인 | 시나리오 10 (Identity Resolution) |
| 10 | `get_conflict_context` | 컨텍스트 충돌 다중 관점 반환 | 시나리오 7 (컨텍스트 충돌) |
| 11 | `get_recent_activity` | 시간 범위 기반 활동 조회 | 시나리오 9 (범위 모호한 질문) |
| 12 | `get_pr_context` | PR 번호 중심 탐색 | 시나리오 5, 6 |
| 13 | `get_thread_context` | Slack 스레드 전체 조회 | 시나리오 5 |

---

## 실제 Neo4j 노드 속성 (builder.py 기준)

| 노드/엣지 | 주요 속성 |
|---------|---------|
| `Actor` | `uuid`, `name`, `normalized_name`, `aliases[]`, `emails[]`, `confidence` |
| `Issue` | `jira_key`, `title`, `body`, `status`, `issue_type`, `priority`, `assignee`, `occurredAt`, `createdAt`, `embedding` |
| `ChangeSet` | `hash`, `message`, `occurredAt`, `source` |
| `PullRequest` | `pr_number`(int), `title`, `body`, `state`, `base_branch`, `url`, `occurredAt`, `createdAt` |
| `Communication` | `url`(PK), `body`, `channel`, `conversation_id`, `occurredAt`, `createdAt`, `source`, `embedding` |
| `File` | `path` |
| `MODIFIED` 엣지 | `diffSummary`, `embedding` |
| `TRIGGERED_BY` 엣지 | `confidence`(시맨틱 생성 시) |
| `DISCUSSED_IN` 엣지 | `confidence`(시맨틱 생성 시) |
| `REFERENCE` 엣지 | `confidence` |

---

## 도구 상세

---

### 1. `get_issue_context`

**역할**: Jira 이슈를 기준으로 관련 커밋, PR, Slack/GitHub 논의를 한 번에 조회.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `jira_key` | string | Jira 티켓 키 (예: `HT-12`) |

**Cypher**

```cypher
MATCH (i:Issue {jira_key: $jira_key})
OPTIONAL MATCH (creator:Actor)-[:CREATED]->(i)
OPTIONAL MATCH (assignee:Actor)<-[:ASSIGNED_TO]-(i)
OPTIONAL MATCH (cs:ChangeSet)-[tb:TRIGGERED_BY]->(i)
OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
OPTIONAL MATCH (i)-[disc:DISCUSSED_IN]->(c:Communication)
OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
WITH i, creator, assignee,
     collect(DISTINCT {
         hash: cs.hash, message: cs.message,
         occurredAt: toString(cs.occurredAt),
         author: cs_author.name,
         confidence: tb.confidence
     }) AS changesets,
     collect(DISTINCT {
         pr_number: pr.pr_number, title: pr.title, url: pr.url,
         occurredAt: toString(pr.occurredAt)
     }) AS pull_requests,
     collect(DISTINCT {
         body: c.body, channel: c.channel, source: c.source,
         occurredAt: toString(c.occurredAt),
         author: c_author.name,
         confidence: disc.confidence
     }) AS discussions
RETURN i, creator.name AS creator, assignee.name AS assignee,
       changesets, pull_requests, discussions
```

**반환값**: 이슈 메타데이터 + 커밋 목록 + PR 목록 + 논의 목록

---

### 2. `get_changeset_context`

**역할**: 커밋 hash로 "왜 이 코드가 바뀌었는지" 파악. diffSummary, 연결 이슈, Slack 논의, PR을 함께 반환.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `hash` | string | Git commit hash |

**Cypher**

```cypher
MATCH (cs:ChangeSet {hash: $hash})
MATCH (a:Actor)-[:AUTHORED]->(cs)
OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
RETURN cs.hash AS hash,
       cs.message AS commit_message,
       toString(cs.occurredAt) AS occurredAt,
       a.name AS author,
       collect(DISTINCT {
           jira_key: i.jira_key, title: i.title,
           body: i.body, status: i.status,
           confidence: tb.confidence
       }) AS issues,
       collect(DISTINCT {
           body: c.body, channel: c.channel, source: c.source,
           occurredAt: toString(c.occurredAt),
           author: c_author.name,
           confidence: ref.confidence
       }) AS communications,
       {pr_number: pr.pr_number, title: pr.title, url: pr.url} AS pull_request,
       collect(DISTINCT {path: f.path, diffSummary: m.diffSummary}) AS file_changes
```

**반환값**: 커밋 정보 + 연결 이슈(confidence 포함) + Slack/GitHub 논의 + PR + 파일별 diff 요약

---

### 3. `find_expert`

**역할**: 특정 파일 또는 디렉토리에 가장 많이 기여한 Actor를 식별. 최근 6개월 커밋에 2배 가중치를 적용해 현재 담당자를 우선한다.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `path_prefix` | string | 파일 경로 또는 디렉토리 접두어 (예: `src/auth/` 또는 `src/auth/token.py`) |

**Cypher**

```cypher
MATCH (a:Actor)-[:AUTHORED]->(cs:ChangeSet)-[:MODIFIED]->(f:File)
WHERE f.path STARTS WITH $path_prefix
WITH a, cs,
     CASE WHEN cs.occurredAt >= datetime() - duration('P180D')
          THEN 2 ELSE 1 END AS weight
WITH a,
     count(cs) AS commit_count,
     sum(weight) AS weighted_score,
     max(cs.occurredAt) AS last_commit
RETURN a.name AS author,
       a.uuid AS actor_uuid,
       commit_count,
       weighted_score,
       toString(last_commit) AS last_commit
ORDER BY weighted_score DESC
LIMIT 5
```

**반환값**: 가중치 점수 순 Actor 목록 (이름, 커밋 수, 최근 기여 시각)

> **주의**: `MODIFIES` 아님, `MODIFIED` 임.

---

### 4. `get_timeline`

**역할**: Jira 이슈 기준으로 Slack 논의 → Jira 생성 → 커밋 → PR 머지 순서를 UTC 기준 오름차순으로 반환.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `jira_key` | string | Jira 티켓 키 |

**Cypher**

```cypher
MATCH (i:Issue {jira_key: $jira_key})
OPTIONAL MATCH (cs:ChangeSet)-[:TRIGGERED_BY]->(i)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
OPTIONAL MATCH (i)-[:DISCUSSED_IN]->(c:Communication)
WITH
  collect(DISTINCT {
      type: 'Issue', occurredAt: toString(i.occurredAt),
      data: {jira_key: i.jira_key, title: i.title, status: i.status}
  }) +
  collect(DISTINCT {
      type: 'ChangeSet', occurredAt: toString(cs.occurredAt),
      data: {hash: cs.hash, message: cs.message}
  }) +
  collect(DISTINCT {
      type: 'PullRequest', occurredAt: toString(pr.occurredAt),
      data: {pr_number: pr.pr_number, title: pr.title, url: pr.url}
  }) +
  collect(DISTINCT {
      type: 'Communication', occurredAt: toString(c.occurredAt),
      data: {body: c.body, channel: c.channel, source: c.source}
  }) AS all_events
UNWIND all_events AS event
WHERE event.occurredAt IS NOT NULL
RETURN event.type AS type, event.occurredAt AS occurredAt, event.data AS data
ORDER BY occurredAt ASC
```

**반환값**: `{type, occurredAt, data}` 배열, UTC 오름차순 정렬

> **수정 이유**: 기존 쿼리의 `ORDER BY coalesce(...)` 는 OPTIONAL MATCH가 생성하는 여러 행 사이에서 올바르게 동작하지 않는다. UNWIND 후 정렬해야 타입별 단일 행이 혼재하지 않는다.

---

### 5. `search_by_keyword`

**역할**: 자연어 키워드를 임베딩해 의미적으로 유사한 Communication과 Issue를 탐색. 연결된 ChangeSet·이슈를 함께 반환해 진입점을 제공한다.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `embedding` | float[] | 쿼리 텍스트의 임베딩 벡터 (호출 전 `embed_text(keyword)`로 생성) |
| `top_k` | int | 각 인덱스에서 반환할 최대 후보 수 (기본 5) |
| `threshold` | float | 최소 코사인 유사도 (기본 0.30) |

**Cypher**

```cypher
// Communication 인덱스 검색
CALL db.index.vector.queryNodes('comm_embedding', $top_k, $embedding)
YIELD node AS c, score
WHERE score >= $threshold
OPTIONAL MATCH (cs:ChangeSet)-[:REFERENCE]->(c)
OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
RETURN 'Communication' AS type,
       left(c.body, 300) AS text,
       c.channel AS channel,
       c.source AS source,
       toString(c.occurredAt) AS occurredAt,
       score,
       collect(DISTINCT cs.hash) AS related_changesets,
       collect(DISTINCT i.jira_key) AS related_issues

UNION

// Issue 인덱스 검색
CALL db.index.vector.queryNodes('issue_embedding', $top_k, $embedding)
YIELD node AS i, score
WHERE score >= $threshold
OPTIONAL MATCH (cs:ChangeSet)-[:TRIGGERED_BY]->(i)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
RETURN 'Issue' AS type,
       (i.title + ': ' + coalesce(i.body, '')) AS text,
       null AS channel,
       'JIRA' AS source,
       toString(i.occurredAt) AS occurredAt,
       score,
       collect(DISTINCT cs.hash) AS related_changesets,
       collect(DISTINCT i.jira_key) AS related_issues
```

**Neo4j 인덱스 생성 (최초 1회)**

```cypher
CREATE VECTOR INDEX comm_embedding IF NOT EXISTS
FOR (c:Communication) ON (c.embedding)
OPTIONS { indexConfig: { `vector.dimensions`: 1536, `vector.similarity_function`: 'cosine' }};

CREATE VECTOR INDEX issue_embedding IF NOT EXISTS
FOR (i:Issue) ON (i.embedding)
OPTIONS { indexConfig: { `vector.dimensions`: 1536, `vector.similarity_function`: 'cosine' }};
```

**반환값**: score 내림차순 Communication + Issue 혼합 목록, 연결된 ChangeSet hash / Jira key 포함

> **수정 이유**: 기존 `diffSummary_embedding` 인덱스는 존재하지 않는다. `MODIFIED` 엣지 속성에 저장된 diffSummary 임베딩은 노드 인덱스 대상이 아니다. 노드 인덱스가 존재하는 `Communication.embedding`과 `Issue.embedding` 을 조회해야 한다.

---

### 6. `get_actor_activity`

**역할**: 이름·alias·email 중 하나로 Actor를 찾아 커밋, PR, Slack 메시지, Jira 이슈 활동을 반환.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `identifier` | string | 이름, alias, 또는 이메일 중 하나 (예: `john-dev`, `jkim@co.com`, `John Kim`) |
| `from` | string? | 조회 시작 시각 ISO-8601 (null이면 전체) |
| `to` | string? | 조회 종료 시각 ISO-8601 (null이면 현재) |
| `limit` | int | 항목당 최대 반환 수 (기본 20) |

**Cypher**

```cypher
MATCH (a:Actor)
WHERE a.name = $identifier
   OR $identifier IN a.aliases
   OR $identifier IN a.emails
WITH a

// 커밋 (최신순)
OPTIONAL MATCH (a)-[:AUTHORED]->(cs:ChangeSet)
WHERE $from IS NULL OR cs.occurredAt >= datetime($from)
WITH a, cs ORDER BY cs.occurredAt DESC
WITH a, collect(cs)[0..$limit] AS ordered_cs

// PR (최신순)
OPTIONAL MATCH (a)-[:AUTHORED]->(pr:PullRequest)
WHERE $from IS NULL OR pr.occurredAt >= datetime($from)
WITH a, ordered_cs, pr ORDER BY pr.occurredAt DESC
WITH a, ordered_cs, collect(pr)[0..$limit] AS ordered_pr

// 메시지
OPTIONAL MATCH (a)-[:WROTE]->(c:Communication)
WHERE $from IS NULL OR c.occurredAt >= datetime($from)
WITH a, ordered_cs, ordered_pr, c ORDER BY c.occurredAt DESC
WITH a, ordered_cs, ordered_pr, collect(c)[0..$limit] AS ordered_c

// Jira 생성 / 담당
OPTIONAL MATCH (a)-[:CREATED]->(i:Issue)
OPTIONAL MATCH (assigned:Issue)-[:ASSIGNED_TO]->(a)

RETURN a.name AS name,
       a.aliases AS aliases,
       a.emails AS emails,
       [x IN ordered_cs | {hash: x.hash, message: x.message, occurredAt: toString(x.occurredAt)}] AS changesets,
       [x IN ordered_pr | {pr_number: x.pr_number, title: x.title, occurredAt: toString(x.occurredAt)}] AS pull_requests,
       [x IN ordered_c  | {body: left(x.body, 200), channel: x.channel, occurredAt: toString(x.occurredAt)}] AS communications,
       collect(DISTINCT {jira_key: i.jira_key, title: i.title}) AS issues_created,
       collect(DISTINCT {jira_key: assigned.jira_key, title: assigned.title}) AS issues_assigned
```

> **수정 이유**: `{name: $name}` 단일 매칭은 alias로 통합된 Actor를 놓친다. `aliases`와 `emails` 배열 포함 검색 필요. `CREATED`(Jira 생성)와 `ASSIGNED_TO`(담당자) 관계도 추가.

---

### 7. `get_file_history`

**역할**: 특정 파일의 변경 이력을 시간 역순으로 반환. diffSummary, 연결 이슈, PR 포함.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `path` | string | 파일 경로 (예: `src/auth/token.py`) |
| `limit` | int | 최대 반환 커밋 수 (기본 20) |

**Cypher**

```cypher
MATCH (f:File {path: $path})<-[m:MODIFIED]-(cs:ChangeSet)
MATCH (a:Actor)-[:AUTHORED]->(cs)
OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
RETURN f.path AS file_path,
       cs.hash AS hash,
       cs.message AS message,
       toString(cs.occurredAt) AS occurredAt,
       a.name AS author,
       m.diffSummary AS diff_summary,
       i.jira_key AS jira_key,
       i.title AS issue_title,
       pr.pr_number AS pr_number,
       pr.url AS pr_url
ORDER BY cs.occurredAt DESC
LIMIT $limit
```

**반환값**: 커밋별 {hash, 메시지, 작성자, diff 요약, 연결 이슈, PR} 목록

> **수정 이유**: 기존 쿼리는 `MODIFIES` (오타) 사용 및 `diffSummary` 미반환.

---

### 8. `check_missing_context`

**역할**: 이슈와도, Slack/GitHub 논의와도 연결되지 않은 "고아 커밋"을 탐지. 시나리오 4(데이터 공백)와 11(연결 부재 감지) 검증에 사용.

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `from` | string? | 조회 시작 시각 ISO-8601 |
| `to` | string? | 조회 종료 시각 ISO-8601 |
| `limit` | int | 최대 반환 수 (기본 50) |

**Cypher**

```cypher
MATCH (cs:ChangeSet)
WHERE NOT (cs)-[:TRIGGERED_BY]->(:Issue)
  AND NOT (cs)-[:REFERENCE]->(:Communication)
  AND ($from IS NULL OR cs.occurredAt >= datetime($from))
  AND ($to IS NULL OR cs.occurredAt <= datetime($to))
MATCH (a:Actor)-[:AUTHORED]->(cs)
OPTIONAL MATCH (cs)-[:MODIFIED]->(f:File)
RETURN cs.hash AS hash,
       cs.message AS message,
       toString(cs.occurredAt) AS occurredAt,
       a.name AS author,
       collect(f.path) AS files
ORDER BY cs.occurredAt DESC
LIMIT $limit
```

**반환값**: 고아 커밋 목록 (hash, 메시지, 작성자, 변경 파일)

> **수정 이유**: 기존 쿼리는 LIMIT 없어 대량 반환. 날짜 필터와 작성자·파일 필드 추가.

---

### 9. `inspect_actor`

**역할**: Actor 통합 결과를 확인. `jkim@co.com`, `john-dev`, `John Kim`이 하나의 Actor 노드로 통합됐는지, 통합 confidence는 얼마인지 반환.

**커버 시나리오**: 시나리오 10 — Identity Resolution 정확도

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `identifier` | string | 이름, alias, 또는 이메일 중 하나 |

**Cypher**

```cypher
MATCH (a:Actor)
WHERE a.name = $identifier
   OR $identifier IN a.aliases
   OR $identifier IN a.emails
RETURN a.uuid AS uuid,
       a.name AS display_name,
       a.normalized_name AS normalized_name,
       a.aliases AS all_aliases,
       a.emails AS emails,
       a.confidence AS merge_confidence,
       count { (a)-[:AUTHORED]->(:ChangeSet) } AS commit_count,
       count { (a)-[:AUTHORED]->(:PullRequest) } AS pr_count,
       count { (a)-[:WROTE]->(:Communication) } AS message_count,
       count { (a)-[:CREATED]->(:Issue) } AS issue_created_count
```

**반환값**: Actor의 display name, 통합된 모든 alias/email, 통합 confidence, 활동 집계

**활용 예시**
```
사용자: "john-dev (GitHub)와 jkim@co.com (Jira)가 같은 사람으로 묶였어?"
→ inspect_actor("john-dev") 호출
→ aliases에 "JIRA:...", emails에 "jkim@co.com" 포함 여부 확인
```

---

### 10. `get_conflict_context`

**역할**: 하나의 커밋에 대해 Jira, Slack, PR이 각각 다른 맥락을 설명할 때 이를 나란히 반환. LLM이 다중 관점을 비교해 실제 이유를 추론하도록 한다.

**커버 시나리오**: 시나리오 7 — 컨텍스트 충돌 처리

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `hash` | string | Git commit hash |

**Cypher**

```cypher
MATCH (cs:ChangeSet {hash: $hash})
OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
OPTIONAL MATCH (pr:PullRequest)-[:CONTAINS]->(cs)
OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
RETURN cs.hash AS hash,
       cs.message AS commit_message,
       toString(cs.occurredAt) AS occurredAt,
       collect(DISTINCT {
           source: 'Jira',
           id: i.jira_key,
           text: i.title + '\n' + coalesce(i.body, ''),
           confidence: coalesce(tb.confidence, 1.0)
       }) AS jira_contexts,
       collect(DISTINCT {
           source: c.source,
           channel: c.channel,
           text: c.body,
           occurredAt: toString(c.occurredAt),
           confidence: ref.confidence
       }) AS comm_contexts,
       collect(DISTINCT {
           source: 'GitHub PR',
           id: toString(pr.pr_number),
           text: pr.title + '\n' + coalesce(pr.body, ''),
           confidence: 1.0
       }) AS pr_contexts,
       collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
```

**반환값**: 출처별(Jira / Slack·GitHub / PR) 컨텍스트를 confidence와 함께 분리 반환

**활용 예시**
```
사용자: "이 커밋 설명이 Jira랑 Slack이랑 달라, 어느 게 맞아?"
→ get_conflict_context(hash="abc123")
→ jira_contexts, comm_contexts, pr_contexts를 LLM에 전달
→ LLM이 "Jira는 성능 이슈로 설명하나 Slack에서는 보안 패치로 논의됨. 두 이유가 모두 유효할 수 있음" 답변
```

---

### 11. `get_recent_activity`

**역할**: "최근에 뭐 했어?", "이번 주 변경 사항은?" 처럼 범위가 모호한 질문에 사용. LLM이 `from`/`to`를 추론해 전달한다.

**커버 시나리오**: 시나리오 9 — 질문 범위 처리

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `from` | string | 시작 시각 ISO-8601 (LLM이 "최근 7일" 등을 계산해 전달) |
| `to` | string | 종료 시각 ISO-8601 (기본 현재 시각) |
| `limit` | int | 최대 반환 수 (기본 30) |

**Cypher**

```cypher
MATCH (n)
WHERE (n:ChangeSet OR n:PullRequest OR n:Communication OR n:Issue)
  AND n.occurredAt >= datetime($from)
  AND n.occurredAt <= datetime($to)
WITH n, labels(n)[0] AS node_type
OPTIONAL MATCH (a:Actor)-[:AUTHORED|WROTE|CREATED]->(n)
RETURN node_type AS type,
       toString(n.occurredAt) AS occurredAt,
       a.name AS actor,
       CASE node_type
         WHEN 'ChangeSet'     THEN n.hash
         WHEN 'PullRequest'   THEN toString(n.pr_number)
         WHEN 'Communication' THEN n.url
         WHEN 'Issue'         THEN n.jira_key
       END AS id,
       CASE node_type
         WHEN 'ChangeSet'     THEN n.message
         WHEN 'PullRequest'   THEN n.title
         WHEN 'Communication' THEN left(n.body, 200)
         WHEN 'Issue'         THEN n.title
       END AS summary
ORDER BY occurredAt DESC
LIMIT $limit
```

**반환값**: 기간 내 모든 노드 타입을 최신순으로 혼합 반환

---

### 12. `get_pr_context`

**역할**: PR 번호로 시작하는 탐색. PR에 포함된 커밋, 연결 이슈, Slack 논의, 파일 변경 요약을 반환.

**커버 시나리오**: 시나리오 5 (크로스 소스 연결), 시나리오 6 (증분 동기화 확인)

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `pr_number` | int | GitHub PR 번호 |

**Cypher**

```cypher
MATCH (pr:PullRequest {pr_number: $pr_number})
OPTIONAL MATCH (author:Actor)-[:AUTHORED]->(pr)
OPTIONAL MATCH (pr)-[:CONTAINS]->(cs:ChangeSet)
OPTIONAL MATCH (cs_author:Actor)-[:AUTHORED]->(cs)
OPTIONAL MATCH (cs)-[tb:TRIGGERED_BY]->(i:Issue)
OPTIONAL MATCH (cs)-[ref:REFERENCE]->(c:Communication)
OPTIONAL MATCH (c_author:Actor)-[:WROTE]->(c)
OPTIONAL MATCH (cs)-[m:MODIFIED]->(f:File)
RETURN pr.pr_number AS pr_number,
       pr.title AS title,
       pr.body AS body,
       toString(pr.occurredAt) AS merged_at,
       toString(pr.createdAt) AS created_at,
       pr.url AS url,
       author.name AS author,
       collect(DISTINCT {
           hash: cs.hash, message: cs.message,
           occurredAt: toString(cs.occurredAt),
           author: cs_author.name
       }) AS changesets,
       collect(DISTINCT {
           jira_key: i.jira_key, title: i.title,
           status: i.status, confidence: tb.confidence
       }) AS issues,
       collect(DISTINCT {
           body: c.body, channel: c.channel, source: c.source,
           occurredAt: toString(c.occurredAt),
           author: c_author.name, confidence: ref.confidence
       }) AS discussions,
       collect(DISTINCT {path: f.path, diff_summary: m.diffSummary}) AS file_changes
```

**반환값**: PR 메타데이터 + 포함 커밋 + 연결 이슈 + 논의 + 파일 변경 목록

---

### 13. `get_thread_context`

**역할**: Slack 스레드를 `conversation_id`로 완전히 조회. 방안 C(스레드 전파)로 생성된 DISCUSSED_IN 연결이 올바른지 검증하거나, 스레드 전체 맥락을 LLM에 제공할 때 사용.

**커버 시나리오**: 시나리오 5 (크로스 소스 연결), 스레드 전파 검증

**파라미터**

| 이름 | 타입 | 설명 |
|------|------|------|
| `conversation_id` | string | Slack 스레드 루트 메시지 ts 또는 GitHub Issue 번호 |

**Cypher**

```cypher
MATCH (c:Communication {conversation_id: $conversation_id})
OPTIONAL MATCH (a:Actor)-[:WROTE]->(c)
OPTIONAL MATCH (i:Issue)-[:DISCUSSED_IN]->(c)
RETURN c.body AS body,
       toString(c.occurredAt) AS occurredAt,
       c.source AS source,
       c.url AS url,
       a.name AS author,
       collect(DISTINCT {
           jira_key: i.jira_key, title: i.title
       }) AS related_issues
ORDER BY occurredAt ASC
```

**반환값**: 스레드 내 메시지를 시간순으로 정렬, 각 메시지에 연결된 Jira 이슈 포함

---

## Tool Calling 흐름 예시

### "결제 모듈 리팩토링 왜 했어?"

```
1. search_by_keyword(embedding=embed("결제 리팩토링"), top_k=5, threshold=0.30)
   → related_changesets: ["abc123", "def456"]
   → related_issues: ["HT-8"]

2. get_changeset_context(hash="abc123")
   → issues: [{jira_key: "HT-8", confidence: 0.85}]
   → communications: [{body: "validateToken() 제거 논의...", channel: "#backend"}]
   → file_changes: [{path: "src/payment/...", diffSummary: "validateToken() 제거..."}]

3. (선택) get_thread_context(conversation_id="1773799131")
   → 스레드 전체 맥락 확인

→ "HT-8 이슈에서 시작된 작업으로, Slack #backend에서 3월 1일 팀 논의 후
   PR #45로 머지됐습니다. validateToken() 중복 호출 제거가 핵심 변경입니다."
```

### "이번 주 뭐가 바뀌었어?"

```
1. get_recent_activity(from="2026-05-04T00:00:00Z", to="2026-05-10T23:59:59Z", limit=30)
   → 커밋 12건, PR 3건, Jira 이슈 5건 목록

2. (선택) get_changeset_context(hash=...) — 주요 커밋 상세 조회

→ 주요 변경 사항 요약 + 빠진 컨텍스트 명시
```

### "john-dev랑 jkim@co.com 같은 사람이야?"

```
1. inspect_actor("john-dev")
   → aliases: ["GITHUB:john-dev", "JIRA:account_abc"]
   → emails: ["jkim@co.com"]
   → merge_confidence: 0.91

→ "같은 Actor 노드로 통합되어 있습니다. confidence: 0.91"
```

---

## 시나리오 → 도구 매핑

| 시나리오 | 도구 |
|---------|------|
| 1. 타임라인 순서 보존 | `get_timeline` |
| 2. 코드 변경 이유 정확도 | `search_by_keyword` → `get_changeset_context` |
| 3. 인과관계 추론 품질 | `get_changeset_context` (confidence 필드 활용) |
| 4. 데이터 공백 구간 처리 | `check_missing_context` |
| 5. 크로스 소스 연결 정확도 | `get_issue_context`, `get_pr_context`, `get_thread_context` |
| 6. 증분 동기화 정확도 | `get_pr_context`, `check_missing_context` |
| 7. 컨텍스트 충돌 처리 | `get_conflict_context` |
| 9. 질문 범위 처리 | `get_recent_activity` |
| 10. Identity Resolution | `inspect_actor` |
| 11. 연결 부재 감지 | `check_missing_context` |
| 12. 전문가 식별 | `find_expert`, `get_actor_activity` |
