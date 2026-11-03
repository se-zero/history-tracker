# 임베딩 대체 실험 — text-embedding-3-large → 파인튜닝한 오픈 모델

> **상태: 진행 중** (브랜치 `feat/embedding-finetune`, 2026-11-03 착수). 목적은 **실험**이다.
> 프로덕션 설정은 바꾸지 않고, 대체가 안 된다는 결과도 그대로 기록한다.
> 측정 방법의 정본은 [measurement.md](measurement.md), 임베딩 구조의 정본은 [embedding-design.md](embedding-design.md)다.

## 1. 답할 질문과 기준선

**"공개 데이터로 파인튜닝한 오픈 임베딩 모델이 `text-embedding-3-large`(1536차원 절삭)를 대체할 수 있는가?"**

임베딩을 고른 이유는 개선 여지가 이미 측정돼 있기 때문이다. 자동 구축된 시맨틱 엣지의 40% 넘게가 틀린다.
코드 변경 요약 문체와 구어체 대화는 같은 내용이어도 임베딩 공간에서 멀리 떨어진다([embedding-design.md](embedding-design.md)).
그 간극을 우리 도메인의 쌍으로 직접 학습시키는 것이 파인튜닝의 몫이다.

기준선(`eval/results/edge-2026-07-20-3large-tuned.json`, 라벨 257쌍·골든 23쌍):

| | 전체 | REFERENCE | DISCUSSED_IN | TRIGGERED_BY |
|---|---|---|---|---|
| precision | 0.576 | 0.487 | 0.756 | 0.500 |
| recall | 0.913 (21/23) | 13/14 | 6/6 | 2/3 |

## 2. 전제

1. **학습 데이터는 허용 라이선스(MIT·Apache·BSD) 공개 GitHub 저장소만 쓴다.**
   - 고객 데이터는 쓰지 않는다. Slack 심사 제출 문안이 "we do not fine-tune or train any model on Customer Data"다([slack-marketplace.md](slack-marketplace.md)).
   - 팀 스냅샷(`eval/snapshots`)은 **평가 전용**이다. 학습에 섞으면 시험 문제를 미리 보여 주는 셈이 되고, "처음 보는 프로젝트에서도 통하는가"를 잴 수 없다. 고객 데이터로 학습할 수 없는 서비스에서는 그 조건이 곧 실제 운영 상황이기도 하다.
   - GitHub 이용 정책은 공개·비개인 정보의 연구 목적 사용을 허용한다(결과를 출판하면 오픈 액세스 조건). 그래서 작성자 정보는 저장하지 않는다.
2. **OpenAI가 만든 글은 학습 데이터로 쓰지 않는다.** 약관에 "Output으로 OpenAI와 경쟁하는 모델을 개발하지 않는다"는 조항이 있다. gpt-4o-mini의 diff 요약이나 GPT로 합성한 쌍은 쓰지 않는다. 필요하면 GPU에서 돌린 오픈 LLM의 글만 쓴다.
3. **환경변수를 넣지 않으면 지금과 똑같이 동작한다.** ai-engine 변경은 전부 선택 사항이다.

## 3. 실행 환경

| 위치 | 사양 | 하는 일 |
|---|---|---|
| Mac | Apple M4, 메모리 16GB, MPS. 임베딩용 venv는 Python 3.11(`eval/embedding_finetune/.venv`) | 그래프·eval·후보 비교·학습 데이터 수집·임베딩 서버·풀스택 측정 |
| 학교 GPU (gfarm, AstraGo) | 인터랙티브 잡. 이미지 `vs-code:xiilab/astrago:pytorch-23.07-cuda12.1`. GPU MIG 3g.40gb(80GB GPU의 3/7 조각, 메모리 40GB). 저장은 모델 경로 `whycode`(NFS)를 `/mnt/models`에 붙인다 | **학습만** |

