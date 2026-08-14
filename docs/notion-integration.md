# Notion 연동 계획 — 문서 아키타입 1호

`docs/integration-abstraction.md` Part B의 마지막 커넥터이자, **유일하게 ai-engine 신규 설계가
선행하는 예외**다. 기존 8개 커넥터는 `Issue`/`Communication`으로 정규화돼 ai-engine이 무변경이었지만,
Notion은 `Document` 노드를 새로 만든다 — 노드·엣지·임베딩·시맨틱 링크·질의 도구·성좌 뷰까지
전부 신규다.

Notion API 공식 문서 조사(2026-08, 최신 `Notion-Version: 2026-03-11` 기준)로 작성했다.
조사로 확정된 사실은 §12, 실기동으로만 확인 가능한 항목은 §13에 분리했다.

**이 문서의 무게 중심은 §2(그래프 설계)와 §3(수집 계약)이다.** 나머지(backend 연결·수집·화면)는
Google Chat·Discord와 같은 모양이라 「커넥터 엔드투엔드 체크리스트」를 그대로 따른다.

## 이 커넥터가 검증하는 것

1. **아키타입이 3개로 성립하는가** — 지금까지 `NormalizedEvent`의 `nodeType`은 4종이었고
   신규 커넥터는 전부 기존 nodeType에 얹혔다. Notion은 5번째 nodeType을 추가하는 첫 사례라,
   "이벤트는 소스가 아니라 nodeType으로 해석된다"는 계약이 **새 nodeType 추가에도 견디는지**를 본다.
2. **긴 텍스트의 임베딩 단위** — 지금까지 임베딩 대상은 전부 짧았다(커밋 메시지, 메시지 본문,
   이슈 제목+본문). 문서는 한 페이지가 수만 자다. 통짜 임베딩은 의미가 평균화돼 무엇과도
   안 맞으므로 **쪼개서 임베딩하고 합쳐서 엣지를 건다**는 새 패턴이 필요하다(§2-3).
3. **오래 사는 노드의 시간 윈도우** — Layer 4의 시간 윈도우(REFERENCE ±5일, 이슈 생애 윈도우)는
   전부 "짧게 살고 끝나는" 노드를 전제한다. 설계 문서는 한 번 쓰이고 몇 달간 참조되므로 그 전제가
   깨진다(§2-5).

## 0. 결정 사항 요약

| 항목 | 결정 | 근거 |
|------|------|------|
| provider 표기 | RDB/경로 `notion` · source `NOTION` · alias `NOTION:{userId}` · routing `event.notion`(자동 유도) | 한 단어라 전 계층 동일. `docs/normalized-event.md`「source·표기 규칙」 |
| API | Notion REST, **`Notion-Version` 헤더 고정 필수** | 버전이 헤더로 갈리고 2025-09-03에서 database → data source 비호환 변경이 있었다(§1-2) |
| 인증 모델 | **공개 연결(public integration) OAuth 2.0** | 내부 연결(internal)·PAT은 워크스페이스 하나에 묶여 다중 사용자 제품에 못 쓴다 |
| 연결 플로우 | OAuth → **선택 단계 없음** → 즉시 확정 (Slack·Discord형) | Notion 동의 화면의 **페이지 피커가 곧 선택**이다. 사용자가 공유한 페이지만 API에 보인다(§1-3) |
| 수집 범위 | 사용자가 공유한 **page 전체**(+ 하위 페이지 자동 상속). database/data source는 노드로 만들지 않는다 | §2-4 |
| 새 nodeType | **`Document`** — 자연키 `(project_id, source, external_id)` | `Communication` 재사용은 **싼 일만 아끼고 청킹·시간 윈도우·노이즈 필터 격리는 못 아낀다**(§2-0). 자연키는 Issue 전례 — page id가 불변(§2-1) |
| 임베딩 단위 | **`DocumentSection` 서브노드**(heading 경계 청킹). 시맨틱 엣지는 **Document에** 건다 | 통짜 임베딩은 다주제 평균화로 무력화된다. 8191토큰 상한도 넘는다(§2-3) |
| 토큰 | **`AccessTokenRefresher` 미구현**(비만료 취급, Discord형 404 경로). refresh token은 저장만 해 둔다 | 갱신 응답에 `expires_in`이 **없어** 만료 임박 판정 자체가 불가능하다. 갱신은 **회전형**이라 근거 없는 선제 갱신이 오히려 자격증명을 잃을 위험을 만든다(§4-3) |
| 원격 폐기 | `ProviderCredentialLifecycle` **구현** — `POST /v1/oauth/revoke` | Basic auth + `{token}`. `externalRef` 불필요 |
| 증분 | `POST /v1/search` `sort: last_edited_time desc` + **checkpoint 도달 시 조기 중단** | search API에 **시간 필터가 없다** — 정렬 기반 증분(Teams와 같은 형태) |
| checkpoint 전진 | **실행 끝에 딱 한 번** | ⚠️ 내림차순 순회라 **페이지 단위로 전진시키면 과거분이 영구 누락된다**(§5-2 — 최근 Google Chat 변경을 그대로 복사하면 사고) |
| 편집 추적 | **가능** — 편집된 문서는 `last_edited_time`이 갱신돼 다시 상위로 올라온다 | 대화 아키타입(Slack·Discord·Google Chat)이 못 하던 것 |
| 삭제·아카이브 | Phase 1은 **추적하지 않는다**(그래프에 잔존, 알려진 한계) | 삭제 이벤트는 계약 전반의 성격을 바꾼다. 단 `filter.in_trash=true`로 조회할 수단은 있다 — Phase 2 reconcile 후보(§5-5) |
| 개인정보 | `created_by`·`last_edited_by`가 **partial user(id만)** → `GET /v1/users` 전량 조회로 보강 | Google Chat의 People API 사건과 **정확히 같은 함정**이다(§8) |
| rate limit | 연결당 **평균 3 req/s**. 429에 `Retry-After` 헤더 제공 | Google Chat과 달리 서버가 대기 시간을 알려준다 — 백오프보다 헤더를 우선한다 |
| PR 분할 | **1 PR 규칙의 예외 — 4개로 나눈다**(N0~N3) | ai-engine 신규 설계가 커넥터보다 크다(§10) |

---

## 1. 사전 준비

### 1-1. Notion 공개 연결(public integration) 등록

- Notion 개발자 콘솔에서 **public** 타입으로 만든다. internal은 워크스페이스 하나에 고정이라
  다중 사용자 제품에 못 쓴다.
- redirect URI: `{BASE}/api/v1/integrations/notion/callback` — **소문자 kebab이며 이후 변경 불가**.
  `{BASE}`는 **프론트 오리진**이다. 콜백이 돌려주는 302가 상대 경로라 backend(:8080)를 직접
  등록하면 연동은 성공해도 마지막 리다이렉트가 401로 끝난다(Discord에서 실제로 겪은 사고 —
  `docs/discord-integration.md` §1).
- 동의 URL 파라미터: `client_id` · `response_type=code` · `owner=user` · `redirect_uri` · `state`.
  Google처럼 `access_type`/`prompt`를 넣을 필요가 없다.
- **capabilities 설정이 곧 scope다.** Notion은 OAuth scope 문자열이 아니라 연결 설정의
  capability 체크박스로 권한을 정한다.

  | capability | 설정값 | 용도 |
  |------------|--------|------|
  | Content | **Read content** only | 페이지·블록 읽기. update/insert는 켜지 않는다(최소 권한) |
  | User information | **Read user information including email addresses** | `actor.name`·`actor.email` — 없으면 `/v1/users`가 403 |
  | Comments | 끔 | Phase 1은 댓글을 수집하지 않는다(§2-4) |

  이메일 포함은 동의 화면에 그대로 노출되므로 사용자가 보게 된다. 그럼에도 켜는 이유는 이메일이
  Actor 동일인 판단의 가장 강한 신호이기 때문이다(Google Chat과 같은 판단).
- 환경변수(`ATLASSIAN_*` 패턴): `NOTION_CLIENT_ID` · `NOTION_CLIENT_SECRET` · `NOTION_REDIRECT_URI`.
  backend에만 필요하다(pipeline-worker는 DB의 사용자 토큰으로 수집한다).
  `infra/docker/docker-compose.yml` backend 블록에 추가하고 실제 값은 `.env`(gitignore).

### 1-2. `Notion-Version` 헤더 — 반드시 상수로 고정한다

Notion은 URL이 아니라 **헤더로 API 버전을 가른다**. 헤더를 빠뜨리면 계정에 설정된 기본 버전이
적용돼 **배포 시점에 따라 응답 형태가 달라진다.**

