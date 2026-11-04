#!/usr/bin/env python
"""임베딩 대조학습 — 공개 저장소 쌍(pairs-train/val.jsonl)으로 Qwen3-Embedding-0.6B를 파인튜닝한다
(docs/embedding-finetune.md §5 2단계, 묶음 E).

  train      변형(all·ko·pre2025) 하나를 학습한다. 학습 전(base)과 학습 후(final)를 같은 검증 평가기로 재어
             <out>/metrics.json에 남기고, 모델은 <out>/model(fp16)에 저장한다. manifest.json에는 입력 파일의 sha256과
             라이브러리 버전을 남긴다. 실패하면 <out>/FAILED에 사유를 쓰고 1로 끝난다.
  summarize  <run-dir>/<변형>/metrics.json들을 모아 summary.json을 쓰고 표로 보여 준다. torch를 import하지 않는다.

학습은 쌍 유형(변경↔대화·변경↔이슈·이슈↔대화)마다 Dataset을 따로 두고, 한 배치는 한 유형에서만 뽑는다.
같은 유형끼리 서로의 오답이 되어 구분이 더 어려워지고 그만큼 배울 것이 많다. 손실은 CachedMultipleNegativesRankingLoss라
메모리는 미니배치만큼만 쓰면서 오답 후보는 배치 전체(기본 127개)를 본다.
Qwen3의 질의 지시문(prompt)은 학습에도 평가에도 쓰지 않는다 — 평가 쪽 처리는 train_core.EVALUATOR_OPTIONS 주석.

실행 (GPU는 gpu_run.sh가 부른다. Mac 시험 실행은 README.md의 「학습 (묶음 E)」 절):
  eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/train.py train --variant all --out <폴더>
  eval/embedding_finetune/.venv/bin/python eval/embedding_finetune/train.py summarize --run-dir <폴더>

torch·sentence-transformers·datasets는 run_training() 안에서만 import한다 — summarize와 인자 해석이 무거운 라이브러리
없이 돌고, 순수 로직은 train_core.py에서 모델 없이 검증한다(eval/tests/test_train_core.py).
"""

import argparse
import math
import os
import platform
import shutil
import sys
import time
import traceback
from datetime import datetime, timezone

import train_core

DEFAULT_DATA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")

# 저장한 모델을 다시 읽어 같은 벡터가 나오는지 보는 문장. 내용은 아무래도 상관없다
PROBE_TEXT = "로그인 실패 시 재시도 횟수를 제한한다"
# fp16으로 저장했다 fp32로 읽으면 cosine이 0.9999쯤이다. 0.99 아래면 저장·로드에서 무언가 빠진 것이다(풀링·정규화 설정 등)
RELOAD_MIN_COSINE = 0.99
# 학습기가 고른 최고 점수와 최종 평가가 이만큼 넘게 다르면 최고 에폭을 불러오지 못한 것이다
BEST_MATCH_TOLERANCE = 1e-3


def log(message: str) -> None:
    # nohup 로그가 몇 시간 동안 버퍼에 갇히지 않게 바로 내보낸다
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


# --------------------------------------------------------------------------- 인자

def positive_int(text: str) -> int:
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("1 이상이어야 합니다")
    return value


