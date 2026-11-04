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

GPU 학습 안내는 묶음 E에서 추가한다.
