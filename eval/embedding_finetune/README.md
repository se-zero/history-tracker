# embedding_finetune — 임베딩 대체 실험 도구

오픈 임베딩 모델을 OpenAI와 같은 형식의 `POST /v1/embeddings`로 띄우는 로컬 서버(`serve.py`)다.
eval 스크립트(OpenAI SDK)와 ai-engine이 코드를 거의 그대로 둔 채 `base_url`·모델명만 바꿔
후보 모델을 `text-embedding-3-large`와 같은 자로 잴 수 있다. 프로세스당 모델 1개만 올린다(Mac 16GB).

## Mac 준비 (최초 1회)

```bash
cd eval/embedding_finetune
/opt/homebrew/bin/python3.11 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
```

기본 `python3`(3.14)는 쓰지 않는다. transformers 5.x가 Python 3.11 이상·PyTorch 2.6 이상을 요구하고,
macOS arm64용 PyPI torch에 MPS(Apple GPU)가 들어 있다. 장치는 cuda → mps → cpu 순으로 자동 선택된다.

## 후보별 실행

```bash
cd eval/embedding_finetune
.venv/bin/python serve.py --model BAAI/bge-m3
.venv/bin/python serve.py --model nlpai-lab/KURE-v1
.venv/bin/python serve.py --model Qwen/Qwen3-Embedding-0.6B
.venv/bin/python serve.py --model kakaocorp/kanana-2-1.3b-embedding --trust-remote-code --revision <커밋 해시>
```

- **kanana**는 모델 저장소의 코드를 실행한다(`--trust-remote-code`). 코드를 읽고 커밋 해시로 고정해서 띄운다.
  해시 조회: `curl -s https://huggingface.co/api/models/kakaocorp/kanana-2-1.3b-embedding | python3 -c "import sys,json; print(json.load(sys.stdin)['sha'])"`
- 요청의 `model`이 서빙 이름(`--name`, 기본 `--model`)과 다르면 400이다. 호출하는 쪽도 같은 이름을 쓴다.
- 입력은 `--max-seq-length`(기본 2048) 토큰에서 잘린다. 요청마다 `truncated=N`이 로그에 남으므로
  잘린 건수는 `grep truncated= <서버 로그>`로 합산한다(잘림이 있으면 WARNING).
- 접두어·지시문은 붙이지 않는다(Qwen3의 질의 지시문 등은 호출하는 쪽이 텍스트에 넣는다).
- `dimensions` 요청은 앞쪽을 자르고 L2 재정규화한다(MRL 절단). MRL로 학습되지 않은 모델은 자르면 품질이 떨어진다.
- 기동 때 한 번 돌려 보고 fp16에서 NaN이 나오면 종료한다 — 그러면 `--dtype fp32`로 다시 띄운다.
- **측정(precheck·풀스택)은 `--dtype fp32`로 띄운다.** fp16에서는 같은 글도 한 건씩 넣을 때와 묶음으로 넣을 때
  벡터가 최대 3.7e-4 달라진다(MiniLM 실측, fp32는 2.4e-7). 측정 텍스트가 2천 건 안팎이라 fp32로도 충분히 빠르다.
- 긴 입력이 많아 메모리가 모자라면 `--batch-size`를 줄인다.
- 토큰이 필요하면 환경변수 `EMBED_SERVER_TOKEN`을 준다(비우면 인증 없음). 기본 바인딩이 `127.0.0.1`인 이유이므로
  `--host`로 밖에 열 때는 토큰을 함께 준다.

## 확인

모델 로드와 워밍업이 끝나야 포트가 열린다(로그의 `준비 완료` 줄). 첫 요청은 MPS 준비 때문에 1초 안팎 걸릴 수 있다(MiniLM 실측).

```bash
curl -s localhost:8100/health      # {"model","dim","device","dtype","max_seq_length"}
curl -s localhost:8100/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"BAAI/bge-m3","input":["안녕","hello"]}' | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d['data']), len(d['data'][0]['embedding']), d['usage'])"
```

```python
from openai import OpenAI
c = OpenAI(base_url="http://127.0.0.1:8100/v1", api_key="x")
c.embeddings.create(model="BAAI/bge-m3", input=["안녕", "hello"])
```