def parse_args(argv=None) -> argparse.Namespace:
    ap = argparse.ArgumentParser(description="임베딩 대조학습 (묶음 E)")
    sub = ap.add_subparsers(dest="command", required=True)

    t = sub.add_parser("train", help="변형 하나를 학습한다")
    t.add_argument("--data-dir", default=DEFAULT_DATA_DIR, help="pairs-train.jsonl · pairs-val.jsonl이 있는 폴더")
    t.add_argument("--variant", required=True, choices=train_core.VARIANTS,
                   help="all=전체, ko=한글이 든 쌍, pre2025=2025-01-01 이전 글. train만 거르고 val은 모두 같다")
    t.add_argument("--model", default=train_core.DEFAULT_MODEL, help="HF 모델 id 또는 로컬 경로")
    t.add_argument("--revision", default=None,
                   help="모델 리비전. 생략하면 기본 모델일 때만 고정 해시를 쓰고, 빈 문자열이면 리비전 없이 받는다")
    t.add_argument("--out", required=True, help="결과 폴더 (model/ · metrics.json · manifest.json)")
    t.add_argument("--epochs", type=positive_int, default=3)
    t.add_argument("--batch-size", type=positive_int, default=128, help="배치 크기 = 오답 후보 수 + 1")
    t.add_argument("--mini-batch-size", type=positive_int, default=32, help="한 번에 올리는 크기 — 메모리를 정한다")
    t.add_argument("--lr", type=float, default=2e-5)
    t.add_argument("--warmup", type=float, default=0.1, help="0~1 소수는 전체 스텝의 비율, 1 이상은 스텝 수")
    t.add_argument("--max-seq-length", type=positive_int, default=512)
    t.add_argument("--seed", type=int, default=42)
    t.add_argument("--device", choices=["auto", "cuda", "cpu"], default="auto",
                   help="auto = cuda가 있으면 cuda, 없으면 cpu (MPS 학습은 지원하지 않는다)")
    t.add_argument("--limit-train", type=positive_int, default=None, help="유형당 학습 쌍 수 상한 (시험 실행용)")
    t.add_argument("--limit-val", type=positive_int, default=None, help="유형당 검증 쌍 수 상한 (시험 실행용)")

    s = sub.add_parser("summarize", help="변형 결과를 모아 summary.json을 쓴다")
    s.add_argument("--run-dir", required=True, help="<변형>/metrics.json들이 있는 폴더")
    s.add_argument("--variants", default=",".join(train_core.VARIANTS), help="쉼표로 구분한 변형 이름")

    args = ap.parse_args(argv)
    if args.command == "train":
        if args.revision is None:
            args.revision = train_core.DEFAULT_REVISION if args.model == train_core.DEFAULT_MODEL else None
        elif args.revision == "":
            args.revision = None
    return args


# --------------------------------------------------------------------------- 하위 명령

def cmd_summarize(args) -> int:
    if not os.path.isdir(args.run_dir):
        sys.exit(f"실행 폴더가 없습니다: {args.run_dir}")
    variants = tuple(v.strip() for v in args.variants.split(",") if v.strip())
    try:
        summary = train_core.summarize_run(args.run_dir, variants)
    except ValueError as exc:
        sys.exit(str(exc))
    train_core.write_json_atomic(os.path.join(args.run_dir, "summary.json"), summary)
    print(train_core.format_summary(summary), flush=True)
    return 0 if summary["chosen"] else 1


def cmd_train(args) -> int:
    out = os.path.abspath(args.out)
    os.makedirs(out, exist_ok=True)
    # 같은 폴더를 다시 쓸 때 이전 시도의 표시가 남으면 summarize가 이번 결과를 잘못 읽는다
    for stale in ("FAILED", "metrics.json"):
        if os.path.exists(os.path.join(out, stale)):
            os.remove(os.path.join(out, stale))
    try:
        run_training(args, out)
    except Exception as exc:  # noqa: BLE001 — 무엇이든 표시를 남기고 0이 아닌 코드로 끝내야 gpu_run.sh가 다음 변형으로 넘어간다
        summary = f"{type(exc).__name__}: {exc}"
        with open(os.path.join(out, "FAILED"), "w", encoding="utf-8") as f:
            f.write(summary + "\n\n" + traceback.format_exc())
        log(f"실패: {summary}")
        traceback.print_exc()
        return 1
    return 0


# --------------------------------------------------------------------------- 학습

def resolve_device(requested: str, torch) -> str:
    cuda = torch.cuda.is_available()
    if requested == "cuda" and not cuda:
        raise RuntimeError("--device cuda인데 CUDA를 쓸 수 없습니다 (드라이버·torch 빌드 확인)")
    if requested == "auto":
        return "cuda" if cuda else "cpu"
    return requested


