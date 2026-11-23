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

# 데이터 계층. 세 모듈 모두 비밀(whycode/staging/rds·mq·neo4j)을 만들고, 그 비밀을 읽을 서비스의 실행 역할에 읽기 정책을 붙인다.
# 그래서 플랫폼 모듈의 실행 역할 이름을 받는다. 비밀번호는 사람이 정하지 않고 모듈이 만들어 비밀에 넣는다.
module "rds" {
  source = "../../modules/rds"

  environment          = "staging"
  subnet_ids           = module.network.private_subnet_ids
  security_group_id    = module.network.security_group_ids.rds
  execution_role_names = module.platform.execution_role_names

  # 스테이징은 몇 시간 올렸다 내리는 환경이다. 최종 스냅샷이 남으면 같은 이름으로 다시 만들 때 막히고 destroy가 지울 수도 없다.
  # 운영은 이 값을 두지 않아 기본값(스냅샷을 남긴다)을 쓴다.
  skip_final_snapshot = true
}

# 단일 인스턴스는 서브넷 하나에 놓인다. 서비스와 같은 AZ에 두려고 첫 번째 비공개 서브넷을 쓴다(AZ 간 전송 요금을 피한다).
module "mq" {
  source = "../../modules/mq"

  environment          = "staging"
  subnet_id            = module.network.private_subnet_ids[0]
  security_group_id    = module.network.security_group_ids.mq
  execution_role_names = module.platform.execution_role_names
}

module "neo4j" {
  source = "../../modules/neo4j"

  environment          = "staging"
  subnet_id            = module.network.private_subnet_ids[0]
  security_group_id    = module.network.security_group_ids.neo4j
  execution_role_names = module.platform.execution_role_names

  # 스테이징 destroy가 비어 있지 않은 백업 버킷에서 멈추지 않게 한다. 운영은 기본값(꺼짐)이라 백업이 있으면 destroy가 멈춘다.
  backup_bucket_force_destroy = true
}

