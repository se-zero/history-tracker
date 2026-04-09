import re

_EXCLUDED_PREFIXES = (
    # 빌드 아티팩트
    "dist/",
    "build/",
    ".next/",
    "out/",
    # 의존성
    "node_modules/",
    ".gradle/",
    "target/",
    "__pycache__/",
    ".venv/",
    # IDE
    ".idea/",
    ".vscode/",
    # 테스트 스냅샷 / 커버리지
    "__snapshots__/",
    "coverage/",
    ".nyc_output/",
)

_EXCLUDED_NAMES = {
    # 락 파일
    "package-lock.json",
    "yarn.lock",
    "pnpm-lock.yaml",
    "poetry.lock",
    "Pipfile.lock",
    "Gemfile.lock",
    # OS 아티팩트
    ".DS_Store",
    "Thumbs.db",
}

_EXCLUDED_EXTENSIONS = re.compile(
    r"\.(min\.js|min\.css|map|pyc|class|jar|war|zip|tar\.gz|lock"
    # 바이너리/미디어
    r"|png|jpg|jpeg|gif|svg|ico|webp|mp4|mp3|pdf"
    # 폰트
    r"|ttf|woff|woff2|eot"
    # 스냅샷/로그/DB
    r"|snap|log|sqlite|db"
    # 생성 코드
    r"|pb\.go|_pb2\.py)$"
)


def should_skip(path: str) -> bool:
    if not path:
        return True
    name = path.split("/")[-1]
    if name in _EXCLUDED_NAMES:
        return True
    if any(path.startswith(p) or f"/{p}" in path for p in _EXCLUDED_PREFIXES):
        return True
    if _EXCLUDED_EXTENSIONS.search(path):
        return True
    return False