- **연결 방식:** GPU 잡 주소는 학교 내부망(`10.10.x.x`)이라 학교 밖 Mac에서는 닿지 않는다(2026-11-03 확인). 그래서 GPU 쪽은 웹 터미널에서 명령을 치고, 파일은 업로드·다운로드로 주고받는다.
- **GPU 쪽 작업 단위:** 파일 3개를 올리고(스크립트 2개, 데이터 1개), 명령 한 줄을 실행하고, 결과 압축 파일 하나를 내려받는다.
- **GPU 환경:** 이미지의 Python 3.10·PyTorch 2.1(NGC 23.07판)은 쓰지 않는다. transformers 5.x가 Python 3.11 이상·PyTorch 2.6 이상을 요구하기 때문이다. 대신 `/mnt/models/venv`에 Python 3.12와 PyTorch 2.6 이상을 설치한다. 잡은 학습할 때만 켠다.

## 4. 흐름과 관문

비용이 큰 단계로 가기 전에 멈출 수 있게 관문을 넷 둔다.

```
0 기준선 고정 → 1 파인튜닝 없는 후보 비교 ─관문1→ 2 데이터·파인튜닝 ─관문2→ 3 ai-engine 스위치
  → 4 풀스택: 엣지 ─관문3→ 답변 품질 ─관문4→ 5 결과 기록
```

| 관문 | 판정 | 통과하지 못하면 |
|---|---|---|
| 1 | 1차 지표(REFERENCE·DISCUSSED_IN AUC) 상위 1~2개를 파인튜닝 베이스로 고른다 | — |
| 2 | 파인튜닝 모델·베이스·3-large를 같은 지표로 비교한다 | 둘 다 3-large보다 확실히 낮으면(AUC 차이 95% 구간 상한 < 0) 음의 결과로 끝낸다 |
| 3 | 같은 골든 recall에서 균등 표본 precision을 비교한다 | 3-large보다 확실히 낮으면 끝낸다. 차이 0.12 안쪽은 "구분 불가" |
| 4 | e2e 골든셋의 `AGGREGATE_FLOOR`를 넘는 악화가 없는지 본다 | 악화가 있으면 "대체 불가"로 기록한다 |

## 5. 단계별 방법

### 0단계 — 기준선 고정

- 7월 덤프는 7월 코드로 만든 것이라, 그대로 비교하면 그 사이 코드 변경까지 모델 효과로 착각한다.
- 그래서 라벨 파일이 가리키는 `graph-2026-07-21.dump`(pid `eb74cbd9…`)를 로드한다.
- [measurement.md](measurement.md) §4.5 (a) 체인을 현행 임계값으로 다시 돌려 **현재 코드 기준선**과 엣지 수를 기록한다.
- `validate_golden.py`로 깨지는 골든 케이스를 제외 집합으로 확정해 이후 모든 비교에 똑같이 적용한다.
- **7월 덤프와 현재 코드의 차이 하나:** 현재 코드는 이슈를 `"{source}:{external_id}"`로 찾는다. 이 방식은 커밋 `723db829`(Issue 계약 소스 중립화)에서 생겼다. 7월 덤프의 이슈 노드에는 `external_id`가 없어서 `/issue-links/build`가 500으로 실패한다. 옛 노드를 채우는 마이그레이션은 없다.
  - 처리: 실험 그래프에서만 Jira 이슈의 `external_id`를 `issue_key`로 채웠다(95건). 7월 빌더가 `issue_key`로 이슈를 찾던 대응을 복원한 것이다.
  - 덤프 원본은 그대로 두었다. 패치한 뒤 체인까지 돌린 상태를 실험 기준 덤프 `eval/snapshots/exp-base-2026-11-03.dump`로 저장했다. 이후 기준선으로 돌아갈 때는 이 덤프를 로드한다.
  - `status_category`도 없지만 영향은 없다. `closedAt`이 없을 때만 쓰는 대체값인데, 완료 이슈 93건에는 `closedAt`이 모두 있다.

### 1단계 — 파인튜닝 없는 후보 비교 (Mac)

| 후보 | 차원 | 입력 상한 | 라이선스 | 메모 |
|---|---|---|---|---|
| `BAAI/bge-m3` | 1024 | 8,192 | MIT | 접두어 불필요 |
| `nlpai-lab/KURE-v1` | 1024 | 8,192 | MIT | bge-m3를 한국어로 파인튜닝한 모델 |
| `Qwen/Qwen3-Embedding-0.6B` | 32~1024 | 32K | Apache-2.0 | 질의 쪽 지시문 권장 |
| `kakaocorp/kanana-2-1.3b-embedding` | 1280 | 4,096 | Kanana Open License | `trust_remote_code` 필요. 리비전을 고정한다 |