서버 로직 테스트는 모델 없이 돈다: `services/ai-engine/.venv/bin/python -m pytest eval/tests -q`

## 학습 데이터 수집

허용 라이선스(MIT·Apache-2.0·BSD-2/3-Clause) 공개 GitHub 저장소에서 "같은 사안을 다루는 글 쌍"
(코드 변경↔리뷰 댓글, 변경↔이슈, 이슈↔댓글)을 모아 대조학습용 JSONL로 만든다. `collect_pairs.py` 하나이고
표준 라이브러리만 써서 추가 설치가 없다. GitHub 호출은 `gh api`로 하므로 토큰은 파이썬이 다루지 않는다.

**준비:** `gh auth status`로 로그인돼 있는지 확인한다.

```bash
# 저장소 루트에서
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py fetch      # 원자료 받기
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py build --per-pr-commit-cap 0   # 쌍 만들기 (네트워크 없음)
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py sample --n 50   # 사람이 읽어 볼 표본
```

`--per-pr-commit-cap 0`은 커밋 제목 앵커(`change_issue/commit_message`)를 끈다. PR의 무작위 커밋 제목이 이슈와 무관한 경우가
많아서다(2026-11-04 표본에서 10쌍 중 4쌍, 예: "remove console.log"). 기본값은 3이다.

처음에는 저장소 하나로 시험해 보면 좋다: `fetch --repos next-step/atdd-subway-admin`. 상한·분할 같은 인자는
`collect_pairs.py <명령> --help`.

**결과 (`data/`):**

| 파일 | 내용 |
|---|---|
| `raw/<owner>__<repo>/` | 저장소별 원자료(`meta.json`, `issues.jsonl`, `review_comments.jsonl`, 제품형이면 `pulls`·`issue_comments`·`pr_commits`) |
| `pairs-train.jsonl` · `pairs-val.jsonl` | 쌍 한 줄 = `{id, type, anchor_kind, anchor, positive_kind, positive, repo, split, has_korean, source:{kind, number}, created_at}` |
| `pairs-manifest.json` | 저장소·유형별 건수, 탈락 사유별 수, 라이선스, 사용한 인자, 분할별 한글 비율 |
| `pairs-sample50.jsonl` | `(type, anchor_kind)` 층마다 고르게 뽑은 표본 |

쌍은 `change_conversation`(코드 변경 줄 또는 PR 제목 ↔ 리뷰·PR 댓글), `change_issue`(PR 제목·커밋 제목 ↔ PR이
가리키는 이슈), `issue_conversation`(이슈 ↔ 그 댓글) 세 유형이다. 사람이 쓴 이슈(PR·봇 제외)가 20건 이상인
"제품형" 저장소에서만 PR 제목↔PR 댓글, 변경↔이슈, 이슈↔댓글 쌍을 모으고, 이슈가 거의 없는 미션 저장소는 코드 변경↔리뷰
댓글 쌍만 나온다. 검증용 저장소(기본
`woowacourse-teams/2022-dallog`·`2023-hang-log`)는 `pairs-val.jsonl`로 간다. `has_korean`은 앵커나 짝의 내용에
한글이 있는지만 기록한다(diff 앵커의 `[추가]`·`[제거]` 표지는 세지 않는다). 같은 원자료·같은 시드면 출력이 같다.

**걸리는 시간:** 3시간 안팎이다(2026-11-04에 155개 저장소 실측 2.8시간). 리뷰 댓글을 100건씩 받는 요청이 몇 초씩
걸려서, 리뷰 댓글 30쪽을 다 채우는 미션 저장소는 하나에 4~8분이 든다. REST 한도(시간당 5,000회)가 100회 아래로 내려가면 reset 시각까지
알아서 기다리고, 5xx 오류·시간 초과·네트워크 오류(DNS 실패, 연결 끊김)는 최대 5회 다시 시도한다. 저장소마다
`meta.json`의 `complete`로 완료를 표시하므로 끊겨도 같은 명령을 다시 실행하면 이어 받는다(`--refresh`로 완료된
저장소도 다시 받기). 어떤 저장소가 실패해도 나머지는 계속 받고, 끝에 실패 목록을 보여 주며 종료 코드 1로 끝난다.
맥이 잠들면 멈추므로 `caffeinate -i`로 감싼다.