- 2025-09-03에서 database가 database(컨테이너)와 data source(테이블)로 쪼개지는 **비호환 변경**이
  있었다. `POST /v1/search`의 `filter.value`도 `"database"` → `"data_source"`로 바뀐다.
- 최신은 `2026-03-11`이다. **한 상수(`NotionProperties.apiVersion`)로 고정하고 모든 요청에 싣는다.**
- 우리는 page만 수집하고 database/data source는 노드로 만들지 않으므로(§2-4) 이 변경의 영향은
  `search` 필터 값 한 곳뿐이다 — 최신 버전으로 고정하고 `filter.value = "page"`만 쓴다.

### 1-3. 선택 단계가 없는 이유 — 페이지 피커가 곧 선택이다

Notion 동의 화면에는 "Select pages" 단계가 있어 사용자가 공유할 페이지·데이터베이스를 직접 고른다.
고르지 않은 페이지는 우리 토큰으로 **아예 조회되지 않는다**. 즉 수집 범위 선택이 provider의 동의
화면 안에서 끝난다 — Discord(자기 동의 화면에서 서버 선택)와 같은 모양이고, `IntegrationSelectionFlow`를
구현하지 않는다.

부수효과가 하나 있다. 공유된 페이지의 **하위 페이지는 자동으로 상속**되므로, 사용자가 최상위
위키 페이지 하나를 고르면 그 아래 수백 페이지가 전부 수집 대상이 된다. 사용자는 이걸 예상하지
못할 수 있으므로 **연결 UI와 개인정보 고지에 "선택한 페이지의 하위 페이지도 함께 수집된다"를
명시한다**(§7·§9).

---

## 2. 그래프 설계 — `Document` 노드 (핵심)

`docs/graph-schema.md`가 `Document`를 "_(미래)_ 장기 문서"로, `DESCRIBED_IN (Issue)→(Document)`를
예약해 두었다. 그 자리를 채우되 **예약된 이름은 그대로 쓴다**(설명문은 현재 내용이 어긋나 있어
함께 고친다 — `DESCRIBED_IN`에 "Actor가 문서에 기술됨"이라고 적혀 있는데 방향과 맞지 않는다).

### 2-0. 먼저 — 왜 `Communication` 재사용이 아닌가

가장 싼 길은 Notion 페이지를 `Communication`으로 정규화하는 것이다(body=본문, url=페이지 URL,
channel=부모 페이지, conversation_id=부모 id). 그러면 **ai-engine이 무변경**이라 이 문서 전체가
사라지고 커넥터 1개 = 1 PR 규칙 안에 들어온다. 진지한 대안이므로 왜 안 하는지 남긴다.

**핵심: 재사용은 싼 일만 아끼고 비싼 일은 하나도 못 아낀다.**

| 재사용이 아끼는 것 | 재사용이 **못** 아끼는 것 |
|---|---|
| 노드·유니크 제약·벡터 인덱스(§2-9) | **청킹**(§2-3) — `Communication.embedding`은 노드에 1개다. 긴 페이지는 8191토큰을 넘어 `embedder`가 빈 벡터로 채운다 |
| 질의 도구 2종(§6-4) | **시간 윈도우**(§2-6) — Communication으로 두면 ±5일·이슈 생애 윈도우를 그대로 상속한다 |
| 프론트 타입·색·성좌 분기(§7) | **노이즈 필터 격리** — 아래 |

오른쪽 셋은 전부 공용 코드(`graph/embedder.py` · `issue_linker.py` · `reference_builder.py` ·
`postprocess.py`) 수정이라 노드를 새로 만들든 재사용하든 똑같이 해야 한다. 따라서 "1 PR vs 4 PR"이라는
비교는 성립하지 않는다 — 재사용이 아끼는 건 N1의 일부와 N3뿐이다.

**그리고 재사용에는 적극적 손해가 하나 있다.** `run_postprocess_sequence` 0단계의
`run_slack_llm_filter`는 `llm_filtered=false`인 Communication을 **source 구분 없이** 훑어
"의사결정 맥락 없음"으로 판정된 노드를 **삭제한다**. Notion 문서가 Slack 잡담 기준으로 심사받아
지워지고, 문서 수만큼 LLM 비용도 든다. 막으려면 그 필터에도 source 가드를 넣어야 한다 —
공용 코드 수정이 하나 더 늘 뿐이다.

> **과장하지 않기.** "Communication의 자연키가 `url`인데 Notion URL은 제목 따라 바뀐다"는
> 결정적 근거가 **아니다** — `https://www.notion.so/{32hex}` 정규 형태로 저장하면 안정적이다.
> 재사용을 막는 건 위 세 가지지 자연키가 아니다.

**타임라인이 급하면 성립하는 대안**: Phase 0으로 재사용본을 먼저 띄우고(본문 앞 8,000자 절삭,
필터 source 가드만 추가) 문서 링크가 실제로 쓸모 있는지 본 뒤 Document로 옮기는 길이 있다.
이행 비용도 낮다 — 이 프로젝트는 이미 "그래프를 새로 구축한다"를 관행으로 삼는다(A6 전례).
그럼에도 권하지 않는 이유는, 그 경로가 미루는 일이 **정규화 로직을 두 번 쓰는 것**뿐이고
정작 비싼 세 가지는 그때도 똑같이 해야 하기 때문이다.

### 2-1. 자연키 — `(project_id, source, external_id)`

Issue의 전례를 따른다.

- **page id(UUID)는 불변**이고, URL은 `https://www.notion.so/{제목-슬러그}-{id}` 형태라 제목을 바꾸면
  바뀐다. URL을 키로 쓰려면 `{32hex}` 정규 형태로 강제해야 하는데, 불변 id가 따로 있는데 굳이
  URL을 키로 삼을 이유가 없다.
- `source`를 키에 넣어 두면 나중에 Confluence·Google Docs가 붙어도 같은 라벨을 공유할 수 있다
  (Issue가 Jira·Linear·Asana·ClickUp을 한 라벨로 담는 것과 같다).

```json
{
  "project_id": "",        // 프로젝트 UUID (격리 기준)
  "source": "NOTION",      // 자연키 일부
  "external_id": "",       // Notion page id (UUID) — 자연키
  "title": "",             // 페이지 제목 (title property의 plain_text)
  "body": "",              // 블록을 평문으로 펼친 본문 (상한 §2-2)
  "url": "",               // 표시·링크용 (자연키 아님)
  "createdAt": "",         // created_time
  "occurredAt": "",        // last_edited_time — checkpoint 전진 기준
  "parent_type": "",       // page_id | database_id | data_source_id | workspace
  "parent_external_id": "" // 부모 page id (CHILD_OF 매칭 키). 부모가 page가 아니면 null
}
```

`Document`에는 **embedding을 두지 않는다** — 임베딩은 전부 `DocumentSection`에 있다(§2-3).

### 2-2. 본문 평문화 — 블록 트리를 어떻게 접는가

Notion 본문은 블록 트리다. 재귀 조회(`GET /v1/blocks/{id}/children`)로 받아 마크다운 유사 평문으로
접는다. **pipeline-worker가 접어서 `properties.body`로 보낸다** — ai-engine이 Notion 블록 구조를
알 필요가 없어야 계약이 소스 중립으로 남는다.