- **임베딩 서버** `eval/embedding_finetune/serve.py`:
  - OpenAI 형식의 `POST /v1/embeddings`를 연다. 그래서 eval 스크립트와 ai-engine이 코드 대부분을 그대로 쓴 채 모델만 바꿔 잴 수 있다.
  - 한 프로세스에 모델 하나만 올리고, 요청 모델 이름이 다르면 거절한다. 엉뚱한 라벨로 결과가 기록되는 것을 막기 위해서다.
- **`eval/precheck_embedding_model.py`:** 후보별 `base_url`과 `--only`를 받는다. 재현 검증은 3-large 점수와 현재 그래프 confidence를 대조한다.
- **`eval/precheck_compare.py`:** AUC 차이의 신뢰구간을 낸다. 쌍들이 스레드를 공유하므로 도착 노드 단위로 복원 추출하는 부트스트랩이다.
- **`eval/precheck_retrieval.py`:** 질문으로 검색했을 때 골든 근거가 상위 5·20개에 드는 비율(Recall@k)을 잰다. 실제 도구는 LLM이 고른 키워드와 HNSW를 쓰므로 근사치다.

**판정 기준(미리 고정):**
- 1차 지표는 REFERENCE AUC와 DISCUSSED_IN AUC다. AUC는 관련 쌍과 무관 쌍을 하나씩 뽑았을 때 관련 쌍 점수가 더 높을 확률이다.
- 동일 골든 recall에서의 precision과 검색 Recall@5는 2차 지표다.
- TRIGGERED_BY는 라벨이 15쌍뿐이라 참고만 한다.

### 2단계 — 학습 데이터(Mac)와 파인튜닝(GPU)

| 쌍 | 앵커 | 짝 | 대응 엣지 |
|---|---|---|---|
| 변경↔대화 | 커밋 메시지 또는 PR 제목 | 같은 PR의 리뷰 댓글 | REFERENCE |
| 변경↔이슈 | 커밋 메시지 또는 PR 제목 | PR이 닫은 이슈 제목+본문 | TRIGGERED_BY |
| 이슈↔대화 | 이슈 제목+본문 | 그 이슈의 댓글 | DISCUSSED_IN |

- **출처:** 허용 라이선스 저장소인 next-step 36개, woowacourse 34개, woowacourse-teams 5개, daangn 55개, toss 25개다(2026-11-02 조회). 예를 들어 `next-step/atdd-subway-admin`(MIT)에는 리뷰 댓글 11,237건이 있고, 최근 50건 중 49건이 한국어다.
- **정제:**
  - 봇과 20자 미만·칭찬뿐인 댓글을 뺀다.
  - `#123`·`HT-12`·URL·`@멘션`·커밋 해시·이메일을 가린다. 같은 문자열로 맞히는 법을 배우면, 정작 키가 없는 쌍에는 소용이 없기 때문이다.
  - 중복을 제거하고, PR당·저장소당 상한을 둔다.
- **분할:** 저장소 단위로 나눈다. 운영 데이터와 가장 닮은 `woowacourse-teams/2022-dallog`·`2023-hang-log`는 검증 전용이다.
- **학습:** Sentence Transformers의 `MultipleNegativesRankingLoss`로 학습한다(한 배치 안에서 짝은 가깝게, 나머지는 멀게). 설정은 `NO_DUPLICATES` 샘플러, bf16, lr 2e-5, 1~3 에폭이다.
  - 변형은 최대 3개이고, 고르는 기준은 공개 검증셋 NDCG@10뿐이다.
  - 팀 라벨로 채점하는 것은 고른 1~2개뿐이다. 같은 라벨을 여러 번 보면 그 라벨에 과적합된다.
- 데이터와 모델은 `eval/embedding_finetune/data|models/`(gitignore)와 GPU의 `/mnt/models`에만 둔다. 외부 업로드는 하지 않는다.

### 3단계 — ai-engine 스위치 (관문 2 통과 시에만)

