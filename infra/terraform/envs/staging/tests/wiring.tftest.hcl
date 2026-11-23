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

  # 모듈 간에 넘기는 계산값은 AWS provider가 형식을 검사하므로 실제와 같은 모양으로 채운다.
  mock_resource "aws_lb" {
    defaults = {
      arn      = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/whycode-staging-alb/50dc6c495c0c9188"
      dns_name = "internal-whycode-staging-alb.ap-northeast-2.elb.amazonaws.com"
    }
  }

  mock_resource "aws_lb_listener" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:listener/app/whycode-staging-alb/50dc6c495c0c9188/f2f7dc8efc522ab2"
    }
  }

  mock_resource "aws_lb_target_group" {
    defaults = {
      arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/whycode-staging-test/6d0ecf831eec9f09"
    }
  }

  mock_resource "aws_cloudfront_function" {
    defaults = {
      arn = "arn:aws:cloudfront::123456789012:function/whycode-staging-test"
    }
  }

  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/E123EXAMPLE"
      domain_name = "d123example.cloudfront.net"
      id          = "E123EXAMPLE"
    }
  }

  mock_resource "aws_s3_bucket" {
    defaults = {
      arn                         = "arn:aws:s3:::whycode-staging-test"
      bucket_regional_domain_name = "whycode-staging-test.s3.ap-northeast-2.amazonaws.com"
    }
  }

  mock_data "aws_security_group" {
    defaults = {
      id = "sg-0123456789abcdef0"
    }
  }

  # Neo4j 모듈이 읽는 값: AMI id(SSM 파라미터)와 서브넷의 AZ.
  mock_data "aws_ssm_parameter" {
    defaults = {
      insecure_value = "ami-0123456789abcdef0"
    }
  }

  mock_data "aws_subnet" {
    defaults = {
      availability_zone = "ap-northeast-2a"
    }
  }
}

# MQ 모듈의 관리자 비밀번호는 random provider가 만든다. 브로커가 비밀번호 길이(12자 이상)를 검사하므로 형식을 갖춘 값으로 고정한다.
mock_provider "random" {
  mock_resource "random_password" {
    defaults = {
      result = "Ab3dEf6hJk9mNp2qRs5tUv8wXy1zAb4c"
    }
  }
}