| 블록 타입 | 평문화 |
|-----------|--------|
| `heading_1/2/3` | `# ` / `## ` / `### ` + plain_text — **청킹 경계라 반드시 보존한다** |
| `paragraph` · `quote` · `callout` | plain_text 한 줄 |
| `bulleted_list_item` · `numbered_list_item` · `to_do` | `- ` 접두 (to_do는 `- [x] `/`- [ ] `) |
| `code` | ` ``` ` 펜스 + language |
| `table` | 행별 `|` 구분 평문 |
| `child_page` · `child_database` | **제목만 남기고 재귀하지 않는다** — 각자 별도 Document다 |
| `image` · `file` · `embed` 등 | caption만(없으면 제외). 파일 본문은 받지 않는다 |

상한(무한 페이지 방어):

- 재귀 깊이 **5단**, 페이지당 블록 **2,000개**, `body` **100,000자**. 넘으면 잘라내고 warn 로그.
- 각 rich_text의 `plain_text`만 쓴다(annotation·색은 버린다).

### 2-3. 임베딩 단위 — `DocumentSection` 서브노드

**결정: 문서를 heading 경계로 쪼개 섹션마다 임베딩하고, 시맨틱 엣지는 Document에 건다.**

통짜 임베딩을 쓰지 않는 이유가 둘이다.

1. **의미가 평균화된다.** Layer 4는 전부 코사인 유사도인데, 여러 주제를 담은 5,000자 스펙 문서의
   단일 벡터는 어떤 커밋과도 어중간하게 닮아 임계값 근처에 몰린다. 섹션 단위면 "토큰 갱신 정책"
   섹션이 토큰 갱신 커밋과 정확히 붙는다.
2. **임베딩 입력 상한을 넘는다.** `text-embedding-3-large`는 8,191토큰이 상한이고, 한국어는
   토큰이 무거워 대략 1만 2천 자 근처에서 걸린다. 지금 `graph/embedder.py`에는 **길이 상한 처리가
   없고**, 실패한 청크는 `빈 벡터로 채움` 경로를 탄다 — 즉 긴 문서는 **에러 없이 조용히
   embedding=[]로 저장되고 시맨틱 링크에서 사라진다.** 통짜로 가면 이게 기본 동작이 된다.

전례도 이미 있다. ChangeSet은 커밋 전체가 아니라 **파일별로 쪼개** `MODIFIED` 엣지에 임베딩을
단다. "큰 것을 부분으로 쪼개 부분 단위로 임베딩한다"는 이 코드베이스의 확립된 패턴이고,
Document는 그 두 번째 사례다.

MODIFIED처럼 엣지 속성에 담지 않고 **별도 노드**로 두는 이유: 파일은 여러 커밋이 공유하는 독립
개체지만 섹션은 문서 전용이라 반대편에 놓을 노드가 없다.

```json
// DocumentSection — 자연키 (project_id, source, document_external_id, ordinal)
{
  "project_id": "", "source": "NOTION",
  "document_external_id": "",  // 소속 Document
  "ordinal": 0,                // 문서 내 순번
  "heading_path": "",          // "인증 > 토큰 갱신" — 임베딩 입력 앞에 붙인다(맥락 보존)
  "text": "",                  // 섹션 본문
  "embedding": []              // heading_path + "\n\n" + text 임베딩
}
```

`(DocumentSection)-[:PART_OF]->(Document)`.

청킹 규칙:

- `heading_1/2/3` 경계로 자른다. heading 앞의 서두는 ordinal 0 섹션(heading_path = 문서 제목).
- 섹션이 **1,500자를 넘으면** 문단 경계로 재분할(같은 heading_path 유지, ordinal만 증가).
- 섹션이 **200자 미만이면** 다음 섹션과 병합 — 목차·한 줄 heading이 벡터 공간을 오염시키지 않게.
- heading이 하나도 없는 문서는 문단 경계로만 1,500자 단위 분할.

**재수집 시 섹션은 전량 교체한다**(upsert가 아니라 delete-then-create). 문서 중간에 문단이 하나
추가되면 이후 ordinal이 전부 밀려 부분 갱신이 의미가 없기 때문이다. 이게 안전한 이유가 바로
**시맨틱 엣지를 섹션이 아니라 Document에 걸기 때문**이다 — 섹션이 통째로 갈려도 엣지는 문서에
남아 있고, 다음 빌드가 갱신한다.

### 2-4. 수집하지 않기로 한 것

| 대상 | 결정 | 이유 |
|------|------|------|
| database / data source | Document 노드로 만들지 않는다 | 스키마 컨테이너지 문서가 아니다. 안의 page는 각자 Document로 수집된다 |
| database 안 page의 property 값 | `body`에 넣지 않는다 | 팀마다 자유 스키마라 소스 중립 매핑이 없다. 제목(title property)만 쓴다 |
| 페이지 댓글(Comments API) | Phase 1 제외 | 별도 엔드포인트·별도 capability. 굳이 넣는다면 `Communication`이지 `Document`가 아니다 — 아키타입이 갈리므로 섞지 않는다 |
| 파일·이미지 본문 | 제외 | caption만 |

### 2-5. 관계 — 6종 (신규 어휘는 `EDITED` · `PART_OF` 둘뿐)

| 관계 | 방향 | 레이어 | 비고 |
|------|------|--------|------|
| `WROTE` | `(Actor)→(Document)` | 1 | `created_by`. Communication과 같은 동사를 쓴다(둘 다 텍스트 작성) |
| `EDITED` | `(Actor)→(Document)` | 1 | `last_edited_by`. **신규 어휘** |
| `PART_OF` | `(DocumentSection)→(Document)` | — | **신규 어휘**. 내부 구조 |
| `CHILD_OF` | `(Document)→(Document)` | 2 | 부모 페이지. Issue→Issue 전례 재사용, pre-node MERGE |
| `DESCRIBED_IN` | `(Issue)→(Document)` | 2(text) + 4(semantic) | `source: text|semantic`, `confidence`를 보존한다. 명시 참조와 추론을 질의·답변에서 구분한다 |
| `DISCUSSED_IN` | `(Document)→(Communication)` | 2(text) | 대화 본문의 Notion URL. **기존 관계의 시작 라벨 확장** |
| `REFERENCE` | `(ChangeSet)→(Document)` | 2(text) + 4(semantic) | 커밋 본문의 Notion URL + 시맨틱. **기존 관계의 끝 라벨 확장** |
| `REFERENCE` | `(PullRequest)→(Document)` | 2(text) | PR 제목/본문의 Notion URL. **그 PR의 CONTAINS 커밋에 전파**한다 |

**`(PullRequest)→(Document)`를 Phase 1에 넣는 이유**: 실무에서 Notion 링크가 가장 자주 박히는 곳이
커밋 메시지가 아니라 **PR 본문**이다. 그리고 전파 기계가 이미 있다 —
`link_pr_changesets_to_issues`/`link_pr_changesets_to_issue_externals`가 PR의 참조를 CONTAINS 커밋에
퍼뜨리는 것과 똑같이, `documentExternalRefs`를 `pr.document_external_ids`
(`"SOURCE:externalId"` 문자열 배열 — 맵 배열은 Neo4j 속성으로 저장 불가)에 실어 같은 경로로 전파한다.
비용이 거의 없는데 빼면 문서 링크의 주 유입로가 막힌다.

**`EDITED`는 스냅샷이 아니라 누적이다.** Notion은 *마지막* 편집자만 알려주므로, `ASSIGNED_TO`처럼
스냅샷 교체 규약(배열에 없는 사람은 해제)을 적용하면 편집자가 바뀔 때마다 이전 편집자가 지워져
"이 문서에 누가 손댔나"가 항상 1명이 된다. **MERGE만 하고 지우지 않는다** — 계약 문서에도
명시한다(`docs/normalized-event.md`).

**`DESCRIBED_IN`은 반드시 근거 종류를 보존한다.** 문서 제목·본문에서 이슈 키/URL을 추출한
연결은 `source='text'`, `confidence=1.0`으로 쓴다. `DocumentSection.embedding` 유사도로 만든
연결은 `source='semantic'`, `confidence=코사인 점수`, `section=최고점 heading_path`로 쓴다.
둘은 같은 이슈–문서 쌍에 중복 생성하지 않고 **text를 우선**한다. 도구와 에이전트는 text를
“문서에 명시됨”, semantic을 “관련 문서로 추론됨”으로 표시해야 하며, 후자를 명시적 설계 근거처럼
단정하지 않는다. `TRIGGERED_BY`의 provenance 패턴을 재사용하는 것이므로 새 관계 어휘는 추가하지
않는다.

`DISCUSSED_IN`의 시작 라벨을 늘리는 건 기존 쿼리를 깨지 않는다 — 확인했다.
`tools/queries/discovery.py`·`issue.py`의 모든 사용처가 `(i:Issue)-[:DISCUSSED_IN]->(c:Communication)`처럼
**양 끝 라벨을 명시**한다. 다만 `tools/queries/explore.py`의 `NODE_LABELS`·`REL_TYPES` 허용 목록과
`run_graph_query` 스키마 설명문은 **반드시 함께 갱신**한다(§6-4).

### 2-6. ⚠️ Layer 4 시간 윈도우 — 문서는 오래 산다

기존 Layer 4의 시간 윈도우는 전부 "짧게 살고 끝나는" 노드를 전제한다.

- `REFERENCE`: `TIME_WINDOW_DAYS = 5` (커밋 ↔ 대화 ±5일)
- `DISCUSSED_IN`/`TRIGGERED_BY`: 이슈 생애 윈도우 `[createdAt-4d, closedAt+3d]`

설계 문서는 한 번 쓰이고 **몇 달간 참조된다.** 이 윈도우를 그대로 쓰면 문서가 만들어진 그 주의
커밋만 붙고, 정작 그 문서를 근거로 3개월 뒤에 이뤄진 변경은 전부 잘린다 — 즉 이 커넥터의 목적
자체가 무력화된다.

**결정: Document 대상 시맨틱 링크는 하한만 두고 상한을 두지 않는다.**

- 윈도우 = `[document.createdAt - document_pre_days, ∞)`, `document_pre_days` 기본 **7**.
  하한을 두는 이유: 문서가 쓰이기 전의 커밋을 그 문서가 설명한다고 보기 어렵다. 회고성 문서를
  위해 7일 버퍼만 둔다.
- 상한이 없으면 후보가 폭증하므로 **문서당 top-k 컷**(기본 5)으로 제어한다. `TRIGGERED_BY`가
  "ChangeSet당 top-1"으로 fan-out을 막는 것과 같은 장치이며, 방향만 반대다(문서 쪽에서 자른다 —
  문서 하나가 여러 변경의 근거인 게 정상이고, 커밋 하나가 여러 문서를 근거로 삼는 것도 정상이라
  양쪽 다 열어두면 곱으로 터진다).
- 임계값은 새 상수 `DOCUMENT_REFERENCE_THRESHOLD` · `DESCRIBED_IN_THRESHOLD`로 두고 **초기값은
  기존 대응 엣지와 같게** 시작한 뒤(`0.44` · `0.48`), `docs/measurement.md`의 eval로 재스윕한다.
  섹션 단위 비교는 통짜보다 점수가 높게 나오므로 **임계값을 올려야 할 가능성이 크다** — 기존
  값을 그대로 굳히지 않는다.
- `DESCRIBED_IN` semantic 엣지는 `source='semantic'`, `confidence=점수`, `section=최고점
  heading_path`를 저장한다. text 엣지가 이미 있는 이슈–문서 쌍은 후보에서 제외한다(§2-5의
  text 우선 규약).

### 2-7. ⚠️ `REFERENCE`에 text 경로가 생긴다 — `clear_reference`가 지운다

**이건 A8·A9와 같은 성격의 구멍이고, 코드를 짜기 전에 처리해야 한다.**

지금 `REFERENCE`는 **전부 시맨틱 산물**이라는 전제 위에 서 있다. 그래서 정밀 재구축
(`/graph/build?verify=true`)의 `clear_reference()`가 **조건 없이 전량 삭제**한다 —
`TRIGGERED_BY`·`DISCUSSED_IN`이 `source='semantic'`인 것만 지우는 것과 대조적이다.
`docs/graph-schema.md`에 그 이유가 적혀 있다: "REFERENCE는 텍스트 경로가 없어 전부 시맨틱
산물이므로 전량 삭제 후 재생성된다."

Notion은 **그 전제를 깬다.** 커밋 메시지나 PR 본문에 Notion URL이 박히는 건 흔하고, 그건 유사도
추정이 아니라 확정적인 text 엣지다. 그대로 두면:

> 사용자가 '정밀 재구축'을 한 번 누르는 순간 **URL로 명시된 문서 링크가 전부 사라지고, 다시
> 만들어지지 않는다.** 시맨틱 빌더는 유사도로만 엣지를 만들므로 text 엣지를 복원할 수 없고,
> 복원하려면 GitHub을 재수집해야 한다.

**해결: `REFERENCE`에 `source: text|semantic` 속성을 도입하고 `clear_reference`를
`source='semantic'` 스코프로 좁힌다.** `TRIGGERED_BY`가 이미 쓰는 패턴을 그대로 옮기는 것이라
새 개념이 아니다.

- 기존 엣지는 전부 시맨틱이므로 `source`가 없는 엣지는 `'semantic'`으로 간주한다
  (`coalesce(r.source, 'semantic')`) — 백필 마이그레이션 없이 동작 불변.
- text 엣지는 `confidence = 1.0` 고정(`TRIGGERED_BY` text와 같다).
- text 엣지가 있는 (ChangeSet, Document) 쌍은 시맨틱 빌더에서 제외한다(text 우선 — `TRIGGERED_BY`와 동일).

**이 변경은 Notion과 무관한 공용 코드**라 선행 PR(N0)로 뺀다(§10).

### 2-8. 소스 삭제 cascade

`delete_project_source_graph`의 1단계는 `MATCH (n {project_id, source})`로 **라벨 없이** 훑으므로,
`Document`와 `DocumentSection`에 `source` 속성만 있으면 **코드 변경 없이 함께 지워진다.**
→ 두 노드 모두 `source = 'NOTION'`을 반드시 싣는다.

추가로 확인할 것:

- **고아 `DocumentSection`** — Document가 지워졌는데 섹션이 남는 경우. 위 규칙대로면 같은 소스라
  동시에 지워지므로 생기지 않는다. 다만 `File`의 고아 정리(2단계)와 같은 방어를 하나 넣을지는
  선택 — **넣지 않는다.** File은 `source`가 없어서 필요했던 예외고, 섹션에는 `source`가 있다.
- **Actor 정리**(3단계) — `NOTION:` 접두 alias만 가진 Actor는 삭제, 다른 소스가 남았으면 alias만
  제거. 이미 소스 문자열 기반이라 자동으로 맞는다.

### 2-9. 스키마 부트스트랩

`graph/schema.py`에 추가한다.

- 유니크 제약 2개:
  - `document_project_source_external` — `Document(project_id, source, external_id)`
  - `document_section_key` — `DocumentSection(project_id, source, document_external_id, ordinal)`
- 벡터 인덱스 1개: `doc_section_embedding` — `DocumentSection.embedding`, 1536차원, cosine.
  (`comm_embedding`·`issue_embedding`과 같은 형태.)
- Document 자체엔 벡터 인덱스가 없다(임베딩이 없으므로).

---

## 3. NormalizedEvent 계약 확장 — 5번째 nodeType

`docs/normalized-event.md`를 함께 고친다. **봉투의 `nodeType` 열거에 `Document`를 추가**하고
properties 절을 새로 쓴다.

### Document — 문서 (자연키: `external_id` — source와 함께 유니크)

| 키 | 타입 | 비고 |
|----|------|------|
| `external_id` | string | **자연키. 없으면 ai-engine이 이벤트를 버린다.** Notion page id(UUID) |
| `title` | string | 페이지 제목 |
| `body` | string | 평문화된 본문(§2-2). ai-engine이 섹션으로 쪼개 임베딩한다 |
| `url` | string | 표시·링크용. **자연키가 아니다**(제목 변경 시 바뀐다) |
| `created_at` | string | `created_time` |
| `parent_type` | string | `page_id` \| `database_id` \| `data_source_id` \| `workspace` |
| `parent_external_id` | string \| 생략 | 부모 **page** id. 부모가 page가 아니면 생략 — `CHILD_OF` 매칭 키 |

`occurredAt`: `last_edited_time`.

**refs**

| 키 | 비고 |
|----|------|
| `issueKeys` | 본문·제목의 이슈 키 **복수**. 문서엔 여러 개가 흔하다 → `(Issue)-[:DESCRIBED_IN]->(Document)` |
| `issueExternalRefs` | URL 기반 이슈 참조(Asana·ClickUp형) — 같은 엣지로 수렴 |
| `editors` | `[{id, name, email, bot}]` — `last_edited_by` 1명을 배열로 감싼다. **누적 반영**(§2-5) |

> `refs.editors`는 `assignees`와 **의도적으로 다른 규약**이다. `assignees`는 스냅샷이라 배열에서
> 빠진 사람이 해제되지만, `editors`는 누적이라 지우지 않는다. 계약 문서의 「담당자 해제 규약」
> 옆에 「편집자 누적 규약」으로 나란히 적는다 — 같은 모양의 배열이 반대로 동작하므로 명시하지
> 않으면 다음 사람이 반드시 틀린다.

**역방향 참조**(다른 소스가 Notion 문서를 가리키는 경우)는 `RefsExtractor`에 Notion URL 패턴을
추가해 새 ref 키 `documentExternalRefs`(`{source, externalId}[]`)로 낸다. `issueExternalRefs`와
같은 메커니즘이며, 소비처는 ChangeSet·PullRequest(→`REFERENCE` text)와 Communication(→`DISCUSSED_IN` text)이다.

Notion URL 형태: `https://www.notion.so/{워크스페이스}/{제목-슬러그}-{32자리 hex}` 또는
`https://www.notion.so/{32자리 hex}`. **끝의 32자리 hex를 하이픈 UUID로 정규화**해 `external_id`와
맞춘다(API가 돌려주는 id는 하이픈이 있고 URL에는 없다 — 여기서 안 맞으면 링크가 조용히 0건이 된다).

