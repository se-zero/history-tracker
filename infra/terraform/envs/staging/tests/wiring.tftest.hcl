# staging 환경 루트의 연결 검사. 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/envs/staging test
#
# 환경 루트는 모듈에 값을 넘기는 곳이다. 모듈 테스트가 모두 통과해도 여기서 틀리면 환경이 어긋난다.
# 특히 staging과 prod의 환경 이름이 뒤바뀌면 운영 CI 역할이 스테이징 GitHub 환경을 신뢰하게 된다 — 스테이징 배포가 운영 역할을 빌릴 수 있다.
# 환경 루트는 모듈 안의 자원이 아니라 출력만 볼 수 있어서, 신뢰 정책에 들어가는 GitHub 환경 이름은 main.tf 소스에서 직접 확인한다.

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {
  # 구독의 topic_arn은 apply 때 ARN 형식을 검사하므로 주제의 ARN만 형식을 갖춘 값으로 고정한다(가짜 provider의 무작위 8글자는 ARN이 아니다).
  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:ap-northeast-2:123456789012:whycode-staging-alerts"
    }
  }
}

# bootstrap이 만든 OIDC provider는 주소로 찾는다. 가짜 provider 아래에서는 ARN이 무작위라 12자리 번호가 든 값으로 고정한다.
override_data {
  target = data.aws_iam_openid_connect_provider.github
  values = {
    arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  }
}

# 플랫폼 모듈이 실행 역할 신뢰 정책에 쓰는 계정 번호.
override_data {
  target = module.platform.data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

# 개인 값은 레포에 없다(*.auto.tfvars). 테스트에는 예시 값만 쓴다.
variables {
  alert_email        = "ops@example.com"
  monthly_budget_usd = 50
}

# 스테이징 이름이 prod로 새지 않는지 본다 — 이름에 환경이 들어가므로 두 환경이 같은 계정에서 이름이 겹치지 않는다.
run "names_carry_the_staging_environment" {
  assert {
    condition     = module.platform.log_group_names == { backend = "/whycode/staging/backend", "pipeline-worker" = "/whycode/staging/pipeline-worker", "ai-engine" = "/whycode/staging/ai-engine" }
    error_message = "로그 그룹 이름이 /whycode/staging/<서비스>가 아니다. 지금 값: ${jsonencode(module.platform.log_group_names)}"
  }

  assert {
    condition     = module.platform.ci_deploy_role_name == "whycode-staging-ci-deploy"
    error_message = "배포 역할 이름이 whycode-staging-ci-deploy가 아니다. 지금 값: ${module.platform.ci_deploy_role_name}"
  }

  assert {
    condition     = alltrue([for n in values(module.platform.execution_role_names) : startswith(n, "whycode-staging-")])
    error_message = "실행 역할 이름이 whycode-staging-으로 시작하지 않는다. 지금 값: ${jsonencode(module.platform.execution_role_names)}"
  }

  assert {
    condition     = toset(keys(module.platform.secret_arns)) == toset(["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"])
    error_message = "비밀 껍데기 키가 계약의 6개와 다르다. 지금 키: ${jsonencode(keys(module.platform.secret_arns))}"
  }
}

# 환경 루트의 출력이 모듈 값을 그대로 내보내는지 본다 — 사람이 apply 뒤 이 값으로 GitHub 환경 변수와 첫 이미지 푸시를 설정한다.
run "outputs_expose_what_the_operator_needs" {
  assert {
    condition     = toset(keys(output.ecr_repository_urls)) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "ecr_repository_urls의 키가 서비스 3개가 아니다. 지금 키: ${jsonencode(keys(output.ecr_repository_urls))}"
  }

  assert {
    condition     = output.ci_push_role_arn == module.platform.ci_push_role_arn && output.ci_deploy_role_arn == module.platform.ci_deploy_role_arn
    error_message = "CI 역할 ARN 출력이 플랫폼 모듈 값과 다르다."
  }

  assert {
    condition     = output.nat_public_ip == module.network.nat_public_ip
    error_message = "nat_public_ip 출력이 네트워크 모듈 값과 다르다."
  }

  assert {
    condition     = output.sns_topic_arn == module.platform.sns_topic_arn
    error_message = "sns_topic_arn 출력이 플랫폼 모듈 값과 다르다."
  }
}

# CI 역할이 신뢰하는 GitHub 환경 이름을 소스에서 확인한다. 환경 이름은 모듈 안에서 정책으로 바뀌어 여기서는 읽을 수 없다.
# staging 루트가 production을 넘기면 스테이징 배포가 역할을 못 빌리고, 반대로 prod 루트가 staging을 넘기면 스테이징 배포가 운영 역할을 빌린다.
run "ci_roles_trust_only_the_staging_github_environment" {
  command = plan

  assert {
    condition     = can(regex("(?m)^\\s*github_environment\\s*=\\s*\"staging\"\\s*$", file("${path.module}/main.tf")))
    error_message = "main.tf가 플랫폼 모듈에 github_environment = \"staging\"을 넘기지 않는다."
  }

  assert {
    condition     = !can(regex("\"production\"", file("${path.module}/main.tf")))
    error_message = "staging 루트의 main.tf에 \"production\"이 들어 있다. 스테이징 루트가 운영 GitHub 환경을 다뤄서는 안 된다."
  }

  assert {
    condition     = can(regex("(?m)^\\s*environment\\s*=\\s*\"staging\"\\s*$", file("${path.module}/main.tf")))
    error_message = "main.tf가 플랫폼 모듈에 environment = \"staging\"을 넘기지 않는다."
  }

  # 네트워크 모듈의 이름은 출력으로 드러나지 않아(보안 그룹·서브넷 이름에만 들어간다) 소스에서 확인한다.
  assert {
    condition     = can(regex("(?m)^\\s*name\\s*=\\s*\"whycode-staging\"\\s*$", file("${path.module}/main.tf")))
    error_message = "main.tf가 네트워크 모듈에 name = \"whycode-staging\"을 넘기지 않는다."
  }
}
