# docs 현황

`docs/`에 있는 문서가 **지금 읽히는 정본인지**, **계획이 코드까지 갔는지**, **열린 항목이 남았는지**를
한곳에 둔다. 개별 문서의 상세는 각 파일을 본다. 이 표가 어긋나면 이 파일을 고친다.

기준일: 2026-08-29. 코드와 문서 헤더를 대조한 결과다.

## 표시

| 칸 | 값 | 뜻 |
|----|-----|-----|
| **상태** | 완료 | 문서가 가리키는 구현이 코드에 있고, 그 문서가 추적하는 필수 항목이 닫혀 있다 |
| | 부분 | 코드는 있으나 실기동·측정·후속 TODO가 그 문서에 열려 있다 |
| | 미완 | 착수 전 |
| **필요** | **정본** | 그 영역을 바꾸면 **반드시 읽고 함께 고친다.** 지우면 안 된다 |
| | **작업** | 남은 항목을 진행할 때 읽는다 |
| | **근거** | 일상 정본은 다른 파일이다. 결정·실측이 여기만 있어 **유지는 한다** |

스펙(그래프·수집 계약·DB·디자인)은 "계획이 끝났다"가 아니라 **현행 정본**이다.

---

## 필요한 문서 — 정본

영역을 만질 때 이 문서가 단일 출처다. CLAUDE.md 참고 문서도 여기를 우선한다.

| 언제 | 문서 |
|------|------|
| 그래프 노드·엣지 | [graph-schema.md](graph-schema.md) |
| 수집 계약·새 커넥터 필드 | [normalized-event.md](normalized-event.md) |
| 소스별 수집·checkpoint | [data-collection.md](data-collection.md) |
| 새 소스 추가 순서 | [integration-abstraction.md](integration-abstraction.md) |
| PostgreSQL 스키마 | [DB.md](DB.md) |
| UI 색·타이포·모션·랜딩 | [DESIGN.md](DESIGN.md) |
| LLM 도구 계약 | [tools.md](tools.md) |
| Actor 동일인 판단 | [actor-node-design.md](actor-node-design.md) |
| Actor 수동 병합·분리 | [actor-manual-merge.md](actor-manual-merge.md) |
| 임베딩·Layer 4 | [embedding-design.md](embedding-design.md) |
| eval | [measurement.md](measurement.md) |
| 질의 품질 회귀 | [query-quality-issues.md](query-quality-issues.md) |
| 배포 | [deployment.md](deployment.md) |
| 공개 전환 | [public-readiness.md](public-readiness.md) |
| Jira 개인정보 보고 | [jira-personal-data-policy.md](jira-personal-data-policy.md) |
| 시각 표시·언어 계약 | [i18n.md](i18n.md) |
| PR 리뷰 역할 | [pr-review-guide.md](pr-review-guide.md) |

커넥터 고유 설계(Discord·Google Chat·Notion·Teams)는 그 소스를 만질 때만 정본에 가깝다.
수집 전략의 일상 정본은 [data-collection.md](data-collection.md)다.

**이 표에 없다고 지우면 안 된다.** 아래 표의 **작업**(남은 TODO)과 **근거**(결정·실측이 여기만 있음)도
유지한다. 랜딩 카피·섹션 규칙은 [DESIGN.md](DESIGN.md) 「랜딩 페이지」 절이 정본이다.

---

## 완료 — 현행 정본·닫힌 런북

| 문서 | 필요 | 종류 | 하는 일 |
|------|------|------|---------|
| [graph-schema.md](graph-schema.md) | 정본 | 스펙 | 지식 그래프 노드·관계 |
| [normalized-event.md](normalized-event.md) | 정본 | 스펙 | pipeline-worker ↔ ai-engine 수집 계약 |
| [data-collection.md](data-collection.md) | 정본 | 스펙 | 소스별 수집·정규화·checkpoint |
| [DB.md](DB.md) | 정본 | 스펙 | PostgreSQL 테이블 (Flyway) |
| [DESIGN.md](DESIGN.md) | 정본 | 스펙 | 팔레트·타이포·모션·랜딩 |
| [tools.md](tools.md) | 정본 | 스펙 | LLM tool-calling 계약 |
| [measurement.md](measurement.md) | 정본 | 런북 | GraphRAG eval 방법 |
| [deployment.md](deployment.md) | 정본 | 런북 | 실사용 배포 절차 |
| [jira-personal-data-policy.md](jira-personal-data-policy.md) | 정본 | 운영 | Jira 개인정보 보고·봇 계정 등록 |
| [pr-review-guide.md](pr-review-guide.md) | 정본 | 프로세스 | 봇 리뷰 + 사람 리뷰 역할 |
| [actor-node-design.md](actor-node-design.md) | 정본 | 스펙 | Actor 동일인 판단 |