이 시점에서 URL 기반 ref 패턴이 세 벌(Jira 키 형식, Asana/ClickUp URL 형식, Notion URL 형식)이
되므로, `docs/integration-abstraction.md` §3-1이 미뤄 둔 **`RefsExtractor` 패턴 레지스트리화를
여기서 한다** — "다음 URL 기반 소스 착수 시"가 바로 지금이다.

---

## 4. backend — 연결

`com.history.backend.notion` 패키지(신규). SPI **2개만** 구현한다 — 선택 단계 없음(§1-3),
토큰 갱신 없음(§4-3).

### 4-1. 구현 목록

- `IntegrationProvider.NOTION("notion", "Notion")` 추가. **`integrations` 마이그레이션 불필요**
  (V12에서 provider CHECK 제거). `checkpoints`도 불필요(V16에서 제거 — A9).
- `NotionProperties`(`@ConfigurationProperties`) + `application.yaml`(운영·테스트 양쪽).
  `apiVersion`을 여기에 둔다(§1-2). **`PropertiesConfig`의 `@EnableConfigurationProperties` 목록에
  등록하는 것을 빠뜨리지 않는다** — 빠뜨리면 컨텍스트 로드 테스트가 통째로 죽는다(Discord에서 밟은 지점).
- `NotionClient` — code 교환(`POST /v1/oauth/token`, **Basic auth**), 폐기(`POST /v1/oauth/revoke`),
  `GET /v1/users/me`(연결 확인용).