def library_versions(torch, device: str) -> dict:
    from importlib import metadata

    versions = {"python": platform.python_version(), "torch": torch.__version__}
    for name in ("transformers", "sentence-transformers", "datasets", "accelerate"):
        versions[name] = metadata.version(name)
    if device == "cuda":
        versions["cuda"] = torch.version.cuda
        versions["gpu"] = torch.cuda.get_device_name(0)
    return versions


def evaluate(evaluator, model, types) -> dict:
    """유형별 NDCG@10과 그 평균을 돌려준다. details에는 평가기가 낸 모든 지표(MRR·정확도·재현율 등)가 들어 있다."""
    scores = evaluator(model)
    keys = {t: train_core.ndcg_key(t) for t in types}
    missing = [k for k in keys.values() if k not in scores]
    if missing:
        raise RuntimeError(f"평가 결과에 {missing}가 없습니다 — 모델의 유사도 함수가 cosine이 아닌지 확인하세요 "
                           f"(나온 키: {sorted(scores)[:6]} ...)")
    by_type = {t: float(scores[k]) for t, k in keys.items()}
    macro = train_core.macro_mean(by_type.values())
    # 학습기가 최고 에폭을 고르는 점수(sequential_score)와 우리가 보고하는 macro는 같은 값이어야 한다.
    # 다르면 학습기가 보고서와 다른 기준으로 모델을 고르고 있다는 뜻이라, 한 시간 학습하기 전에 여기서 멈춘다
    if not math.isclose(float(scores["sequential_score"]), macro, rel_tol=0.0, abs_tol=1e-9):
        raise RuntimeError(f"sequential_score({scores['sequential_score']})가 유형 평균({macro})과 다릅니다")
    return {"by_type": by_type, "macro": macro, "details": {k: float(v) for k, v in scores.items()}}


def format_scores(result: dict) -> str:
    cells = [f"{train_core.EDGE_LABELS[t]} {v:.4f}" for t, v in result["by_type"].items()]
    return " / ".join(cells) + f" / macro {result['macro']:.4f}"