`OPENAI_BASE_URL`은 클라이언트가 하나라 chat까지 함께 옮겨 간다. 그래서 임베딩만 다른 서버로 보내는 스위치를 둔다.

| 파일 | 변경 |
|---|---|
| `openai_client.py` | `get_embedding_client()` 추가. `EMBEDDING_BASE_URL`이 비면 기존 클라이언트를 그대로 쓴다 |
| `graph/embedder.py` | `EMBEDDING_MODEL`·`EMBEDDING_DIMENSIONS`(기본값 현행) |
| `graph/schema.py` | 벡터 인덱스 차원을 같은 값으로 만든다. 실제 인덱스 차원과 어긋나면 기동을 실패시킨다 |
| `routers/query.py` | `/query/config`에 임베딩 모델·차원 추가. eval 러너가 meta에 자동 기록한다 |

**지금 코드가 조용히 틀리는 지점(조사 결과):**
- 벡터 인덱스는 `IF NOT EXISTS`라 차원이 바뀌어도 옛 인덱스가 남는다.
- Neo4j는 설정 차원과 다른 벡터를 색인하지 않고, 다른 차원으로 질의하면 오류를 낸다.
- 차원이 같은 신·구 벡터가 섞이면 오류 없이 무의미한 점수가 나온다.

### 4단계 — 풀스택 측정 (Mac)

같은 그래프·같은 코드·같은 diff 요약에서 **임베딩만 바꿔** 비교한다.
1. **교체:** 기준 덤프(`exp-base-2026-11-03.dump`)에서 시작한다. 벡터 인덱스 DROP → 재기동 → `force=true` 재임베딩 4종 → 4종 모두 `saved == total` 확인. 임베딩 서버는 요청을 하나씩 처리하므로 재임베딩 라우트도 하나씩 부른다(ai-engine 클라이언트 타임아웃 60초). 이벤트 재주입은 쓰지 않는다. 요약 LLM이 다시 돌면 변수가 둘이 되기 때문이다.
2. **임계값 재조정:** 점수 척도가 통째로 바뀌므로 숫자를 그대로 쓰면 비교가 무효다. 7월 교체 때 임계값을 그대로 두자 REFERENCE 엣지가 594→1,315개로 늘었다(커밋 `1992dcb9`).
   - 세 엣지 임계값은 골든 히트(13/14·6/6·2/3)를 유지하는 지점으로 맞춘다.
   - `discussed_in_margin`은 점수 분산 비율로 환산한다.
   - `TOOLS_MIN_CONFIDENCE`는 기준선에서 통과하던 TRIGGERED_BY 비율과 같아지게 맞춘다.
   - 검색 `threshold=0.30`은 그대로 둔다. Neo4j 점수가 `(1+cos)/2`라 사실상 필터가 아니기 때문이다.
3. **균등 표본 라벨링:**
   - 기존 라벨 257쌍은 과거 모델의 엣지에서 뽑은 것이라, 새 모델의 오탐을 보지 못한다.
   - 그래서 두 그래프에서 균등 표본을 뽑고(`sample_edges.py --uniform --per-type 40 --reuse-labels --blind`), 새 쌍만 섞어 모델과 점수를 숨긴 채 라벨링한다(`label_queue.py`).
   - 표본 파일에는 `--graph-snapshot`으로 그래프 이름을 남긴다. 기준선은 `exp-base-2026-11-03.dump`, 후보는 `exp-base-2026-11-03.dump+<모델 라벨>`이다.
4. **답변 품질:** 관문 3을 통과하면 기준선과 후보를 같은 커밋에서 runner 3회 → grader → compare 순으로 돌린다.
5. **복구:** 끝나면 env를 제거하고 백업한 그래프를 복원한다.

### 5단계 — 결과 기록

관문별 수치와 원자료 경로를 이 문서 §8과 `eval/improvement-log.md`에 남긴다. 실제 전환 여부는 별도로 결정한다.
전환한다면 남는 과제는 다음과 같다: CPU·GPU 서빙 구성, `DocumentSection` 백필 경로(지금 없음), 문서 엣지 임계값 재조정, 정책 문구 갱신.

## 6. 하지 않는 것