---

## 부분 — 코드는 있고, 문서에 열린 항목이 있다

| 문서 | 필요 | 종류 | 된 것 | 남은 것 |
|------|------|------|--------|---------|
| [discord-integration.md](discord-integration.md) | 근거 | 커넥터 계획 | 코드 + 연결·초기 수집 실기동 | 웹훅 증분, 해제 시 봇 퇴장, 아카이브 스레드·포럼 |
| [google-chat-integration.md](google-chat-integration.md) | 근거 | 커넥터 계획 | 코드 + 연결·스페이스 선택·초기 수집 | **PR 머지 웹훅 증분·1시간 토큰 갱신은 안 함.** 스레드 `conversation_id`, §12 |
| [notion-integration.md](notion-integration.md) | 근거 | 커넥터 계획 | N0~N3 코드 | §13 실기동, Phase 2 삭제·아카이브 |
| [integration-abstraction.md](integration-abstraction.md) | 정본 | 추상화 계획 | Part A, Linear·Asana·ClickUp·Discord·Google Chat·Notion | monday.com 미착수, Teams 보류. Part B Notion 행이 코드와 어긋남(N3 남음으로 적힘) |
| [actor-manual-merge.md](actor-manual-merge.md) | 정본 | 스펙 | 병합·unmerge·split·프론트 | 봇 플래그 미승계 |
| [embedding-design.md](embedding-design.md) | 정본 | 스펙 | 임베딩·벡터 인덱스·Layer 4 | refs 없는 시맨틱 엣지, REFERENCE vector index는 미채택 |
| [graph-query-tool.md](graph-query-tool.md) | 근거 | 설계 | 코드 + 라우팅 확인 런 | 품질 측정 미실시. 계약의 정본은 [tools.md](tools.md) |
| [timeline-scope.md](timeline-scope.md) | 근거 | 설계 | 스코프 일반화, eval 채택 | `get_recent_activity` 통합, 시간 인덱스. 계약의 정본은 [tools.md](tools.md) |
| [query-followups.md](query-followups.md) | 작업 | TODO | LLM 빈 답 위장 수정 | Responses API, README→Document, 랭킹·인용 |
| [i18n.md](i18n.md) | 정본 | 계약 | 시각 표시 계약, 랜딩 ko/en | 앱 UI 언어 분리 착수 전 |
| [deployment-followups.md](deployment-followups.md) | 작업 | TODO | 인바운드 인증 | RabbitMQ URL 분리, 터널 실기동, GitHub 설치 404 |
| [public-readiness.md](public-readiness.md) | 정본 | 점검표 | 0~2층·4층 다수 | 0-1b, 4-5 내보내기, Slack, 3층 심사, 5층 운영, 2-4 |
| [query-quality-issues.md](query-quality-issues.md) | 정본 | 분석 로그 | 케이스별 원인·개선안 | 닫히지 않음 |

---

## 미완 — 착수 전

| 문서 | 필요 | 종류 | 남은 것 |
|------|------|------|---------|
| [teams-integration.md](teams-integration.md) | 작업 | 커넥터 계획 | 착수 보류. `TeamsCollector` 없음. Teams를 붙일 때 정본이 된다 |
| [slack-marketplace.md](slack-marketplace.md) | 작업 | 등재 계획 | 착수 전. `/why-code` 슬래시 커맨드·Events API 라이프사이클·제출물 — public-readiness 0-3 D 트랙의 실행 계획 |

---

## 어디에 무엇이 있는지

새 커넥터: [integration-abstraction.md](integration-abstraction.md) → [normalized-event.md](normalized-event.md) → 해당 `*-integration.md` → [data-collection.md](data-collection.md).

질의·도구: [tools.md](tools.md). 품질은 [query-quality-issues.md](query-quality-issues.md)·[measurement.md](measurement.md).

배포·공개: [deployment.md](deployment.md) → [deployment-followups.md](deployment-followups.md) → [public-readiness.md](public-readiness.md).