# 브로커의 엔드포인트는 apply 뒤에야 생기는 값이라 모듈이 amqps 주소를 뽑으려면 형식을 갖춘 값이 있어야 한다.
# 순서를 일부러 https(Prometheus)가 앞에 오게 해서 모듈이 amqps만 골라 쓰는지도 함께 본다.
override_resource {
  target = module.mq.aws_mq_broker.this
  values = {
    id  = "b-12345678-90ab-cdef-1234-567890abcdef"
    arn = "arn:aws:mq:ap-northeast-2:123456789012:broker:whycode-staging-rabbitmq:b-12345678-90ab-cdef-1234-567890abcdef"
    instances = [
      {
        console_url = "https://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.on.aws"
        endpoints = [
          "https://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.on.aws:16001",
          "amqps://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.on.aws:5671",
        ]
        ip_address = "10.20.10.10"
      },
    ]
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

override_data {
  target = module.edge.data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

override_data {
  target = module.neo4j.data.aws_caller_identity.current
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

  assert {
    condition     = output.web_bucket_name == module.edge.web_bucket_name
    error_message = "web_bucket_name 출력이 엣지 모듈 값과 다르다."
  }

  assert {
    condition     = output.cloudfront_distribution_id == "E123EXAMPLE" && output.cloudfront_domain_name == "d123example.cloudfront.net"
    error_message = "CloudFront 배포 ID와 스테이징 주소는 엣지 모듈에서 가져와야 한다."
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

  # 네트워크·ALB·엣지 모듈의 이름은 출력으로 드러나지 않아(보안 그룹·서브넷·ALB·버킷 이름에만 들어간다) 소스에서 확인한다.
  # 세 모듈이 같은 이름을 쓰므로 파일 전체에서 한 줄만 찾으면 한 모듈의 이름이 틀려도 통과한다. 그래서 모듈 블록 안에서 찾는다.
  # 모듈 블록 안에는 중괄호가 없어 `[^}]*`가 그 블록까지만 본다.
  assert {
    condition     = can(regex("module \"network\" \\{[^}]*\\n\\s*name\\s*=\\s*\"whycode-staging\"\\s*\\n", file("${path.module}/main.tf")))
    error_message = "main.tf가 network 모듈에 name = \"whycode-staging\"을 넘기지 않는다."
  }

  assert {
    condition     = can(regex("module \"alb\" \\{[^}]*\\n\\s*name\\s*=\\s*\"whycode-staging\"\\s*\\n", file("${path.module}/main.tf")))
    error_message = "main.tf가 alb 모듈에 name = \"whycode-staging\"을 넘기지 않는다."
  }

  assert {
    condition     = can(regex("module \"edge\" \\{[^}]*\\n\\s*name\\s*=\\s*\"whycode-staging\"\\s*\\n", file("${path.module}/main.tf")))
    error_message = "main.tf가 edge 모듈에 name = \"whycode-staging\"을 넘기지 않는다."
  }
}

# 데이터 계층의 출력이 모듈 값을 그대로 내보내는지 본다 — 사람이 apply 뒤 이 값으로 Neo4j에 접속하고 RDS·MQ 주소를 확인한다.
run "outputs_expose_the_data_layer" {
  assert {
    condition     = output.rds_address == module.rds.address
    error_message = "rds_address 출력이 RDS 모듈 값과 다르다."
  }

  assert {
    condition     = output.mq_broker_id == "b-12345678-90ab-cdef-1234-567890abcdef" && output.mq_amqp_host == "b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.on.aws"
    error_message = "MQ 출력은 브로커 id와 amqps 엔드포인트의 호스트여야 한다(https 엔드포인트가 아니다). 지금 값: ${output.mq_broker_id} / ${output.mq_amqp_host}"
  }

  assert {
    condition     = output.neo4j_instance_id == module.neo4j.instance_id && output.neo4j_private_ip == module.neo4j.private_ip
    error_message = "Neo4j 인스턴스 id·사설 IP 출력이 Neo4j 모듈 값과 다르다."
  }

  assert {
    condition     = output.neo4j_backup_bucket_name == "whycode-staging-neo4j-backup-123456789012"
    error_message = "Neo4j 백업 버킷 이름은 whycode-staging-neo4j-backup-<계정 번호>여야 한다. 지금 값: ${output.neo4j_backup_bucket_name}"
  }
}

# 데이터 계층 모듈이 스테이징 값과 알맞은 보안 그룹·서브넷을 받는지 소스에서 확인한다. 보안 그룹이 서로 바뀌어도(예: RDS가 MQ의 그룹을 받아도)
# 모듈 테스트와 가짜 provider로는 드러나지 않고 apply 뒤 연결이 안 될 때 비로소 보인다. 모듈 블록 안에는 중괄호가 없어 `[^}]*`가 그 블록까지만 본다.
run "data_layer_modules_get_staging_values_and_their_own_security_groups" {
  command = plan

  assert {
    condition = alltrue([
      for m in ["rds", "mq", "neo4j"] :
      can(regex("module \"${m}\" \\{[^}]*\\n\\s*environment\\s*=\\s*\"staging\"\\s*\\n", file("${path.module}/main.tf")))
    ])
    error_message = "rds·mq·neo4j 모듈 호출마다 environment = \"staging\"을 글자 그대로 넘겨야 한다."
  }

  assert {
    condition = alltrue([
      for m, sg in { rds = "rds", mq = "mq", neo4j = "neo4j" } :
      can(regex("module \"${m}\" \\{[^}]*\\n\\s*security_group_id\\s*=\\s*module\\.network\\.security_group_ids\\.${sg}\\s*\\n", file("${path.module}/main.tf")))
    ])
    error_message = "각 데이터 모듈은 자기 보안 그룹(rds→security_group_ids.rds, mq→.mq, neo4j→.neo4j)을 받아야 한다."
  }

  assert {
    condition = alltrue([
      for m in ["rds", "mq", "neo4j"] :
      can(regex("module \"${m}\" \\{[^}]*\\n\\s*execution_role_names\\s*=\\s*module\\.platform\\.execution_role_names\\s*\\n", file("${path.module}/main.tf")))
    ])
    error_message = "각 데이터 모듈은 플랫폼 모듈의 execution_role_names를 받아 자기 비밀을 읽을 서비스의 실행 역할에 정책을 붙인다."
  }

  # RDS는 서브넷 그룹이 서로 다른 AZ의 서브넷 둘을 요구한다. 단일 인스턴스인 MQ·Neo4j는 서브넷 하나(첫 AZ)에 둔다.
  assert {
    condition     = can(regex("module \"rds\" \\{[^}]*\\n\\s*subnet_ids\\s*=\\s*module\\.network\\.private_subnet_ids\\s*\\n", file("${path.module}/main.tf")))
    error_message = "rds 모듈은 비공개 서브넷 목록 전체(module.network.private_subnet_ids)를 받아야 한다."
  }

  assert {
    condition = alltrue([
      for m in ["mq", "neo4j"] :
      can(regex("module \"${m}\" \\{[^}]*\\n\\s*subnet_id\\s*=\\s*module\\.network\\.private_subnet_ids\\[0\\]\\s*\\n", file("${path.module}/main.tf")))
    ])
    error_message = "mq·neo4j 모듈은 첫 번째 비공개 서브넷(module.network.private_subnet_ids[0])을 받아야 한다."
  }
}

# 스테이징은 몇 시간 올렸다 내리는 환경이다. destroy가 최종 스냅샷(같은 이름이 다음 apply를 막는다)이나 비어 있지 않은 백업 버킷에서 멈추지 않게 한다.
# 이 두 값은 스테이징에서만 켜는 것이라 모듈 기본값(꺼짐)이 아니라 이 루트가 정한다.
run "staging_data_layer_can_be_destroyed_without_leftovers" {
  command = plan

  assert {
    condition     = can(regex("module \"rds\" \\{[^}]*\\n\\s*skip_final_snapshot\\s*=\\s*true\\s*\\n", file("${path.module}/main.tf")))
    error_message = "스테이징 RDS는 skip_final_snapshot = true여야 한다(최종 스냅샷이 남으면 같은 이름으로 다시 만들 때 막힌다)."
  }

  assert {
    condition     = can(regex("module \"neo4j\" \\{[^}]*\\n\\s*backup_bucket_force_destroy\\s*=\\s*true\\s*\\n", file("${path.module}/main.tf")))
    error_message = "스테이징 Neo4j 백업 버킷은 backup_bucket_force_destroy = true여야 한다(비어 있지 않은 버킷이 destroy를 멈춘다)."
  }
}