| 항목 | 이유 |
|---|---|
| 프로덕션 전환, 빌더 임계값 상수 변경 | 목적이 실험이다. 임계값은 admin 파라미터로만 주입한다 |
| 팀·고객 데이터로 학습, OpenAI 출력으로 학습 | §2 |
| 라이선스 없는 저장소 | 허용 라이선스만으로 양이 충분하다 |
| 모델·데이터 외부 업로드 | 라이선스·개인정보·정책 문구 보호 |
| 학교망 외부 터널 | 웹 터미널 방식을 택했다 |
| 프롬프트의 confidence 0.7 문구 수정 | 한 번에 하나만 바꾼다(§7에 한계로 기록) |
| `verify=true` 수동 정밀 구축 측정 | LLM 판정이 섞여 임베딩 효과를 분리할 수 없다 |

## 7. 알고 가는 측정 한계

- **표본이 작다.** 관련 94쌍·무관 157쌍이라, 서로 다른 모델의 AUC 차이는 대략 0.05 안쪽이면 구분하기 어렵다. 판정은 신뢰구간으로 한다.
- **평가 프로젝트가 하나다.** 결과를 다른 팀에 일반화하지 않는다.
- **프롬프트 문구가 점수 척도에 묶여 있다.** 시스템 프롬프트의 "confidence 0.7 미만은 추정 표기"와 영어 프롬프트의 `>= 0.5`는 env를 따르지 않는다. 그래서 e2e를 읽을 때 confidence 분포를 함께 기록한다.
- **입력 절단이 다르다.** 서버는 2,048토큰, 3-large는 8,000토큰이다. 잘린 건수를 기록한다.
- **장치가 다르다.** 후보 비교와 서빙은 Mac(MPS, fp16)에서, 학습은 GPU에서 한다. 같은 모델은 같은 장치로 비교한다.

## 8. 진행 기록

| 단계·관문 | 날짜 | 결과 | 원자료 |
|---|---|---|---|
| 0 기준선 | 2026-11-03 | 골든 55케이스 ERROR 0(제외 집합 없음). 덤프 그대로 precision 0.576·recall 0.913(21/23)으로 7월 기록 재현. 현재 코드로 체인 재실행(이슈 `external_id` 보충) 결과도 동일하고, 엣지 수는 REFERENCE 685·DISCUSSED_IN 시맨틱 102·TRIGGERED_BY 시맨틱 10 | `eval/results/edge-2026-11-03-dump-asis.json`, `edge-2026-11-03-baseline-current.json`(로컬, git 미포함). 기준 덤프 `exp-base-2026-11-03.dump`(sha256 `be0a9c33…`), 실험 전 로컬 그래프 백업 `local-backup-2026-11-03.dump`(sha256 `7d42233f…`) |
| 관문 1 | | | |
| 관문 2 | | | |
| 관문 3 | | | |
| 관문 4 | | | |

## 근거 (외부 사실, 2026-11-02~03 확인)

- 모델 카드: [bge-m3](https://huggingface.co/BAAI/bge-m3) · [KURE-v1](https://huggingface.co/nlpai-lab/KURE-v1) · [Qwen3-Embedding-0.6B](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) · [kanana-2-1.3b-embedding](https://huggingface.co/kakaocorp/kanana-2-1.3b-embedding)
- 라이브러리: [transformers](https://pypi.org/project/transformers/)(5.22.1, Python 3.11+·PyTorch 2.6+) · [sentence-transformers](https://pypi.org/project/sentence-transformers/)(6.2.0) · [학습 가이드](https://sbert.net/docs/sentence_transformer/training_overview.html)
- GPU: [NGC PyTorch 23.07](https://docs.nvidia.com/deeplearning/frameworks/pytorch-release-notes/rel-23-07.html) · [MIG 프로필](https://docs.nvidia.com/datacenter/tesla/mig-user-guide/supported-mig-profiles.html)
- [Neo4j 벡터 인덱스](https://neo4j.com/docs/cypher-manual/5/indexes/semantic-indexes/vector-indexes/) · [GitHub Acceptable Use Policies](https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies) · [OpenAI EU Terms of Use 2024-12-11판](https://openai.com/policies/eu-terms-of-use/revisions/2024-12-11/)
