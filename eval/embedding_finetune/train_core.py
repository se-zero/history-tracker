"""학습 스크립트(train.py)의 순수 로직 — 표준 라이브러리만 쓰고 torch·sentence-transformers는 import하지 않는다.

train.py가 모델과 학습기를 맡고, 여기에는 쌍을 거르고 나누는 일, 검증 입력을 만드는 일, 점수를 합치는 일,
변형 결과를 모아 1위를 고르는 일을 둔다. 이 부분이 틀리면 학습은 멀쩡히 돌고 숫자만 조용히 틀어진다
(예: val까지 변형으로 걸러 변형끼리 비교가 무효가 됨, 같은 앵커의 짝이 둘일 때 하나만 정답으로 쳐 점수가 낮게 나옴,
유형 점수를 평균하지 않고 건수로 합쳐 이슈↔대화가 묻힘). GPU에서 한 번 돌리는 데 한 시간 넘게 걸리므로
모델 없이 값으로 검증할 수 있게 떼어 놓았다.

테스트:
  services/ai-engine/.venv/bin/python -m pytest eval/tests -q
"""

import hashlib
import json
import os
import random

# 쌍의 유형. 운영의 세 엣지에 대응한다 — 변경↔대화(REFERENCE), 변경↔이슈(TRIGGERED_BY), 이슈↔대화(DISCUSSED_IN)
TYPES = ("change_conversation", "change_issue", "issue_conversation")
EDGE_LABELS = {
    "change_conversation": "REFERENCE",
    "change_issue": "TRIGGERED_BY",
    "issue_conversation": "DISCUSSED_IN",
}

# 순서가 곧 동점 처리 순서다 — 점수가 같으면 앞선 변형(데이터를 덜 건드린 쪽)이 이긴다
VARIANTS = ("all", "ko", "pre2025")

DEFAULT_MODEL = "Qwen/Qwen3-Embedding-0.6B"
DEFAULT_REVISION = "97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3"

# created_at(ISO 8601)과 문자열로 비교한다. 이 날짜 이전 글만 쓰는 변형은 생성형 AI가 흔해지기 전 글을 가리려는 것이다
PRE2025_CUTOFF = "2025-01-01"

# InformationRetrievalEvaluator 옵션 (질의·후보·이름 제외).
#
# query_prompt·corpus_prompt를 None이 아니라 빈 문자열로 주는 것이 핵심이다. 평가기는 질의를 encode_query,
# 후보를 encode_document로 인코딩하는데, 이 둘은 prompt가 None이면 모델이 가진 prompts["query"]·["document"]를
# 자동으로 붙인다. Qwen3-Embedding-0.6B의 prompts["query"]는 "Instruct: Given a web search query, ..." 지시문이라
# None으로 두면 지시문 붙은 질의로 평가하게 된다(같은 문장의 벡터가 encode()와 cosine 0.91로 달라지는 것을
# 2026-11-04에 확인). 학습도, 운영의 엣지 비교도, 1단계 측정도 지시문 없이 하므로(결정 1) 평가만 지시문을
# 받으면 학습·평가 조건이 어긋난다. 빈 문자열은 "지시문 없음"으로 취급되고 default_prompt_name 폴백도 막는다.
EVALUATOR_OPTIONS = {
    "ndcg_at_k": [10],
    "mrr_at_k": [10],
    "accuracy_at_k": [1, 5, 10],
    "precision_recall_at_k": [10],
    "map_at_k": [10],
    "query_prompt": "",
    "corpus_prompt": "",
    "write_csv": False,          # 평가 결과 CSV를 쓰지 않는다 — 점수는 metrics.json 하나에만 남긴다
    "show_progress_bar": False,  # nohup 로그에 진행 막대가 쌓이지 않게 한다
}


# --------------------------------------------------------------------------- 읽기·거르기