```bash
caffeinate -i eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/collect_pairs.py fetch
```

**빼는 글:**
- 포크이거나 허용 라이선스 밖인 저장소(`fetch`와 `build`에서 두 번 확인한다)
- 봇(`user.type == "Bot"`, 앱이 단 댓글)과 Copilot 리뷰, 봇이 연 PR·이슈의 제목·본문
- 사람 계정으로 올라와 봇 판정을 피하는 자동 글(`machine`). 첫 줄이 Codecov·Gatsby Cloud 보고서 제목(`## [Codecov](…) Report`),
  배포 미리보기 알림(`## :white_check_mark: <사이트> deploy preview ready`·`failed`), AI 리뷰 템플릿(`**Review 2 · Pass**`·`Fail`)인
  글과, 본문 어디에든 `Generated with [Claude Code]`가 있는 글이다. Codecov는 일반 사용자 계정으로, Gatsby Cloud는 삭제돼
  `ghost`가 된 계정으로, AI 리뷰는 사람 계정으로 올라오니 `user.type`·앱 표시로는 걸리지 않고, 작성자를 저장하지 않으니 글
  모양으로 거른다. 리뷰·PR·이슈 댓글(짝 글)에만 적용하고 이슈 본문·PR 제목은 거르지 않는다. "codecov"·🤖 같은 낱말이 든 사람
  글은 남긴다
- 코드블록·이모지를 뺀 글이 20자 미만이거나 "LGTM", "감사합니다", "반영했습니다" 같은 인사·확인뿐인 댓글
- 가린 뒤 8자 미만인 PR·커밋 제목, 머지·리뷰 반영·WIP 커밋, 변경 줄이 없는 diff
- 이슈를 4개 이상 가리키는 PR(릴리스·묶음 PR), 같은 짝 글의 반복
- 탈락 사유별 수는 `pairs-manifest.json`의 `dropped`에 남는다(`license`는 글이 아니라 저장소를 1로 센다)

**개인정보:**
- 작성자 필드(로그인·이름·이메일·사용자 id·아바타)는 원자료에도 쓰지 않는다. 봇인지 같은 판정 플래그와 글 내용만 남기고,
  커밋은 제목줄만 저장한다.
- 그 저장소에서 글이나 커밋을 쓴 사람의 `@로그인`은 `@user`로, 이메일은 `<EMAIL>`로 `fetch` 단계에서 가린다. 로그인
  목록은 메모리에서만 쓴다. `@ManyToOne` 같은 애너테이션은 로그인이 아니라 그대로 남는다.
- `#123`→`#N`, `HT-12`→`KEY-N`, URL→`<URL>`, 커밋 해시→`<HASH>`는 `build` 단계에서 가린다(코드 줄에는 URL만).
- **못 가리는 것:** 본문 속에 그냥 적힌 이름("철수님 확인해 주세요"), 글을 쓴 적 없는 사람의 멘션, 파일 경로·브랜치 이름에 든
  이름.

`data/`는 `.gitignore`에 들어 있어 커밋되지 않는다. 외부로 올리지 않는다.

수집 로직 테스트도 같은 명령으로 돈다(가짜 `gh`를 주입하므로 네트워크를 쓰지 않는다): `services/ai-engine/.venv/bin/python -m pytest eval/tests -q`

## 학습 (묶음 E)

위에서 만든 `data/pairs-*.jsonl`로 `Qwen/Qwen3-Embedding-0.6B`를 대조학습한다(Sentence Transformers의
`CachedMultipleNegativesRankingLoss`). 학교 GPU는 웹 터미널로만 닿아서 실패할 때마다 사용자가 오가야 한다.
그래서 **Mac에서 같은 스크립트를 작은 모델로 먼저 돌려 오류를 잡고**, GPU에서는 설치·학습·포장을 한 번에 끝낸다.