- `NotionOAuthConnectFlow` — 동의 URL(`owner=user`·`response_type=code`·state),
  `exchangeCode`가 `OAuthConnection`(자격증명 + 수집 대상 참조)을 반환. **선택 단계가 없으므로
  `pendingSelection`이 아니라 확정 형태로 돌려준다.**
- `NotionCredentialLifecycle`(`ProviderCredentialLifecycle`) — `POST /v1/oauth/revoke`.
  Basic auth + `{"token": access_token}`. `externalRef`는 쓰지 않는다. 실패는 삼킨다(공용 규약).
- `IntegrationResponse.displayName` switch에 `NOTION` case — `selectionValue("workspace_name")`.
  (exhaustive switch라 추가하지 않으면 컴파일이 깨진다.)
- 검증: `./gradlew test`

### 4-2. external_ref

토큰 응답이 그대로 준다: `workspace_id` · `workspace_name` · `bot_id`.
`workspace_name`이 연동 행 표시 이름이고, `bot_id`는 나중에 "우리 앱이 만든 페이지" 판별에 쓸 수
있으니 함께 저장한다. **pipeline-worker는 external_ref를 읽지 않는다** — 수집 범위가 토큰에
암시돼 있어서다(Slack과 같다).

### 4-3. 토큰 — `AccessTokenRefresher`를 만들지 않는 결정

Google Chat의 거울상이라 헷갈리기 쉬운 자리다. 셋 다 다르다.

| provider | refresh token 회전 | 만료 시각 | 우리 구현 |
|----------|-------------------|----------|----------|
| Jira/Atlassian | 회전한다 | `expires_in` 있음 | `AccessTokenRefresher` 구현 |
| Google Chat | **회전하지 않는다** | `expires_in` 있음(~1시간) | 구현. 갱신 응답에 refresh_token이 없어도 **기존 값 보존** |
| **Notion** | **회전한다** | **`expires_in` 없음** | **구현하지 않는다** |

Notion의 갱신 응답에는 `expires_in`도, 다른 어떤 만료 정보도 없다(조사 확인 — §12-4).
`AccessTokenRefresher.ensureFreshAccessToken`의 계약이 "**만료 임박이면** 갱신"인데, 만료 임박을
판정할 입력이 아예 없다. 억지로 붙이면 "마지막 갱신 후 N시간 경과 시 갱신" 같은 근거 없는 주기가
되는데, Notion은 **회전형**이라 갱신할 때마다 옛 refresh token이 무효화된다 — 저장에 한 번 실패하면
연동이 죽는다. **얻는 것 없이 자격증명을 잃을 위험만 만든다.**

따라서 Discord와 같은 비만료 취급으로 간다. 내부 토큰 API는 빈이 없어 404를 답하고, §2-1 선행
변경(webhook 토큰 확보 일반화, Google Chat에서 완료) 덕에 호출부는 **저장된 자격증명 그대로
진행**한다 — Slack·Discord와 같은 경로다.

**단, 대비는 해 둔다.**

- 자격증명 JSON에 `refresh_token` 자리를 **처음부터 만들어 저장한다.** 나중에 반응형 갱신을 붙일 때
  마이그레이션이 필요 없도록.
- 갱신을 붙이게 되면 **회전 규약**(응답의 새 refresh_token을 반드시 저장)을 단위 테스트로 고정한다 —
  Google Chat의 "보존" 테스트와 정반대 방향이라 그 코드를 복사하면 깨진다.
- §13-1을 실기동에서 확인한다: 며칠 지난 토큰으로 수집이 401을 맞는지.

---

## 5. pipeline-worker — 수집

`source/notion` 패키지(신규)에 `NotionCollector` · `NotionRawService` · `NotionNormalizer` ·
`NotionRateLimiter`. `CollectionProvider.NOTION("notion")` 추가 외에 오케스트레이션 계층은 무변경이다.

### 5-1. 수집 흐름

```
resolveFetchRequest: 자격증명 JSON 복호화 → access_token Bearer
                     (external_ref는 읽지 않는다 — 범위가 토큰에 암시)
collect:
  반복: POST /v1/search
          { filter: {property:"object", value:"page"},
            sort:   {timestamp:"last_edited_time", direction:"descending"},
            page_size: 100, start_cursor: {직전 next_cursor} }
        → last_edited_time <= checkpoint 인 항목을 만나면 그 자리에서 중단
  각 page:
     GET /v1/blocks/{page_id}/children  (재귀, page_size=100, 깊이 5·블록 2000 상한)
     → 평문화(§2-2)
  등장한 created_by/last_edited_by id 집합
     → GET /v1/users (전량 페이지네이션, TTL 캐시) → 이름·이메일·bot 여부 (§8)
  → normalize → publish (배치 단위)
  → ★ 실행 끝에 딱 한 번, 최대 occurredAt으로 checkpoint 갱신
```

### 5-2. ⚠️ checkpoint는 실행 끝에 한 번만 — 페이지 단위로 전진시키면 안 된다

**이 커넥터에서 가장 사고 나기 쉬운 지점이다.**

최근 Google Chat 수집이 "전량 축적 후 일괄 발행"에서 "페이지 단위 발행"으로 바뀌었고(커밋
`525d0d3`), Slack·Discord도 같은 모양이다. 그 패턴을 여기에 그대로 옮기면 **데이터가 영구
누락된다.**

이유는 정렬 방향이다. Google Chat은 `orderBy=createTime ASC`라 페이지를 넘길수록 커서가 앞으로
가지만, **Notion search에는 시간 필터가 없어 내림차순(최신 → 과거)으로만 증분이 성립한다.**
내림차순에서 첫 배치는 가장 최신이므로, 그걸로 checkpoint를 올리면:

> 첫 배치(최신 100건)를 발행하고 checkpoint를 그 최댓값으로 전진 → 아직 안 읽은 과거분이
> checkpoint보다 오래됨 → **다음 수집에서 조기 중단 조건에 걸려 영원히 스킵된다.**

**규칙:**

- **발행은 배치 단위로 한다**(메모리·재시도에 유리하고 공용 규약과도 맞는다).
- **checkpoint 전진은 그 실행의 마지막에 딱 한 번**, 그 실행에서 본 **최대** `last_edited_time`으로.
- 중간에 실패하면 checkpoint가 그대로라 다음 실행이 처음부터 다시 훑는다 — 재발행은 멱등이라
  데이터 사고가 아니다(계약: "중복 발행을 두려워하지 말고 누락을 두려워하라").
- 조기 중단 비교는 **strict**(`last_edited_time > checkpoint`인 동안만 계속)로 해 경계 항목의
  무한 재발행을 막는다.
- 이 규칙을 **단위 테스트로 고정한다** — "2페이지에 걸친 응답에서 checkpoint가 1페이지 최댓값이
  아니라 전체 최댓값으로 한 번만 갱신된다".

checkpoint 키: `notion/notion_pages` 단일 커서.

### 5-3. N+1 호출과 초기 수집 비용

페이지마다 블록 트리 재귀 조회가 붙어 호출 수가 `페이지 수 × (1 + 중첩 블록 요청)`이다.
연결당 평균 **3 req/s**이므로 200페이지 위키의 초기 수집은 대략 600~1,000요청 ≈ **4~6분**이다.

