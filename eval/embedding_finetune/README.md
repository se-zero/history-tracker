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

GPU 학습 안내는 묶음 E에서 추가한다.
