# staging 환경 루트: 모듈을 연결하고 환경 값을 넘긴다. 자원은 모듈이 만든다.
# 상태는 S3 backend의 staging/terraform.tfstate에 있다(versions.tf). prod와 상태가 따로라 각자 지우고 다시 만들 수 있다.
#
# 환경 이름은 변수나 locals로 감추지 않고 모듈 호출에 글자 그대로 둔다. tests/wiring.tftest.hcl이 이 파일을 읽어
# 스테이징 루트가 운영 GitHub 환경을 다루지 않는지 확인한다.

# bootstrap이 만든 GitHub OIDC provider를 주소로 찾는다. 계정당 하나라 staging·prod가 같이 쓴다.
# bootstrap을 apply하지 않았으면 여기서 plan이 실패한다 — 순서가 틀렸다는 뜻이다.
data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

module "network" {
  source = "../../modules/network"

  name = "whycode-staging"
}

module "platform" {
  source = "../../modules/platform"

  environment              = "staging"
  github_repository        = var.github_repository
  github_environment       = "staging"
  github_oidc_provider_arn = data.aws_iam_openid_connect_provider.github.arn
  alert_email              = var.alert_email
  monthly_budget_usd       = var.monthly_budget_usd
}

module "alb" {
  source = "../../modules/alb"

  name              = "whycode-staging"
  vpc_id            = module.network.vpc_id
  security_group_id = module.network.security_group_ids.alb
  subnet_ids        = module.network.private_subnet_ids
}

module "edge" {
  source = "../../modules/edge"

  name                        = "whycode-staging"
  vpc_id                      = module.network.vpc_id
  alb_arn                     = module.alb.alb_arn
  alb_dns_name                = module.alb.alb_dns_name
  alb_listener_port           = module.alb.listener_port
  alb_security_group_id       = module.network.security_group_ids.alb
  ci_deploy_role_name         = module.platform.ci_deploy_role_name
  origin_read_timeout_seconds = var.origin_read_timeout_seconds

  # CloudFront VPC 원본에는 인터넷 게이트웨이가 연결된 VPC와 준비된 ALB 리스너가 필요하다.
  depends_on = [module.network, module.alb]
}
