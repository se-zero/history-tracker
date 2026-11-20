# prod 환경 루트: 모듈을 연결하고 환경 값을 넘긴다. 자원은 모듈이 만든다.
# 상태는 S3 backend의 prod/terraform.tfstate에 있다(versions.tf). staging과 상태가 따로라 각자 지우고 다시 만들 수 있다.
#
# 환경 이름은 변수나 locals로 감추지 않고 모듈 호출에 글자 그대로 둔다. tests/wiring.tftest.hcl이 이 파일을 읽어
# 운영 루트가 스테이징 GitHub 환경을 다루지 않는지 확인한다.
# AWS 쪽 이름과 디렉터리는 prod, GitHub 환경 이름은 production이다(모듈의 검증이 이 둘만 받는다).

# bootstrap이 만든 GitHub OIDC provider를 주소로 찾는다. 계정당 하나라 staging·prod가 같이 쓴다.
# bootstrap을 apply하지 않았으면 여기서 plan이 실패한다 — 순서가 틀렸다는 뜻이다.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

module "network" {
  source = "../../modules/network"

  name = "whycode-prod"
}

module "platform" {
  source = "../../modules/platform"

  environment              = "prod"
  github_repository        = var.github_repository
  github_environment       = "production"
  github_oidc_provider_arn = data.aws_iam_openid_connect_provider.github.arn
  alert_email              = var.alert_email
  monthly_budget_usd       = var.monthly_budget_usd
}