| 파일 | 역할 |
|---|---|
| `train.py` | `train`은 변형 하나를 학습하고, `summarize`는 변형 결과를 모아 1위를 고른다 |
| `train_core.py` | 순수 로직(쌍 거르기, 검증 입력, 점수 평균, 1위 고르기). torch를 import하지 않아 `eval/tests`에서 검증한다 |
| `gpu_run.sh` | GPU 잡에서 `start`·`status`. 환경 설치 → 모델 받기 → 변형 3개 학습 → 비교 → 결과 묶기 |
| `make_gpu_bundle.py` | 올릴 묶음(tgz) 하나와 그 안의 `SHA256SUMS`를 만든다 |
| `requirements-train.txt` | 학습 의존성. torch는 일부러 뺐다(Mac은 `requirements.txt`의 2.15.0, GPU는 `gpu_run.sh`가 CUDA 12.6 빌드 2.14.1) |

**학습 구성:**
- 변형은 셋이다. `all`(전체), `ko`(`has_korean`이 참인 쌍), `pre2025`(`created_at`이 2025-01-01 이전). **변형은 train만 거르고
  val은 변형과 관계없이 전체를 쓴다.** 그래야 변형끼리 점수를 비교할 수 있다.
- 쌍 유형(변경↔대화·변경↔이슈·이슈↔대화)마다 Dataset을 따로 두고, 한 배치는 한 유형에서만 뽑는다. 같은 유형끼리 서로의
  오답이 되어 구분이 더 어려워진다. 열은 `anchor`·`positive` 두 개뿐이다(다른 열이 있으면 `NO_DUPLICATES` 샘플러가 그 값까지 비교한다).
- 배치 128(미니배치 32), lr 2e-5, warmup 10%, 입력 512토큰, 3에폭, seed 42. CUDA에서는 bf16, 저장은 fp16이다. 에폭마다 val을 재어
  가장 좋은 에폭을 남긴다.
- **지시문(prompt)은 학습에도 평가에도 쓰지 않는다.** 평가기가 질의를 `encode_query`로 인코딩하는데, 이 함수는 prompt가 `None`이면 모델의
  `prompts["query"]`(Qwen3는 "Instruct: ..." 지시문)를 자동으로 붙인다. 그래서 평가기에는 `query_prompt=""`를 준다
  (`train_core.EVALUATOR_OPTIONS`).
- **고르는 점수는 val의 유형별 NDCG@10의 산술 평균이다**(세 유형 같은 가중). 유형마다 앵커를 질의, 같은 유형의 짝을 후보로 놓는다.
  같은 앵커의 짝이 여럿이면 모두 정답이다. 이슈↔대화 val은 96쌍이라 흔들림이 크다. 유형별 점수와 학습 전(base) 점수도 모두 `metrics.json`에 남는다.

### Mac 시험 실행

GPU에 올리기 전에 작은 모델(`all-MiniLM-L6-v2`, CPU)로 흐름 전체를 돌려 본다. 몇 분이면 끝난다. 저장소 루트에서:

```bash
# 최초 1회: datasets·accelerate를 더한다 (torch 등은 이미 설치돼 있다)
eval/embedding_finetune/.venv/bin/python -m pip install -r eval/embedding_finetune/requirements-train.txt

# 1) train.py 단독 — 변형 하나 (약 1분)
HF_HUB_OFFLINE=1 eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/train.py train \
  --variant all --model sentence-transformers/all-MiniLM-L6-v2 --out /tmp/ef-smoke/train-all \
  --device cpu --limit-train 64 --limit-val 32 --epochs 1 --batch-size 8 --mini-batch-size 4
# → /tmp/ef-smoke/train-all/{model/, metrics.json, manifest.json}. 실패하면 FAILED가 생기고 종료 코드가 1이다

# 2) 묶음 + gpu_run.sh 전체 흐름 (약 2분)
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/make_gpu_bundle.py --out /tmp/ef-smoke/bundle.tgz
mkdir -p /tmp/ef-smoke/gpu-sim && tar xzf /tmp/ef-smoke/bundle.tgz -C /tmp/ef-smoke/gpu-sim && cd /tmp/ef-smoke/gpu-sim
# (이 터미널에서만 쓰는 시험용 값이다. 터미널을 닫으면 사라진다)
export ROOT=/tmp/ef-smoke/gpu-root HF_HOME="$HOME/.cache/huggingface" ALLOW_CPU=1 MIN_FREE_GB=1 HF_HUB_OFFLINE=1 \
  PYTHON=<저장소 절대경로>/eval/embedding_finetune/.venv/bin/python MODEL=sentence-transformers/all-MiniLM-L6-v2 REVISION= \
  TRAIN_EXTRA_ARGS="--device cpu --limit-train 32 --limit-val 16 --epochs 1 --batch-size 8 --mini-batch-size 4"
bash ef-bundle/gpu_run.sh start
bash ef-bundle/gpu_run.sh status      # 끝나면 result-*.tgz 경로와 sha256이 나온다
```