- 첫 수집이 오래 걸리는 건 받아들이되, **웹훅 증분은 짧아야 한다** — 편집된 페이지만 다시 긁으므로
  자연히 짧다.
- `has_children`이 false인 블록은 재귀하지 않는다(당연하지만, 이걸 빼먹으면 호출이 배가 된다).
- `child_page`는 재귀하지 않는다(§2-2) — 하위 페이지는 자기 차례에 독립 Document로 수집된다.
  **재귀하면 같은 본문이 부모·자식에 중복 저장되고 임베딩 비용이 배가 된다.**

### 5-4. Rate limit

`NotionRateLimiter` — 호출당 **350ms** 고정 딜레이(평균 3 req/s 아래로). 429·529를 받으면
**`Retry-After` 헤더(초)를 그대로 따르고**, 헤더가 없을 때만 지수 백오프
(`min((2^n)+jitter, 30s)`)로 최대 5회 재시도한다. Google Chat과 달리 서버가 대기 시간을 알려주므로
헤더가 백오프보다 **우선**이다.

한도가 **연결(우리 앱) 단위**라 사용자 수가 늘어도 늘지 않는다는 점은 Google Chat과 같다 —
사용자가 늘면 딜레이가 아니라 구조(수집 스케줄 분산)를 봐야 한다.

### 5-5. 삭제·아카이브 — Phase 1은 추적하지 않는다

Notion에서 페이지를 지우면 휴지통으로 가고, `search`는 기본적으로 그것들을 돌려주지 않는다.
따라서 삭제된 페이지는 **그래프에 그대로 남는다.**

Phase 1은 이걸 알려진 한계로 둔다. 삭제 이벤트를 도입하면 계약 전반의 성격(모든 이벤트가 멱등
upsert)이 바뀌는데, 문서 커넥터 하나를 위해 그 변경을 하지 않는다 — Slack의 삭제된 메시지,
Google Chat의 `showDeleted`와 같은 수준의 제약이다.

**다만 수단은 있다는 걸 기록해 둔다**(조사 확인): `POST /v1/search`에 `filter.in_trash: true`를
주면 휴지통 항목을 조회할 수 있다. Phase 2에서 "주기적 reconcile"(휴지통 목록 → 해당 Document 삭제)로
붙일 수 있고, 그때는 ai-engine에 **문서 단건 삭제 내부 API**가 하나 필요하다.

---

## 6. ai-engine — 신규 작업 (가장 큰 덩어리)

### 6-1. 수집 경로

- `graph/event_handler.py` — `nodeType == "Document"` 분기 + `_handle_document` 추가.
  `external_id` 없으면 폐기(Issue와 동일 정책).
- `graph/document_chunker.py`(신규) — `body` → 섹션 리스트(§2-3). **순수 함수**로 두어 단위
  테스트가 쉽도록 한다(네트워크·LLM 없음).
- `graph/writes.py` — `upsert_document` · `replace_document_sections` ·
  `link_document_to_parent`(pre-node MERGE) · `set_document_editors`(누적) ·
  `link_issue_to_document` · `link_document_to_communication` · `link_changeset_to_document`.
- `graph/schema.py` — 제약 2개 + 벡터 인덱스 1개(§2-9).
- 섹션 임베딩은 `embed_batch`로 **문서당 1콜**에 묶는다(ChangeSet의 파일 요약 배치와 같은 형태).

`_handle_document` 흐름:

```
resolve_actor(created_by)                       → WROTE
upsert_document(...)                            → 노드
chunk(body) → embed_batch(섹션들)               → replace_document_sections (전량 교체)
refs.editors → resolve_actor → set_document_editors (누적)
refs.parentExternalId → link_document_to_parent  (CHILD_OF, pre-node)
refs.issueKeys / issueExternalRefs → DESCRIBED_IN(source='text', confidence=1.0)
```

### 6-2. Layer 4 — 시맨틱 링크 2종 추가

`graph/document_linker.py`(신규). 기존 `issue_linker.py`·`reference_builder.py`와 같은 구조
(numpy 행렬곱 + 시간 윈도우 마스크 + 임계값)를 따르되 윈도우가 §2-6대로 다르다.

| 엣지 | 비교 대상 | 윈도우 | 컷 |
|------|----------|--------|-----|
| `REFERENCE` (ChangeSet→Document) | `MODIFIED.embedding` ↔ `DocumentSection.embedding` | `[doc.createdAt-7d, ∞)` | 문서당 top-5 |
| `DESCRIBED_IN` (Issue→Document) | `Issue.embedding` ↔ `DocumentSection.embedding` | 위와 같음 | 문서당 top-5 |

- 매칭은 섹션 단위, **엣지는 Document에** 건다. `REFERENCE`와 `DESCRIBED_IN` semantic 엣지는
  모두 `source='semantic'`, `confidence=점수`, `section=최고점 heading_path`를 저장한다.
  `section`은 사람이 확인할 근거 위치다(`MODIFIED.diffSummary`와 같은 역할).
- text 엣지가 이미 있는 쌍은 제외한다(text 우선). 특히 `DESCRIBED_IN`의 text/semantic 구분은
  §2-5의 답변 규약을 따른다.
- `graph/postprocess.py`의 `run_postprocess_sequence`에 단계를 추가하고, `verify=true`의 clear
  대상에도 넣는다(`source='semantic'` 스코프 — §2-7).
- `GraphBuildResult`에 카운터 필드 추가 → **프론트 `types/graph.ts`의 `GraphBuildResult`도 함께**
  (안 고치면 타입 불일치).

**LLM 검수(`verify=true`) 빌더는 Phase 1에서 만들지 않는다.** 자동구축(임베딩만) 경로만 붙이고,
필터형 검수는 eval로 false positive 비율을 본 뒤에 판단한다 — 없다고 기능이 깨지지 않는다.

### 6-3. 삭제 경로

`graph/maintenance.py`의 `delete_project_source_graph`는 **코드 변경 없이 동작한다**(§2-8).
단위 테스트(`tests/unit/test_delete_source_graph.py`)에 Document·DocumentSection 케이스를 추가해
고정한다.

### 6-4. 질의 도구

- `tools/queries/document.py`(신규) + `tools/queries/__init__.py` facade re-export.
  - `get_document_context(project_id, document_id)` — 문서 본문·작성자·편집자·연결된 이슈/커밋/대화.
    `DESCRIBED_IN`은 `source`·`confidence`·`section`도 반환해 명시 참조와 추론을 구분한다.
  - `search_documents(project_id, query)` — `doc_section_embedding` 벡터 인덱스 시맨틱 검색.
    **매칭은 섹션, 반환은 문서 + 매칭 섹션 텍스트**(LLM이 읽을 근거가 문서 전체면 컨텍스트를
    통째로 잡아먹는다).
- `tools/definitions.py`에 도구 2개 추가, `tools/executor.py` 디스패치 추가.
- `tools/queries/explore.py` — `NODE_LABELS`에 `Document`·`DocumentSection`, `REL_TYPES`에
  `EDITED`·`PART_OF`·`DESCRIBED_IN` 추가. **`run_graph_query` 스키마 설명문에도 노드·관계를 적는다** —
  안 적으면 LLM이 Document의 존재를 모른다(허용 목록만 늘리는 건 반쪽이다).
  `DocumentSection`은 허용하되 **설명문에서 "본문은 Document.body를 보라"고 안내**한다(섹션을 직접
  RETURN하면 embedding 속성이 컨텍스트를 잡아먹는다 — `_STRIPPED_KEYS`가 막지만 텍스트도 길다).
- `agent/orchestrator.py` 시스템 프롬프트에 문서 노드의 존재와 "설계 근거를 물으면 문서를 먼저
  본다"를 추가.
- `check_missing_context`에 "문서가 하나도 없음" 신호 추가 여부는 선택 — **넣지 않는다**(문서 연동이
  없는 프로젝트가 정상이라 노이즈가 된다).

### 6-5. 그래프 뷰

- `graph/overview.py`
  - `_CONTENT_TYPE_PREDICATES`에 `"doc": "n:Document"` 추가, `_ALL_CONTENT_PRED`에 `n:Document` 추가.
  - `_to_graph_node`에 `Document` 분기 — `type: "doc"`, `title`, `meta`(부모 경로 또는 날짜),
    `source: "notion"`, `snippet`(body 앞부분), `ref: {type:"document", id: external_id}`.
  - **`DocumentSection`은 뷰에서 제외한다** — `_ALL_CONTENT_PRED`에도 `_ALL_EXPANSION_PRED`에도
    넣지 않는다. 내부 검색 단위지 사용자가 볼 개체가 아니다.
  - `_WORK_UNIT_LABELS`(`PullRequest`·`Issue`·`ChangeSet`)에는 **넣지 않는다** — 문서는 작업 단위가
    아니라 맥락이라 성좌의 별성이 아니라 주변 노드다.