def read_pairs(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def filter_variant(rows: list[dict], variant: str) -> list[dict]:
    """변형에 맞는 쌍만 남긴다. train에만 쓴다 — val까지 거르면 변형끼리 점수를 비교할 수 없다."""
    if variant == "all":
        return list(rows)
    if variant == "ko":
        return [r for r in rows if r.get("has_korean") is True]
    if variant == "pre2025":
        # created_at이 비었거나 없으면 언제 쓴 글인지 모르므로 뺀다. ISO 문자열이라 사전순 비교가 시간순이다
        return [r for r in rows if _is_before_cutoff(r.get("created_at"))]
    raise ValueError(f"알 수 없는 변형: {variant!r} ({'|'.join(VARIANTS)})")


def _is_before_cutoff(created_at) -> bool:
    return isinstance(created_at, str) and created_at != "" and created_at < PRE2025_CUTOFF


def _group_by_type(rows: list[dict]) -> dict[str, list[dict]]:
    """TYPES 순서로 묶는다. 쌍이 없는 유형은 빠진다."""
    groups = {t: [] for t in TYPES}
    for row in rows:
        type_ = row["type"]
        if type_ not in groups:
            # 조용히 건너뛰면 새 유형이 생겼을 때 학습에서 통째로 빠져도 알 수 없다
            raise ValueError(f"알 수 없는 유형: {type_!r} (id={row.get('id')!r}) — TYPES에 없다")
        groups[type_].append(row)
    return {t: g for t, g in groups.items() if g}


def _limit(rows: list[dict], limit: int | None, seed: int, type_: str) -> list[dict]:
    """유형마다 limit개를 무작위로 고르되 원래 순서를 지킨다 (Mac 시험 실행용 — 같은 시드면 같은 표본)."""
    if limit is None or limit >= len(rows):
        return rows
    rng = random.Random(f"{seed}:{type_}")
    keep = sorted(rng.sample(range(len(rows)), limit))
    return [rows[i] for i in keep]


# --------------------------------------------------------------------------- 학습·검증 입력

def training_columns_by_type(rows: list[dict], limit_per_type: int | None = None, seed: int = 42) -> dict[str, dict]:
    """유형별 학습 열 {type: {"anchor": [...], "positive": [...]}}. 입력 순서를 지킨다.

    열을 anchor·positive 두 개만 두는 것이 중요하다. NO_DUPLICATES 샘플러가 행의 모든 열 값을 문자열로 비교해
    같은 값이 한 배치에 하나만 들어가게 하므로, type·repo 같은 열이 남으면 같은 저장소의 쌍이 한 배치에 하나만
    들어가 배치가 거의 다 채워지지 않는다.
    """
    out = {}
    for type_, group in _group_by_type(rows).items():
        picked = _limit(group, limit_per_type, seed, type_)
        out[type_] = {"anchor": [r["anchor"] for r in picked], "positive": [r["positive"] for r in picked]}
    return out


def eval_inputs_by_type(rows: list[dict], limit_per_type: int | None = None, seed: int = 42) -> dict[str, tuple]:
    """유형별 검색 평가 입력 {type: (queries, corpus, relevant_docs)}.

    앵커를 질의, 같은 유형의 짝을 후보로 놓는다. 후보는 그 유형의 짝뿐이다 — 다른 유형의 글이 섞이면 질문이
    쉬워지거나 어려워져 유형끼리 점수를 비교할 수 없다.

    같은 앵커 글은 질의 하나로 합치고 그 짝을 모두 정답으로 둔다(리뷰 한 줄에 댓글이 여럿인 경우). 따로 두면 한
    질의의 정답이 다른 질의에서는 오답 후보가 되어 점수가 깎인다. 같은 짝 글도 후보 하나로 합친다(여러 PR이 같은
    이슈를 가리킬 수 있다). id는 처음 나온 순서의 f"{type}-q{n}"·f"{type}-d{n}"이다.
    """
    out = {}
    for type_, group in _group_by_type(rows).items():
        picked = _limit(group, limit_per_type, seed, type_)
        query_ids: dict[str, str] = {}
        doc_ids: dict[str, str] = {}
        queries: dict[str, str] = {}
        corpus: dict[str, str] = {}
        relevant: dict[str, set[str]] = {}
        for row in picked:
            qid = query_ids.get(row["anchor"])
            if qid is None:
                qid = query_ids[row["anchor"]] = f"{type_}-q{len(query_ids)}"
                queries[qid] = row["anchor"]
            did = doc_ids.get(row["positive"])
            if did is None:
                did = doc_ids[row["positive"]] = f"{type_}-d{len(doc_ids)}"
                corpus[did] = row["positive"]
            relevant.setdefault(qid, set()).add(did)
        out[type_] = (queries, corpus, relevant)
    return out


def evaluator_name(type_: str) -> str:
    return f"val_{type_}"


def ndcg_key(type_: str) -> str:
    """평가기가 돌려주는 유형별 NDCG@10 키. 유사도가 cosine인 모델(Qwen3·MiniLM)에 대한 이름이다."""
    return f"{evaluator_name(type_)}_cosine_ndcg@10"


def macro_mean(scores) -> float:
    """유형별 점수의 산술 평균 — 세 유형 같은 가중이다.

    val은 변경↔대화 49%, 변경↔이슈 45%, 이슈↔대화 5%라 쌍을 합쳐 재면 이슈↔대화가 반영되지 않는다. 모델과 변형을
    고르는 점수라 학습기(SequentialEvaluator의 main_score_function)와 보고하는 쪽이 이 함수 하나를 쓴다.
    """
    values = list(scores)
    if not values:
        raise ValueError("점수가 하나도 없어 평균을 낼 수 없습니다")
    return sum(values) / len(values)


# --------------------------------------------------------------------------- 학습 기록

def epoch_records(log_history: list[dict], types) -> list[dict]:
    """학습기의 log_history에서 에폭 끝 평가마다 한 줄씩 뽑는다.

    train_loss는 직전 평가 이후 로그된 loss들의 평균이다(로그는 logging_steps마다 찍힌다).
    """
    records = []
    losses: list[float] = []
    for entry in log_history:
        if "eval_sequential_score" in entry:
            records.append({
                "epoch": round(float(entry["epoch"]), 2),
                "step": int(entry["step"]),
                "macro": float(entry["eval_sequential_score"]),
                "by_type": {t: float(entry[f"eval_{ndcg_key(t)}"]) for t in types},
                "train_loss": sum(losses) / len(losses) if losses else None,
            })
            losses = []
        elif "loss" in entry:
            losses.append(float(entry["loss"]))
    return records


def pick_best_epoch(records: list[dict], best_metric) -> int | None:
    """학습기가 고른 최고 점수(best_metric)를 낸 에폭. 점수가 같으면 먼저 나온 에폭이다(학습기도 같은 규칙)."""
    for record in records:
        if record["macro"] == best_metric:
            return int(round(record["epoch"]))
    return None


# --------------------------------------------------------------------------- 변형 결과 모으기

def summarize_run(run_dir: str, variants=VARIANTS) -> dict:
    """<run_dir>/<변형>/ 결과를 모아 한눈에 비교하는 dict를 만든다.

      {"base": {"by_type", "macro"} | None,         성공한 첫 변형이 잰 학습 전 점수
       "variants": {변형: {"status": "ok"|"failed"|"missing", ...}},
       "chosen": 변형 이름 | None}                  final macro 1위. 동점이면 VARIANTS 순서, 성공이 없으면 None

    성공 여부는 train.py가 남기는 표시로만 판단한다. <변형>/FAILED가 있으면 metrics.json이 있어도 실패다(이전 시도의
    metrics.json이 남아 실패한 실행을 성공으로 읽는 것을 막는다). FAILED는 gpu_run.sh도 쓴다 — train.py가 예외를
    남기지 못하고 죽은 경우(메모리 부족으로 프로세스가 강제 종료 등)다.
    """
    unknown = [v for v in variants if v not in VARIANTS]
    if unknown:
        raise ValueError(f"알 수 없는 변형: {', '.join(unknown)} ({'|'.join(VARIANTS)})")

    entries: dict[str, dict] = {}
    base = None
    for variant in variants:
        entry, variant_base = _load_variant(os.path.join(run_dir, variant))
        entries[variant] = entry
        if base is None and variant_base is not None:
            base = variant_base

    chosen = None
    for variant in VARIANTS:  # 호출자가 준 순서가 아니라 VARIANTS 순서로 훑는다 — 동점 규칙이 인자 순서에 흔들리지 않게
        entry = entries.get(variant)
        if entry is None or entry["status"] != "ok":
            continue
        # 엄격히 큰 점수만 갈아치우므로 동점이면 앞선 변형이 남는다
        if chosen is None or entry["final"]["macro"] > entries[chosen]["final"]["macro"]:
            chosen = variant
    return {"base": base, "variants": entries, "chosen": chosen}


def _load_variant(variant_dir: str) -> tuple[dict, dict | None]:
    failed_path = os.path.join(variant_dir, "FAILED")
    metrics_path = os.path.join(variant_dir, "metrics.json")

    if os.path.exists(failed_path):
        return {"status": "failed", "reason": _first_line(failed_path)}, None
    if not os.path.exists(metrics_path):
        return {"status": "missing"}, None

    try:
        with open(metrics_path, encoding="utf-8") as f:
            metrics = json.load(f)
    except (OSError, ValueError) as exc:
        return {"status": "failed", "reason": f"metrics.json을 읽을 수 없습니다: {exc}"}, None
    if metrics.get("status") != "ok":
        return {"status": "failed", "reason": f"metrics.json의 status가 {metrics.get('status')!r}입니다"}, None

    entry = {
        "status": "ok",
        "final": {"by_type": metrics["final"]["by_type"], "macro": metrics["final"]["macro"]},
        "best_epoch": metrics.get("best_epoch"),
        "n_train": metrics.get("n_train"),
    }
    base = {"by_type": metrics["base"]["by_type"], "macro": metrics["base"]["macro"]}
    return entry, base


def _first_line(path: str, limit: int = 300) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if line.strip():
                    return line.strip()[:limit]
    except OSError:
        pass
    return "(사유 없음)"


def format_summary(summary: dict) -> str:
    """summarize_run 결과를 표로 만든다. 값은 모두 검증셋 NDCG@10이다."""
    columns = [f"{EDGE_LABELS[t]:>12}" for t in TYPES]
    lines = [
        "검증셋 NDCG@10 (" + ", ".join(f"{EDGE_LABELS[t]}={t}" for t in TYPES) + ")",
        f"{'variant':<9} {'status':<8} {'n_train':>8} {'best_ep':>7} " + " ".join(columns) + f" {'macro':>8}",
    ]

    def score_cells(scores: dict | None) -> str:
        if scores is None:
            return " ".join(f"{'-':>12}" for _ in TYPES) + f" {'-':>8}"
        cells = " ".join(f"{scores['by_type'][t]:>12.4f}" if t in scores["by_type"] else f"{'-':>12}" for t in TYPES)
        return f"{cells} {scores['macro']:>8.4f}"

    if summary["base"] is not None:
        lines.append(f"{'(base)':<9} {'-':<8} {'-':>8} {'-':>7} " + score_cells(summary["base"]))
    for variant, entry in summary["variants"].items():
        if entry["status"] == "ok":
            n_train = sum(entry["n_train"].values()) if entry.get("n_train") else "-"
            best = entry["best_epoch"] if entry.get("best_epoch") is not None else "-"
            lines.append(f"{variant:<9} {'ok':<8} {n_train:>8} {best:>7} " + score_cells(entry["final"]))
        else:
            lines.append(f"{variant:<9} {entry['status']:<8} {'-':>8} {'-':>7} " + score_cells(None))

    for variant, entry in summary["variants"].items():
        if entry["status"] == "failed":
            lines.append(f"  {variant}: 실패 — {entry['reason']}")
        elif entry["status"] == "missing":
            lines.append(f"  {variant}: 결과가 없습니다")

    chosen = summary["chosen"]
    if chosen is None:
        lines.append("선택: 없음 (성공한 변형이 없습니다)")
    else:
        macro = summary["variants"][chosen]["final"]["macro"]
        gain = f", base 대비 {macro - summary['base']['macro']:+.4f}" if summary["base"] is not None else ""
        lines.append(f"선택: {chosen} (macro {macro:.4f}{gain})")
    return "\n".join(lines)


# --------------------------------------------------------------------------- 파일

def write_json_atomic(path: str, doc: dict) -> None:
    """임시 파일에 다 쓴 뒤 바꿔치기한다 — 도중에 죽어도 metrics.json이 반쯤 쓰인 채 남아 성공으로 읽히지 않는다."""
    tmp = f"{path}.tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, path)


def sha256_file(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()
