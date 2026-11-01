#!/usr/bin/env python3
"""서비스별 라인 커버리지를 PR 댓글 하나로 남긴다.

.github/workflows/test.yml의 coverage-comment 잡이 부른다. 테스트 잡들이 올린 커버리지 아티팩트를
내려받은 폴더(--artifacts-dir)에서 서비스별 합계를 읽어 표를 만든다. 이 PR에 이미 단 댓글이 있으면
그 내용을 바꾸고 없으면 새로 달아서, 푸시할 때마다 댓글이 쌓이지 않는다.

내 댓글은 본문 첫 줄의 표식(MARKER)과 작성자(github-actions 봇)로 알아본다. PR에는 리뷰 봇·사람
댓글이 섞여 있어서 "마지막 댓글을 고친다"는 방식은 남의 댓글을 건드릴 수 있다.

읽는 파일 — 리포트 설정을 바꾸면 여기도 함께 고친다.
- backend·pipeline-worker: JaCoCo의 jacocoTestReport.xml. <report>의 직계 LINE 카운터가 전체 합계다.
- ai-engine: pytest-cov의 coverage.xml. 루트 <coverage>의 lines-covered / lines-valid다.

--dry-run은 댓글을 달지 않고 본문만 출력한다(로컬에서 모양을 볼 때 쓴다).
"""

import argparse
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

MARKER = "<!-- coverage-report -->"
BOT_LOGIN = "github-actions[bot]"


@dataclass(frozen=True)
class Service:
    name: str      # 표에 보이는 이름
    artifact: str  # test.yml에서 올린 아티팩트 이름
    report: str    # 아티팩트 안에서 찾을 XML 파일 이름
    fmt: str       # "jacoco" | "cobertura"


SERVICES = (
    Service("backend", "backend-coverage", "jacocoTestReport.xml", "jacoco"),
    Service("pipeline-worker", "pipeline-worker-coverage", "jacocoTestReport.xml", "jacoco"),
    Service("ai-engine", "ai-engine-coverage", "coverage.xml", "cobertura"),
)


@dataclass(frozen=True)
class LineCoverage:
    covered: int
    total: int

    @property
    def percent(self) -> float:
        return self.covered / self.total * 100


def parse_jacoco(path: Path) -> LineCoverage:
    # 패키지·클래스 안의 카운터는 더 아래 단계에 있어, 직계 <counter>만 보면 전체 합계와 섞이지 않는다.
    for counter in ET.parse(path).getroot().findall("counter"):
        if counter.get("type") == "LINE":
            covered = int(counter.get("covered"))
            return LineCoverage(covered, covered + int(counter.get("missed")))
    raise ValueError("전체 LINE 카운터가 없습니다")


def parse_cobertura(path: Path) -> LineCoverage:
    root = ET.parse(path).getroot()
    return LineCoverage(int(root.get("lines-covered")), int(root.get("lines-valid")))


PARSERS = {"jacoco": parse_jacoco, "cobertura": parse_cobertura}


def read_coverage(artifacts_dir: Path, service: Service) -> LineCoverage | None:
    """합계를 못 읽으면 None. 테스트가 실패해 아티팩트가 없거나 리포트 형식이 바뀐 경우다."""
    found = sorted((artifacts_dir / service.artifact).rglob(service.report), key=lambda p: len(p.parts))
    if not found:
        return None
    try:
        coverage = PARSERS[service.fmt](found[0])
    except (ET.ParseError, ValueError, TypeError) as e:
        print(f"::warning::{service.name} 커버리지 리포트를 읽지 못했습니다: {e}")
        return None
    return coverage if coverage.total > 0 else None


def render(results: dict[str, LineCoverage | None], head_sha: str, run_url: str) -> str:
    rows = []
    for name, coverage in results.items():
        if coverage is None:
            cell = "측정하지 못함 — 테스트가 실패했거나 리포트가 없습니다"
        else:
            cell = f"{coverage.percent:.1f}% ({coverage.covered:,} / {coverage.total:,}줄)"
        rows.append(f"| {name} | {cell} |")
    return "\n".join([
        MARKER,
        "### 테스트 커버리지 (라인 기준)",
        "",
        "| 서비스 | 커버리지 |",
        "|---|---|",
        *rows,
        "",
        f"PR 최신 커밋 `{head_sha[:7]}` 기준 · web-dashboard는 테스트 러너가 없어 제외합니다.",
        f"상세 HTML 리포트는 [이 실행의 Artifacts]({run_url})에서 받을 수 있습니다(7일 보관).",
    ])


def gh_api(*args: str) -> str:
    return subprocess.run(["gh", "api", *args], check=True, capture_output=True, text=True).stdout


def find_existing_comment_id(repo: str, pr: int, login: str = BOT_LOGIN, marker: str = MARKER) -> int | None:
    # PR 일반 댓글은 issues API에 있다. 페이지마다 jq가 돌아 일치하는 id만 한 줄씩 나온다.
    out = gh_api(
        "--paginate", f"repos/{repo}/issues/{pr}/comments?per_page=100",
        "--jq", f'.[] | select(.user.login == "{login}" and ((.body // "") | contains("{marker}"))) | .id',
    )
    ids = out.split()
    return int(ids[0]) if ids else None


def upsert_comment(repo: str, pr: int, body: str) -> None:
    comment_id = find_existing_comment_id(repo, pr)
    if comment_id is None:
        gh_api(f"repos/{repo}/issues/{pr}/comments", "-f", f"body={body}")
        print("커버리지 댓글을 새로 달았습니다.")
    else:
        gh_api("-X", "PATCH", f"repos/{repo}/issues/comments/{comment_id}", "-f", f"body={body}")
        print(f"커버리지 댓글을 갱신했습니다 (id {comment_id}).")


def default_run_url() -> str:
    server = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
    return f"{server}/{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}"


def main() -> int:
    parser = argparse.ArgumentParser(description="서비스별 라인 커버리지를 PR 댓글 하나로 남긴다.")
    parser.add_argument("--artifacts-dir", type=Path, default=Path("coverage"),
                        help="커버리지 아티팩트를 내려받은 폴더 (그 아래에 아티팩트 이름별 폴더)")
    parser.add_argument("--head-sha", default=os.environ.get("HEAD_SHA", ""))
    parser.add_argument("--run-url", default=default_run_url())
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--pr", default=os.environ.get("PR_NUMBER", ""))
    parser.add_argument("--dry-run", action="store_true", help="댓글을 달지 않고 본문만 출력한다")
    args = parser.parse_args()

    body = render({s.name: read_coverage(args.artifacts_dir, s) for s in SERVICES}, args.head_sha, args.run_url)
    print(body)
    if args.dry_run:
        return 0
    if not args.repo or not args.pr:
        parser.error("댓글을 달려면 --repo(GITHUB_REPOSITORY)와 --pr(PR_NUMBER)가 필요합니다")

    try:
        upsert_comment(args.repo, int(args.pr), body)
    except subprocess.CalledProcessError as e:
        print("::error::PR 댓글을 쓰지 못했습니다 (gh api 오류)")
        print(e.stderr, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