- `tests/unit/test_import_surface.py`의 `MODULES`에 신규 모듈 등록,
  `tests/unit/test_api_routes.py`는 라우트를 추가하지 않으면 무변경.

---

## 7. web-dashboard — **"한 줄"이 아니다**

다른 커넥터는 `sourceCatalog` 한 줄이 전부였지만, Notion은 **새 노드 타입이 생기므로 그래프
쪽 작업이 함께 있다.** 「커넥터 엔드투엔드 체크리스트」 3단계가 이 커넥터에는 부족하다는 뜻이라,
아래를 별도로 챙긴다.

- [ ] `types/graph.ts` — `GraphNodeType`에 `"doc"` 추가 + `NODE_TYPE_INFO`에
      `doc: { label: "Document", cssVar: "var(--node-doc)" }`.
      **`NODE_TYPE_INFO`는 `Record<GraphNodeType, …>`라 유니온에 추가하는 순간 컴파일이 깨진다** —
      의도된 안전망이다.
- [ ] `NodeRef["type"]`에 `"document"` 추가(채팅 첨부 칩 → focus evidence 경로).
- [ ] `styles/tokens.css`에 `--node-doc` 신규 토큰. **`docs/DESIGN.md`를 먼저 읽고 팔레트에서
      파생시킨다** — 기존 7색과 구분되면서 같은 계열이어야 한다. 다크·라이트 양쪽 값을 정한다.
- [ ] `GraphBuildResult`에 신규 카운터 필드(§6-2).
- [ ] `sourceCatalog.tsx` — `notion` 항목을 `status: "wired"`로, `connect: "oauth"`,
      `deletedData: "수집한 Notion 페이지 본문·섹션과 그 그래프 연결"`.
      `consentSideEffect`는 비워 둔다(동의만으로 Notion 쪽에 남는 것이 없다).
- [ ] **`pages/PrivacyPage.tsx` — 배포 기준이다.** 세 곳을 함께 고친다:
      제1조 「연동 자격증명」 행(Notion access token + refresh token),
      제1조 「연동으로 수집되는 기록」 목록(페이지 제목·본문·작성자·편집자),
      제2조 `LegalSourceBlock`(앵커 `#notion`) — 요청 capability, 수집 정보, 이용 목적, 삭제,
      쓰기 권한 없음. **이름·이메일을 `GET /v1/users`로 얻는다는 것까지 밝힌다**(Google Chat이
      People API를 밝히는 것과 같은 수준).
      **여기에 "선택한 페이지의 하위 페이지도 함께 수집된다"를 반드시 적는다**(§1-3).
- [ ] 검증: `npm run typecheck && npm run build`

---

## 8. 개인정보 — partial user 함정 (Google Chat 사건의 재판)

**Notion의 `created_by`·`last_edited_by`는 partial user다 — `{object: "user", id: "..."}` 뿐이고
이름도 이메일도 없다.** 공식 Page 객체 레퍼런스로 확인했다(§12-3).

Google Chat에서 `sender.displayName`이 안 와서 People API 보강을 붙였던 것과 **정확히 같은
함정**이며, 놓치면 증상도 같다 — 수집은 정상으로 끝나는데 모든 Actor의 이름이 조용히 null이 된다.

**해법: `GET /v1/users` 전량 조회 + 캐시.** Google Chat과 달리 **조직 전체를 한 번에 내려주는
API가 있으므로** People API처럼 등장한 id만 지연 조회할 필요가 없다.

- 수집 실행 시작에 `GET /v1/users`를 페이지네이션으로 전량 받아 `{id → (name, email, type)}` 맵을
  만든다(Slack의 `users.list` 전량 캐싱과 같은 형태). TTL 캐시
  (`app.notion.user-cache-ttl`, 기본 30분).
- 맵에 없는 id(게스트·삭제된 사용자 등)는 `GET /v1/users/{id}` 단건으로 폴백하고, 그것도 실패하면
  이름·이메일 null로 두고 **캐시하지 않는다**(다음 실행에서 재시도).
- `type == "bot"` → `actor.bot = true`. Notion 자동화·다른 연결이 만든 페이지가 사람 Actor로
  섞이지 않게 한다.
- 게스트 계정은 `person.email`이 없을 수 있다 → email null 허용(계약대로).

**403은 삼킨다.** capability(§1-1)가 꺼진 워크스페이스에서는 `/v1/users`가 403이다. 여기서 예외를
전파하면 **capability 설정 하나 때문에 수집 전체가 0건**이 된다 — Google Chat의 People API 403
처리와 같은 규약으로, warn 로그 후 빈 맵을 반환하고 이름·이메일 없이 수집을 계속한다.
429는 재시도 상한 소진 후 예외로 전파한다(지속적 rate limit은 조용히 넘길 문제가 아니다).

이름·이메일은 기존대로 `ActorAlias.pd_*`에만 저장한다. Atlassian식 개인정보 보고 의무는 없다.

---

## 9. 문서 동반 갱신

`Document`가 신규 노드라 **갱신할 문서가 다른 커넥터보다 훨씬 많다.**

- `docs/graph-schema.md` — Document·DocumentSection 노드 절(“_(미래)_” 제거), 복합 유니크 키 표,
  관계 목록 7행, mermaid 다이어그램(점선 → 실선), Layer 표, 삭제 cascade 절, `REFERENCE`와
  `DESCRIBED_IN`의 `source: text|semantic`·`confidence`·`section` 속성(§2-5·§2-7).
  **예약돼 있던 `DESCRIBED_IN` 행의 설명("Actor가 문서에 기술됨")은 방향과 어긋나므로 함께 고친다.**
- `docs/normalized-event.md` — 봉투의 nodeType 열거, Document properties 절, `refs.editors`
  누적 규약, `documentExternalRefs`, 새 커넥터 체크리스트 1번의 "문서(`Document`, 미구현)" 표기 수정.
- `docs/embedding-design.md` — 임베딩 대상 표에 `DocumentSection.embedding` 행, 청킹 규칙, 새 임계값.
- `docs/tools.md` — 신규 도구 2종.
- `docs/data-collection.md` — Notion 절(수집 대상·정렬 기반 증분·checkpoint 규칙(§5-2)·rate
  limit·트레이드오프).
- `docs/integration-abstraction.md` — Part B 표의 Notion 완료 표시, §3-1의 `RefsExtractor`
  레지스트리화 완료 표시, §3-3의 "Notion `Document` 노드는 별도 설계 단계" 항목 해소.
- `services/ai-engine/CLAUDE.md`(패키지 구조에 `document_chunker`·`document_linker`·
  `tools/queries/document.py`) · `services/backend/CLAUDE.md`(`notion` 패키지, SPI 2종) ·
  `services/pipeline-worker/CLAUDE.md`(`source.notion` 행) · `clients/web-dashboard/CLAUDE.md`
  (Privacy 앵커 목록에 `#notion`).
- `docs/DB.md` — 변경 없음(새 테이블 없음)을 확인만.

---

## 10. 진행 순서 — 1 PR 규칙의 예외

커넥터 1개 = 1 PR이 원칙이지만, Notion은 ai-engine 신규 설계가 커넥터보다 커서 **4개로 나눈다.**
N1과 N2는 §3 계약이 확정된 뒤라면 **병렬 가능**하다.

| PR | 범위 | 동작 변화 | 검증 |
|----|------|----------|------|
| **N0** (선행, 공용) | `REFERENCE`에 `source: text\|semantic` 도입, `clear_reference`를 semantic 스코프로 축소(§2-7) | **없음** — 기존 엣지는 `coalesce(r.source,'semantic')`로 전부 기존 동작 | `pytest` + 정밀 재구축이 text 엣지를 남기는 회귀 테스트 |
| **N1** (ai-engine) | Document·DocumentSection 노드, 제약·벡터 인덱스, `_handle_document`, 청킹, Layer 2 엣지(`DESCRIBED_IN source='text'`), 삭제 cascade 테스트 | 새 nodeType 소비 가능 (아직 발행하는 곳이 없음) | `pytest` — 가짜 Document 이벤트 주입으로 전 경로 검증 |
| **N2** (backend + pipeline-worker) | 연결(OAuth·폐기)·수집(search·블록 평문화·users 보강) | 실제 그래프가 그려진다 | 양쪽 `./gradlew test` + 실기동 |
| **N3** (ai-engine Layer 4 + 도구 + 프론트) | 시맨틱 링크 2종(`source='semantic'`), 질의 도구 2종, 성좌·타입·PrivacyPage | 문서가 질의·시각화에 노출된다. 답변은 명시 참조와 추론을 구분한다 | `pytest` + `npm run typecheck && npm run build` |