def run_training(args, out: str) -> None:
    started = time.time()

    # 1) 데이터 — 변형은 train만 거른다. val은 변형과 관계없이 전체를 써야 변형끼리 점수를 비교할 수 있다
    train_path = os.path.join(args.data_dir, "pairs-train.jsonl")
    val_path = os.path.join(args.data_dir, "pairs-val.jsonl")
    train_rows = train_core.filter_variant(train_core.read_pairs(train_path), args.variant)
    columns = train_core.training_columns_by_type(train_rows, args.limit_train, args.seed)
    eval_inputs = train_core.eval_inputs_by_type(train_core.read_pairs(val_path), args.limit_val, args.seed)
    if not columns:
        raise ValueError(f"변형 {args.variant!r}에 학습 쌍이 없습니다")
    if not eval_inputs:
        raise ValueError("검증 쌍이 없습니다")
    types = list(eval_inputs)
    n_train = {t: len(c["anchor"]) for t, c in columns.items()}
    n_val = {t: {"pairs": sum(len(rel) for rel in relevant.values()), "queries": len(queries), "corpus": len(corpus)}
             for t, (queries, corpus, relevant) in eval_inputs.items()}
    log(f"변형 {args.variant}: 학습 쌍 {n_train} / 검증 {n_val}")

    # 무거운 import는 여기서만 한다 (모듈 docstring 참고)
    import torch
    from datasets import Dataset, DatasetDict
    from sentence_transformers import (
        SentenceTransformer,
        SentenceTransformerTrainer,
        SentenceTransformerTrainingArguments,
    )
    from sentence_transformers.base.sampler import BatchSamplers, MultiDatasetBatchSamplers
    from sentence_transformers.sentence_transformer.evaluation import (
        InformationRetrievalEvaluator,
        SequentialEvaluator,
    )
    from sentence_transformers.sentence_transformer.losses import CachedMultipleNegativesRankingLoss

    device = resolve_device(args.device, torch)
    bf16 = device == "cuda" and torch.cuda.is_bf16_supported()

    train_core.write_json_atomic(os.path.join(out, "manifest.json"), {
        "variant": args.variant,
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "counts": {"train": n_train, "val": n_val},
        "inputs": {name: train_core.sha256_file(path)
                   for name, path in (("pairs-train.jsonl", train_path), ("pairs-val.jsonl", val_path))},
        "args": {k: v for k, v in vars(args).items() if k != "command"},
        "versions": library_versions(torch, device),
    })

    # 2) 모델 — 마스터 가중치는 fp32로 두고 CUDA에서는 bf16 autocast로 계산한다
    log(f"모델 로드: {args.model} (revision={args.revision}, device={device}, bf16={bf16})")
    model = SentenceTransformer(args.model, revision=args.revision, device=device,
                                model_kwargs={"dtype": torch.float32})
    model.max_seq_length = args.max_seq_length

    # 학습 인자를 평가보다 먼저 만든다. tf32 설정은 이 객체를 만들 때 전역으로 적용되므로, 순서가 반대면 학습 전
    # 평가만 tf32 없이 재어져 base와 이후 점수가 다른 정밀도로 비교된다
    targs = SentenceTransformerTrainingArguments(
        output_dir=os.path.join(out, "checkpoints"),
        num_train_epochs=args.epochs,
        per_device_train_batch_size=args.batch_size,
        learning_rate=args.lr,
        warmup_steps=args.warmup,   # transformers 5.x에는 warmup_ratio가 없다 — 0~1 소수가 비율이다
        lr_scheduler_type="linear",
        weight_decay=0.01,
        bf16=bf16,
        tf32=True if device == "cuda" else None,
        batch_sampler=BatchSamplers.NO_DUPLICATES,
        multi_dataset_batch_sampler=MultiDatasetBatchSamplers.PROPORTIONAL,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_only_model=True,
        save_total_limit=1,
        load_best_model_at_end=True,
        metric_for_best_model="eval_sequential_score",
        greater_is_better=True,
        logging_steps=10,
        disable_tqdm=True,   # nohup 로그에 진행 막대가 쌓이지 않게 한다
        report_to="none",
        seed=args.seed,
        data_seed=args.seed,
        use_cpu=(device == "cpu"),
    )

    # 3) 평가기 — 유형마다 하나. 앵커를 질의, 같은 유형의 짝을 후보로 놓고 NDCG@10을 잰다
    evaluator = SequentialEvaluator(
        [InformationRetrievalEvaluator(queries, corpus, relevant, name=train_core.evaluator_name(t),
                                       **train_core.EVALUATOR_OPTIONS)
         for t, (queries, corpus, relevant) in eval_inputs.items()],
        main_score_function=train_core.macro_mean,
    )

    # 4) 학습 전 점수 — "베이스보다 오른다"를 판정하는 기준이다
    log("학습 전(base) 평가")
    base = evaluate(evaluator, model, types)
    log(f"base: {format_scores(base)}")

    # 5) 학습 — 두 열(anchor·positive)만 둔다 (train_core.training_columns_by_type 주석)
    dataset = DatasetDict({t: Dataset.from_dict(c) for t, c in columns.items()})
    loss = CachedMultipleNegativesRankingLoss(model, mini_batch_size=args.mini_batch_size)
    trainer = SentenceTransformerTrainer(model=model, args=targs, train_dataset=dataset, loss=loss, evaluator=evaluator)
    log(f"학습 시작: 에폭 {args.epochs}, 배치 {args.batch_size}(미니배치 {args.mini_batch_size}), lr {args.lr}")
    train_started = time.time()
    trainer.train()
    train_seconds = time.time() - train_started

    # 6) 학습 후 점수 — load_best_model_at_end로 최고 에폭이 불러와진 상태다
    log("학습 후(final) 평가")
    final = evaluate(evaluator, model, types)
    log(f"final: {format_scores(final)}")
    records = train_core.epoch_records(trainer.state.log_history, types)
    best_metric = trainer.state.best_metric
    best_epoch = train_core.pick_best_epoch(records, best_metric)
    # ST는 최고 체크포인트를 불러오지 못해도 오류 로그만 남기고 마지막 에폭 모델로 계속한다. 그러면 위 final은 저장될
    # 모델의 실제 점수라 변형 비교에는 문제가 없지만 best_epoch 표기가 틀리므로 어긋남을 기록한다
    best_model_loaded = best_metric is None or abs(final["macro"] - best_metric) <= BEST_MATCH_TOLERANCE
    if not best_model_loaded:
        log(f"경고: 최종 평가({final['macro']:.4f})가 학습기가 고른 최고 점수({best_metric:.4f})와 다릅니다 — 최고 에폭을 불러오지 못했을 수 있습니다")

    # 7) 저장 — fp16(약 1.2GB)로 줄여 쓰고 곧바로 CPU에서 다시 읽어, 내려받은 뒤에야 깨진 걸 아는 일을 막는다.
    # 모델 카드(README.md)는 만들지 않는다: 학습 데이터 샘플이 README에 실리고, 렌더링 실패가 저장을 막을 수 있어서다
    reference = model.encode([PROBE_TEXT], convert_to_tensor=True).float().cpu()
    model.half()
    model_dir = os.path.join(out, "model")
    if os.path.exists(model_dir):
        shutil.rmtree(model_dir)
    model.save(model_dir, create_model_card=False)
    reloaded = SentenceTransformer(model_dir, device="cpu", model_kwargs={"dtype": torch.float32})
    vector = reloaded.encode([PROBE_TEXT], convert_to_tensor=True).float()
    cosine = float(torch.nn.functional.cosine_similarity(reference, vector).item())
    reload_ok = bool(torch.isfinite(vector).all()) and vector.ndim == 2 and cosine >= RELOAD_MIN_COSINE
    dim = int(vector.shape[1])
    log(f"저장 확인: reload_ok={reload_ok} dim={dim} cosine(저장 전, 저장 후)={cosine:.6f}")
    if not reload_ok:
        raise RuntimeError(f"저장한 모델을 다시 읽은 결과가 이상합니다 (cosine {cosine:.4f}, dim {dim})")
    shutil.rmtree(os.path.join(out, "checkpoints"), ignore_errors=True)

    # 8) 기록 — 마지막에 한 번, 원자적으로 쓴다. 이 파일이 있다는 것이 곧 이 변형이 끝까지 성공했다는 표시다
    train_core.write_json_atomic(os.path.join(out, "metrics.json"), {
        "status": "ok",
        "variant": args.variant,
        "model": args.model,
        "revision": args.revision,
        "device": device,
        "bf16": bf16,
        "n_train": n_train,
        "n_val": n_val,
        "base": base,
        "final": final,
        "best_epoch": best_epoch,
        "best_metric": best_metric,
        "best_model_loaded": best_model_loaded,
        "epochs": records,
        "reload_ok": reload_ok,
        "reload_cosine": cosine,
        "dim": dim,
        "peak_gpu_gib": round(torch.cuda.max_memory_allocated() / 2**30, 2) if device == "cuda" else None,
        "train_seconds": round(train_seconds, 1),
        "total_seconds": round(time.time() - started, 1),
    })
    log(f"끝: {out} (학습 {train_seconds / 60:.1f}분, 전체 {(time.time() - started) / 60:.1f}분)")


def main(argv=None) -> int:
    args = parse_args(argv)
    if args.command == "summarize":
        return cmd_summarize(args)
    return cmd_train(args)


if __name__ == "__main__":
    sys.exit(main())