`HF_HOME`을 직접 주는 이유: 비어 있으면 `gpu_run.sh`가 `$ROOT/hf`를 쓰는데, Mac에서는 그 폴더가 비어 있어 받아 둔 모델을 못 찾는다.

`gpu_run.sh`는 환경변수로 바꾼다(괄호는 기본값):

| 변수 | 뜻 |
|---|---|
| `ROOT`(`/mnt/models`), `RUN_ROOT`(`$ROOT/runs`), `VENV`(`$ROOT/venv-ef`), `HF_HOME`(`$ROOT/hf`) | 저장 위치. `HF_HOME`은 이미 있으면 그대로 쓴다 |
| `MODEL`(Qwen3-Embedding-0.6B), `REVISION` | 기본 모델이면 고정 해시(`97b0c614…`). 빈 문자열이면 리비전 없이 받는다 |
| `VARIANTS`(`all ko pre2025`) | 돌릴 변형. 순서대로 돌고, 하나가 실패해도 다음으로 넘어간다 |
| `TRAIN_EXTRA_ARGS` | `train.py`에 덧붙일 인자 (`--mini-batch-size 16`, `--epochs 2` 등) |
| `MIN_FREE_GB`(20) | `$ROOT` 빈 공간이 이보다 작으면 시작 전에 멈춘다 |
| `PYTHON` / `SKIP_INSTALL=1` | 환경 설치를 건너뛴다(`PYTHON`은 그 파이썬을, `SKIP_INSTALL`은 `$VENV`를 그대로 쓴다) |
| `ALLOW_CPU=1`, `HF_HUB_OFFLINE=1` | nvidia-smi·CUDA 확인을 건너뛴다 / 모델 내려받기를 건너뛴다 (시험 실행용). 주지 않아도 모델을 받은 뒤에는 스크립트가 스스로 `HF_HUB_OFFLINE=1`로 바꿔, 학습 중 네트워크가 끊겨도 받아 둔 파일로 돈다 |

### 올릴 묶음 만들기

```bash
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/make_gpu_bundle.py
```

`data/gpu-bundle-<날짜>.tgz`(약 6MB, `data/`는 gitignore)를 만들고, 경로·크기·sha256과 GPU에서 칠 한 줄을 출력한다. 안에는
`gpu_run.sh`·`train.py`·`train_core.py`·`requirements-train.txt`·`pairs-train/val.jsonl`·`pairs-manifest.json`·`SHA256SUMS`가 들어
있다. 같은 입력이면 같은 파일(같은 sha256)이 나온다. 만든 뒤 파일을 다시 열어 `SHA256SUMS`와 대조한다.

### GPU에서 할 일 (사용자)

1. **잡을 켠다.** AstraGo에서 이전과 같은 이미지, MIG 3g.40gb 1개, `whycode`를 `/mnt/models`에 붙인다.
2. **묶음 파일을 올린다.** 웹 터미널의 파일 올리기로 `gpu-bundle-<날짜>.tgz` 하나를 올린다.
3. **한 줄을 붙여 넣는다.** 올린 폴더에서 (`make_gpu_bundle.py`가 출력한 줄 그대로):
   ```bash
   sha256sum gpu-bundle-<날짜>.tgz | cut -c1-8 && tar xzf gpu-bundle-<날짜>.tgz && bash ef-bundle/gpu_run.sh start
   ```
   앞 8자가 Mac에서 출력된 값과 같은지 본다. 이어서 압축을 풀고 시작한다. `start`는 먼저 묶음 안 `SHA256SUMS`를 검사해서, 파일이
   잘렸거나 바뀌었으면 "다시 올려 주세요"로 멈춘다. 통과하면 스스로 백그라운드로 다시 떠서 **브라우저를 닫아도 계속 돈다.**