**N0를 먼저 하는 이유**는 A8·A9와 같다 — 공용 코드의 구멍을 커넥터 PR에 섞으면 리뷰가 뒤엉키고,
무엇보다 **N1 이후에 발견하면 이미 만들어진 text 엣지가 한 번 날아간 뒤**다.

**N1을 N2보다 먼저 놓는 이유**: 소비자가 없는 발행은 검증할 수 없지만, 발행자가 없는 소비는
가짜 이벤트로 완전히 검증할 수 있다. 반대 순서면 N2 담당자가 "발행은 되는데 그래프에 아무것도
안 생긴다"를 며칠 본다.

---

## 11. 검증 계획

- **단위**: backend `./gradlew test` · pipeline-worker `./gradlew test` · ai-engine `pytest` ·
  프론트 `npm run typecheck && npm run build`.
- **N0 회귀**: 정밀 재구축(`verify=true`) 후 `source='text'` REFERENCE가 살아남는지.
  → 이 테스트가 없으면 N0는 아무것도 보장하지 않는다.
- **checkpoint 회귀(§5-2)**: 2페이지짜리 내림차순 응답에서 checkpoint가 **전체 최댓값으로 한 번만**
  갱신되는지. → 이 커넥터에서 가장 사고 나기 쉬운 지점이라 반드시 고정한다.
- **청킹 단위 테스트**: heading 경계 분할, 1,500자 초과 재분할, 200자 미만 병합, heading 없는 문서,
  빈 문서. 순수 함수라 값싸게 촘촘히 덮는다.
- **섹션 전량 교체**: 문서를 재수집하면 옛 섹션이 남지 않는지, Document에 걸린 엣지는 살아남는지.
- **partial user 회귀(§8)**: `/v1/users` 403에도 수집이 계속되는지(이름·이메일 null), `type=bot`이
  `actor.bot=true`로 오는지.
- **URL → id 정규화**: 하이픈 없는 32자리 hex URL이 하이픈 UUID `external_id`와 매칭되는지
  (여기가 틀리면 text 링크가 조용히 0건이 된다).
- **eval(`docs/measurement.md`)**: 신규 임계값 2종을 재스윕한다. **섹션 단위 비교는 통짜보다 점수가
  높게 나오므로 기존 값(0.44·0.48)을 그대로 쓰면 false positive가 는다** — 이 커넥터에서 eval은
  선택이 아니라 필수 단계다.
- **실기동 시나리오**: 실제 Notion 공개 연결 등록 → 연결(페이지 피커에서 위키 최상위 선택) →
  초기 수집 → 그래프에 Document·DocumentSection·WROTE·CHILD_OF 확인 → 문서 본문에 적힌 이슈 키가
  `DESCRIBED_IN`으로 붙는지 → Slack에 Notion 링크를 붙여넣고 재수집해 `DISCUSSED_IN`이 붙는지 →
  문서를 편집하고 PR 머지 웹훅으로 증분(섹션 교체 확인) → 그래프 재구축으로 시맨틱 링크 →
  해제 시 NOTION 노드만 삭제되고 Notion 쪽 연결 권한도 사라지는지.

---

## 12. 확인 완료 (2026-08, 공식 문서 조사)

1. **OAuth 토큰 교환** — `POST https://api.notion.com/v1/oauth/token`, **Basic auth**(client_id:secret),
   `grant_type=authorization_code`. 응답: `access_token` · `token_type` · `refresh_token`(null 가능) ·
   `bot_id` · `workspace_id` · `workspace_name` · `workspace_icon` · `owner` · `duplicated_template_id`.
2. **동의 화면에 페이지 피커가 있다** — 사용자가 공유할 페이지·데이터베이스를 직접 고르고,
   고르지 않은 것은 API에 보이지 않는다. → 선택 단계 미구현 근거(§1-3).
3. **`created_by`·`last_edited_by`는 Partial User** — `object`·`id`만 온다(Page 객체 레퍼런스 명시).
   → §8 보강 근거. **계획 초안에서 가장 위험했던 가정이 여기서 뒤집혔다.**
4. **갱신 응답에 `expires_in`이 없다.** 그리고 갱신은 **회전형**(새 `refresh_token`을 준다).
   → `AccessTokenRefresher` 미구현 근거(§4-3).
5. **폐기 엔드포인트가 있다** — `POST /v1/oauth/revoke`, Basic auth + `{token}`.
6. **search는 시간 필터가 없고 정렬만 된다** — `sort.timestamp = "last_edited_time"` +
   `direction`. `filter.property="object"`의 값은 `"page"` \| `"data_source"`, `filter.in_trash`로
   휴지통 조회 가능. → 정렬 기반 증분(§5-1)과 §5-5의 Phase 2 수단.
7. **rate limit: 연결당 평균 3 req/s**, 429/529에 `Retry-After` 헤더 제공. 워크스페이스 단위 한도도
   별도로 있다(요금제 스케일).
8. **블록은 재귀 조회** — `GET /v1/blocks/{id}/children`, `has_children`으로 하위 존재를 판정.
   `child_page`의 children을 조회하면 **그 페이지 본문이 나온다** → 재귀하면 중복 수집(§5-3).
9. **capability로 권한이 갈린다** — 사용자 정보는 3단계(없음 / 이메일 제외 / 이메일 포함)이며,
   권한 없이 users API를 부르면 **403**이다.
10. **API 버전은 헤더로 가른다** — 최신 `2026-03-11`. 2025-09-03에서 database → data source
    비호환 변경이 있었다.

## 13. 구현 시 확인 (미확정 — 실기동에서만 알 수 있다)

1. **access token이 실제로 만료되는가.** 문서에 만료 정보가 전혀 없다(§12-4). 며칠 지난 토큰으로
   수집이 401을 맞는지 실기동으로 본다. 맞는다면 **반응형 갱신**(401 → refresh → 1회 재시도,
   회전한 refresh token 저장)을 붙인다 — 선제 갱신은 여전히 불가능하다.
2. **`search`의 eventual consistency 지연.** 새로 공유·생성한 페이지가 검색 결과에 뜨기까지의
   지연이 얼마인지. 길면 "연결 직후 수집 0건"으로 보여 사용자 혼란이 생기므로, 연결 완료 안내
   문구를 조정해야 할 수 있다.
3. **평문화 품질** — 실제 팀 위키(중첩 토글·표·칼럼)를 넣었을 때 `body`가 읽을 만한지.
   임베딩 품질이 여기 직결되므로 실제 문서 3~5개로 눈으로 확인한다.
4. **초기 수집 실제 소요** — §5-3의 추정(200페이지 ≈ 4~6분)이 맞는지. 크게 벗어나면 페이지 단위
   블록 조회를 제한적으로 병렬화할지 검토한다(**단 3 req/s 한도 안에서만** — 병렬화는 한도를
   늘려주지 않는다).
5. **`workspace_name`이 비어 오는 경우가 있는지** — 연동 행 표시 이름이 빈칸이 되면 폴백이 필요하다.
6. **`data_source` 하위 page의 `parent_type`** — 2025-09-03 이후 database 안 페이지의 부모가
   `database_id`로 오는지 `data_source_id`로 오는지. `CHILD_OF`를 걸지 않는 분기라 동작에 영향은
   없지만 `parent_type` 값 집합을 정확히 기록하려면 확인이 필요하다.

---

## 참고 (Notion API, 2026-08 조사 · `Notion-Version: 2026-03-11`)

- OAuth 토큰 교환: developers.notion.com/reference/create-a-token
- 토큰 갱신(회전·`expires_in` 없음): developers.notion.com/reference/refresh-a-token
- 토큰 폐기: developers.notion.com/reference/revoke-token
- 인가·페이지 피커: developers.notion.com/docs/authorization
- 검색(정렬·`in_trash`·object 필터): developers.notion.com/reference/post-search
- Page 객체(Partial User·`in_trash`·title property): developers.notion.com/reference/page
- 블록 자식 조회·`has_children`: developers.notion.com/reference/get-block-children
- 사용자 목록·단건: developers.notion.com/reference/get-users · /reference/get-user
- 연결 capability(사용자 정보 3단계·403): developers.notion.com/reference/capabilities
- rate limit·`Retry-After`·크기 상한: developers.notion.com/reference/request-limits
- 버전 관리: developers.notion.com/reference/versioning
- 2025-09-03 업그레이드(database → data source): developers.notion.com/docs/upgrade-guide-2025-09-03
