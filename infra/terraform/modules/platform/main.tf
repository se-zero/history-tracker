# 실행 역할 신뢰 정책(aws:SourceAccount)에 쓸 계정 번호. 저장소가 공개라 계정 번호를 코드에 적지 않고 읽어 온다.
data "aws_caller_identity" "current" {}

locals {
  # 서비스마다 ECR 저장소·로그 그룹·실행 역할이 하나씩이다. for_each 키는 plan 때 알 수 있는 정적 값이어야 해서
  # 리소스 id가 아니라 이 고정 집합을 키로 쓴다.
  services = toset(["backend", "pipeline-worker", "ai-engine"])

  # 이름 접두사를 한 곳에서 만들어, 환경을 바꾸면 모든 이름이 같이 바뀌게 한다(예: whycode-staging).
  name_prefix = "${var.project}-${var.environment}"
}