4. **기다린다.** 계획 문서의 추정은 설치 10~20분(최초 1회), 학습 최대 1시간 30분이다. 실제 시간은 아직 재 본 적이 없다. 끝난 뒤 `metrics-<변형>.json`의
   `train_seconds`·`peak_gpu_gib`로 확인한다. 진행은 언제든 `bash ef-bundle/gpu_run.sh status`로 본다(단계·로그 끝 20줄).
   같은 명령을 다시 쳐도 이미 도는 중이면 거절하므로 안전하다.
5. **결과 파일 하나를 내려받는다.** 끝나면 `status`가 `/mnt/models/runs/<실행 id>/result-<실행 id>.tgz`와 sha256을 알려 준다.
6. **잡을 끈다.** 공용 GPU를 반납한다. `/mnt/models`는 잡과 별개로 남는다.

실패하면 `status`가 "실패: [단계] — 로그 끝 30줄을 보내 주세요"와 함께 로그 끝 30줄을 보여 준다. 그대로 복사해 전달한다.

**자주 막힐 곳:**
- `CUDA out of memory`: 미니배치만 줄이면 된다. 캐시형 손실이라 배치 128의 학습은 그대로이고 메모리만 준다.
  `TRAIN_EXTRA_ARGS="--mini-batch-size 16" bash ef-bundle/gpu_run.sh start`. 쓰고 있던 메모리는 `metrics-<변형>.json`의 `peak_gpu_gib`에 남는다.
- 환경 설치 단계의 실패: 같은 명령을 다시 치면 환경을 처음부터 다시 만든다(내려받은 휠은 uv 캐시에 남아 있어 빨라진다). 설치는 끝났는데 CUDA 확인에서
  실패했다면 완료 표시가 붙어 있어 같은 환경을 다시 쓰므로, `rm -rf /mnt/models/venv-ef`로 지운 뒤 다시 친다.
- "CUDA를 쓸 수 없습니다": 드라이버 버전이 함께 찍힌다(`env.txt`). cu126 빌드는 드라이버 525 이상이 필요하다.
- 한 변형만 실패: 나머지 변형은 계속 돌고, 실패한 변형은 `summary.json`에 `failed`와 사유로 남는다.

### 결과 tgz

`result-<실행 id>/` 안에 다음이 들어 있다. 변형 3개 중 **검증 점수 1위 변형**의 모델만 담는다.

| 파일 | 내용 |
|---|---|
| `model/` | 1위 변형의 모델(fp16, 약 1.2GB). `SentenceTransformer`로 바로 읽힌다 |
| `summary.json` | 학습 전(base) 점수, 변형별 상태·유형별 NDCG@10·평균, `chosen` |
| `metrics-<변형>.json` | 변형별 학습 전·후의 모든 지표, 에폭별 점수·loss, 학습 시간, 저장 확인 결과 |
| `manifest-<변형>.json` | 변형별 쌍 건수, 입력 파일 sha256, 인자, 라이브러리 버전·GPU |
| `env.txt` · `run.log` | nvidia-smi·torch/CUDA 확인 결과 / 실행 로그 사본 |

다른 변형의 모델과 기록은 지우지 않는다. `/mnt/models/runs/<실행 id>/<변형>/model`에 남으므로 필요하면 그 폴더에서 받는다.
체크포인트는 학습이 끝나면 지운다.

### 받은 뒤 Mac에서 할 일

```bash
shasum -a 256 result-<실행 id>.tgz          # status가 알려 준 sha256과 같은지
mkdir -p eval/embedding_finetune/models/<실행 id>      # models/는 gitignore
tar xzf result-<실행 id>.tgz -C eval/embedding_finetune/models/<실행 id>
```

학습한 모델을 `serve.py`로 띄우고 1단계와 같은 자로 잰다. 서빙 이름은 `precheck_embedding_model.py`의 `qwen3-0.6b-ft`와 같아야 한다.

```bash
eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/serve.py --dtype fp32 --name qwen3-0.6b-ft \
  --model eval/embedding_finetune/models/<실행 id>/result-<실행 id>/model
services/ai-engine/.venv/bin/python eval/precheck_embedding_model.py --only qwen3-0.6b-ft   # 다른 터미널에서
```

학습 로직 테스트는 모델·GPU 없이 돈다: `services/ai-engine/.venv/bin/python -m pytest eval/tests -q`
