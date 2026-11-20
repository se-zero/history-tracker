# T1 플랫폼 모듈(ECR·로그 그룹·비밀 껍데기·ECS 실행 역할·CI 역할·알림·예산)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/platform test
#
# 이 모듈은 권한(IAM)을 정한다. 그래서 검사의 중심은 두 가지다.
#   1. 서비스는 필요한 것만 읽는다 — 자기 ECR 저장소, 자기 로그 그룹, 자기가 reader인 비밀. 그 밖의 것은 하나도 새지 않는다.
#   2. 허용된 GitHub 환경(저장소 + environment 한 쌍)만 CI 역할을 빌린다. 와일드카드로 넓어지지 않는다.
#
# 읽는 법
# - 가짜 provider는 계산 속성(ARN·id 등)을 apply 단계에서만 채운다. 그래서 모든 run은 기본값 command = apply로 두고,
#   변수 검증 run만 command = plan으로 둔다. plan에서 미정 값에 기대면 "Unknown condition value" 오류로 끝난다.
# - aws_iam_policy_document의 json은 가짜 provider 아래에서 무작위 문자열이 돼 정책 내용을 읽을 수 없다.
#   그래서 구현은 모든 정책을 jsonencode()로 쓰고, 테스트는 jsondecode()로 읽는다.
#   정책 문은 순서가 아니라 내용(Action 조합)으로 고르고, Statement·Action·Resource는 값 하나든 목록이든 flatten으로 목록에 맞춘다.
# - 테스트 파일에는 locals를 쓸 수 없다. 여러 번 쓰는 값은 "for x in [값]"처럼 원소가 하나인 for로 묶어 이름을 붙인다.
# - 가짜 provider가 만든 ARN은 서로 다른 무작위 8글자다. 그래서 "A 정책에 B 비밀 ARN이 없다" 같은 집합 비교가 값을 가려낸다.
#   첫 run이 이 전제(값이 겹치지 않음)를 먼저 확인한다.
# - run이 assert 실패가 아니라 오류로 끝나면 뒤 run이 전부 skip된다. 없을 수 있는 값은 try()로 감싸 실패로 끝나게 한다.

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {
  # 가짜 provider가 채우는 무작위 8글자는 ARN 형식이 아니다. aws_sns_topic_subscription의 topic_arn은 apply 때 ARN 형식을
  # 검사하므로("is an invalid ARN: arn: invalid prefix") 구독 대상인 주제의 ARN만 형식을 갖춘 값으로 고정한다.
  mock_resource "aws_sns_topic" {
    defaults = {
      arn = "arn:aws:sns:ap-northeast-2:123456789012:whycode-staging-alerts"
    }
  }
}

# 가짜 provider는 계정 번호도 무작위 8글자로 만들므로, 실행 역할 신뢰 정책의 계정 조건을 비교할 수 있게 12자리 번호로 고정한다.
override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

# 필수 변수. run마다 variables {}로 덮어쓸 수 있다.
variables {
  environment              = "staging"
  github_repository        = "se-zero/history-tracker"
  github_environment       = "staging"
  github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  alert_email              = "ops@example.com"
  monthly_budget_usd       = 50
}

# ─────────────────────────────────────────────────────────────────────────────
# 0. 계약 밖 자원
# ─────────────────────────────────────────────────────────────────────────────

# 계약에 없는 자원이 덧붙는 것을 막는다 — 아래 run들은 계약에 적힌 주소의 자원만 볼 수 있어서, 다른 이름의 자원으로 권한이나 비밀 값을 몰래 넣어도 통과한다.
# 막으려는 것: 비밀 값을 코드에 넣는 aws_secretsmanager_secret_version(값이 코드와 상태 파일에 평문으로 남는다), 실행 역할·ci_deploy 역할에 붙는 별도
# aws_iam_role_policy·aws_iam_role_policy_attachment(관리자급 권한이 이 파일의 권한 검사를 비켜 간다). 모듈 소스에 선언된 자원 주소를 계약의 12개와 직접 대조한다.
# 소스를 정규식으로 읽으므로 자원은 파일에 resource "타입" "이름" 꼴로 쓴다. 자원을 일부러 늘릴 때는 이 목록과 해당 검사를 함께 고친다.
# 다른 모듈(T2 진입점·T3 데이터·T4 앱)이 이 모듈의 역할에 붙이는 정책은 그 모듈의 자원이라 여기서 보지 않는다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_ecr_repository.service",
      "aws_ecr_lifecycle_policy.service",
      "aws_cloudwatch_log_group.service",
      "aws_secretsmanager_secret.app",
      "aws_iam_role.execution",
      "aws_iam_role_policy.execution",
      "aws_iam_role.ci_push",
      "aws_iam_role.ci_deploy",
      "aws_iam_role_policy.ci_push",
      "aws_sns_topic.alerts",
      "aws_sns_topic_subscription.alerts_email",
      "aws_budgets_budget.monthly",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 12개와 정확히 같아야 한다(덧붙은 자원도, 빠진 자원도 안 된다). 비밀 값(aws_secretsmanager_secret_version)과 별도 정책·정책 연결은 특히 안 된다. 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
  }

  assert {
    condition     = length(flatten([for f in fileset(path.module, "*.tf") : regexall("(?m)^\\s*module\\s+\"", file("${path.module}/${f}"))])) == 0
    error_message = "이 모듈은 다른 모듈을 부르면 안 된다(module 블록 금지). 부른 모듈이 만드는 자원은 아래 run들이 볼 수 없다."
  }

  assert {
    condition     = length(fileset(path.module, "*.tf.json")) == 0
    error_message = "자원은 *.tf에만 선언한다. 지금 *.tf.json 파일: ${jsonencode(fileset(path.module, "*.tf.json"))}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 0. 전제
# ─────────────────────────────────────────────────────────────────────────────

# 가짜 provider가 서로 다른 리소스에 같은 값을 주면 아래의 ARN 집합 비교(다른 서비스의 비밀·저장소가 새지 않는지)가 아무것도 가리지 못한다. 그 전제를 먼저 확인한다.
run "mock_values_are_distinct_across_resources" {
  assert {
    condition = one([
      for ids in [concat(
        [for r in aws_secretsmanager_secret.app : r.arn],
        [for r in aws_ecr_repository.service : r.arn],
        [for r in aws_ecr_repository.service : r.repository_url],
        [for g in aws_cloudwatch_log_group.service : g.arn],
        [for r in aws_iam_role.execution : r.arn],
        [for r in aws_iam_role.execution : r.id],
        [aws_iam_role.ci_push.arn, aws_iam_role.ci_push.id, aws_iam_role.ci_deploy.arn, aws_iam_role.ci_deploy.id],
      )] : length(toset(ids)) == length(ids)
    ])
    error_message = "가짜 provider가 서로 다른 리소스에 같은 값을 줬다. 이 파일의 집합 비교(다른 서비스의 비밀·저장소가 새지 않는지)는 값이 서로 달라야 의미가 있다. 비밀 ARN: ${jsonencode({ for k, r in aws_secretsmanager_secret.app : k => r.arn })}, 저장소 ARN: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.arn })}, 저장소 URL: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.repository_url })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. ECR
# ─────────────────────────────────────────────────────────────────────────────

# 서비스마다 저장소가 하나씩만 있어야 한다. 하나가 빠지면 그 서비스의 이미지를 올릴 곳이 없고, 하나가 더 있으면 쓰지 않는 저장소가 남는다.
run "ecr_has_exactly_one_repository_per_service" {
  assert {
    condition     = toset(keys(aws_ecr_repository.service)) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "ECR 저장소는 backend·pipeline-worker·ai-engine 셋뿐이어야 한다. 지금 키: ${jsonencode(keys(aws_ecr_repository.service))}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_ecr_repository.service[svc].name, null) == "whycode-staging/${svc}"
    ])
    error_message = "ECR 저장소 이름은 <project>-<environment>/<서비스>(예: whycode-staging/backend)여야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.name })}"
  }
}

# 태그가 가변(MUTABLE)으로 바뀌는 것을 막는다 — 가변이면 같은 태그로 다른 이미지를 덮어써서, 배포한 이미지가 무엇인지 태그만으로 믿을 수 없다.
run "ecr_image_tags_are_immutable" {
  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_ecr_repository.service[svc].image_tag_mutability, null) == "IMMUTABLE"
    ])
    error_message = "ECR image_tag_mutability는 모든 저장소에서 IMMUTABLE이어야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.image_tag_mutability })}"
  }

  assert {
    # 예외 필터(image_tag_mutability_exclusion_filter)가 붙으면 그 패턴에 맞는 태그는 덮어쓸 수 있게 되어 불변이 구멍 난다.
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      length(try(aws_ecr_repository.service[svc].image_tag_mutability_exclusion_filter, ["missing"])) == 0
    ])
    error_message = "ECR에 태그 가변 예외 필터(image_tag_mutability_exclusion_filter)가 있다. 모든 태그가 불변이어야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.image_tag_mutability_exclusion_filter })}"
  }
}

# 푸시 시 취약점 스캔이 꺼지는 것을 막는다 — 꺼지면 알려진 취약점이 든 이미지가 조용히 배포된다.
run "ecr_scans_images_on_push" {
  assert {
    # image_scanning_configuration은 list 블록이라 one()으로 꺼낸다. 블록이 없으면 null이 되어 실패로 끝난다.
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(one(aws_ecr_repository.service[svc].image_scanning_configuration).scan_on_push, null) == true
    ])
    error_message = "ECR image_scanning_configuration.scan_on_push는 모든 저장소에서 true여야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.image_scanning_configuration })}"
  }
}

# force_delete가 빠지는 것을 막는다 — 빠지면 이미지가 든 저장소는 terraform destroy가 실패해 환경을 통째로 지울 수 없다.
run "ecr_repositories_can_be_force_deleted" {
  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_ecr_repository.service[svc].force_delete, null) == true
    ])
    error_message = "ECR force_delete는 모든 저장소에서 true여야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.force_delete })}"
  }
}

# 암호화가 KMS로 바뀌는 것을 막는다 — KMS면 이미지를 당기는 실행 역할에 kms 권한이 더 필요해져, 계약에 없는 권한이 늘어난다.
run "ecr_encrypts_with_aes256" {
  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(one(aws_ecr_repository.service[svc].encryption_configuration).encryption_type, null) == "AES256"
    ])
    error_message = "ECR encryption_configuration.encryption_type은 모든 저장소에서 AES256이어야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.encryption_configuration })}"
  }
}

# 수명 주기 정책이 저장소마다 없거나 다른 저장소에 붙는 것을 막는다 — 없으면 이미지가 끝없이 쌓여 저장 요금이 계속 늘어난다.
run "ecr_lifecycle_policy_is_attached_to_each_repository" {
  assert {
    condition     = toset(keys(aws_ecr_lifecycle_policy.service)) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "ECR 수명 주기 정책은 backend·pipeline-worker·ai-engine 저장소마다 하나씩 있어야 한다. 지금 키: ${jsonencode(keys(aws_ecr_lifecycle_policy.service))}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_ecr_lifecycle_policy.service[svc].repository, null) == try(aws_ecr_repository.service[svc].name, "missing-repo:${svc}")
    ])
    error_message = "수명 주기 정책의 repository는 같은 서비스 저장소의 이름이어야 한다. 지금 repository: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => p.repository })}, 저장소 이름: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.name })}"
  }
}

# 수명 주기 규칙이 어긋나는 것을 막는다 — 개수 기준이 변수와 다르거나, 태그 있는 이미지만 지우거나, expire가 아니면 이미지가 쌓이거나 필요한 이미지가 지워진다.
run "ecr_lifecycle_policy_expires_images_beyond_the_limit" {
  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.rulePriority, null) == 1])
      ]
    ]))
    error_message = "수명 주기 정책 규칙은 rulePriority 1 하나여야 한다. 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.selection.tagStatus, null) == "any"])
      ]
    ]))
    error_message = "수명 주기 규칙 selection.tagStatus는 any여야 한다(태그 유무와 상관없이 센다). 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.selection.countType, null) == "imageCountMoreThan"])
      ]
    ]))
    error_message = "수명 주기 규칙 selection.countType은 imageCountMoreThan이어야 한다. 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    # 변수 기본값(20)을 그대로 적는다. JSON 숫자여야 하므로 문자열 "20"은 실패한다.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.selection.countNumber, null) == 20])
      ]
    ]))
    error_message = "수명 주기 규칙 selection.countNumber는 ecr_max_image_count 기본값 20(JSON 숫자)이어야 한다. 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.action.type, null) == "expire"])
      ]
    ]))
    error_message = "수명 주기 규칙 action.type은 expire여야 한다. 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 2. 로그 그룹
# ─────────────────────────────────────────────────────────────────────────────

# 서비스마다 로그 그룹이 하나씩이고 이름이 /<project>/<environment>/<서비스>인지 본다. 이름이 어긋나면 실행 역할 정책이 가리키는 로그 그룹과 앱이 쓰는 로그 그룹이 달라진다.
run "log_groups_are_named_per_service_and_environment" {
  assert {
    condition     = toset(keys(aws_cloudwatch_log_group.service)) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "로그 그룹은 backend·pipeline-worker·ai-engine 셋뿐이어야 한다. 지금 키: ${jsonencode(keys(aws_cloudwatch_log_group.service))}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].name, null) == "/whycode/staging/${svc}"
    ])
    error_message = "로그 그룹 이름은 /<project>/<environment>/<서비스>(예: /whycode/staging/backend)여야 한다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.name })}"
  }
}

# 보존 기간이 비어(무기한) 있거나 변수와 다른 것을 막는다 — 무기한 보존은 로그 저장 요금이 끝없이 늘어난다.
run "log_group_retention_defaults_to_14_days" {
  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].retention_in_days, null) == 14
    ])
    error_message = "로그 그룹 retention_in_days는 기본 14여야 한다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.retention_in_days })}"
  }
}

# 환경을 지워도 로그 그룹이 남거나 기본 키 밖의 설정이 끼어드는 것을 막는다 — skip_destroy·deletion_protection_enabled가 켜지면 terraform destroy 뒤에도 로그 그룹이 남아 같은 이름으로 다시 만들 때 "이미 있다" 오류가 나고,
# kms_key_id는 CloudWatch Logs가 키를 쓸 권한(키 정책)이 따로 있어야 해 계약에 없는 설정이 늘어난다.
run "log_groups_are_destroyed_with_the_environment_and_use_the_default_key" {
  assert {
    # 없으면(true로 대체) 실패로 끝난다.
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].skip_destroy, true) != true
    ])
    error_message = "로그 그룹에 skip_destroy = true를 두면 안 된다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.skip_destroy })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].deletion_protection_enabled, true) != true
    ])
    error_message = "로그 그룹에 deletion_protection_enabled = true를 두면 안 된다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.deletion_protection_enabled })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].kms_key_id, "set") == null
    ])
    error_message = "로그 그룹에 kms_key_id를 설정하면 안 된다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.kms_key_id })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 3. 비밀 껍데기 (값은 사람이 CLI로 넣는다 — aws_secretsmanager_secret_version은 만들지 않는다)
# ─────────────────────────────────────────────────────────────────────────────

# 비밀이 빠지거나 늘어나는 것, 이름 규칙이 어긋나는 것을 막는다 — 앱이 읽을 비밀 이름은 <project>/<environment>/<키>로 고정이다.
run "secrets_are_exactly_the_six_shells_named_per_environment" {
  assert {
    condition = toset(keys(aws_secretsmanager_secret.app)) == toset([
      "backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal",
    ])
    error_message = "비밀 키 집합은 backend·pipeline-worker·ai-engine·shared-credentials·shared-alert·shared-internal 여섯 개여야 한다. 지금 키: ${jsonencode(keys(aws_secretsmanager_secret.app))}"
  }

  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(aws_secretsmanager_secret.app[k].name, null) == "whycode/staging/${k}"
    ])
    error_message = "비밀 이름은 <project>/<environment>/<키>(예: whycode/staging/shared-internal)여야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.name })}"
  }
}

# 삭제 유예 기간이 변수와 다른 것을 막는다 — 기본값 0은 지우면 바로 사라진다는 뜻이라, 7 같은 값이면 destroy 뒤 같은 이름으로 다시 만들 때 "삭제 예약" 오류가 난다.
run "secret_recovery_window_defaults_to_zero" {
  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(aws_secretsmanager_secret.app[k].recovery_window_in_days, null) == 0
    ])
    error_message = "비밀 recovery_window_in_days는 기본 0이어야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.recovery_window_in_days })}"
  }
}

# 비밀에 고객 관리형 KMS 키가 붙거나 다른 리전으로 복제되는 것을 막는다 — KMS 키를 쓰면 실행 역할에 kms:Decrypt가 필요한데 정책에는 없어서 컨테이너가 비밀을 읽지 못해 기동에 실패하고,
# 복제는 계약에 없는 사본(과 요금·노출)을 만든다. 기본 키(kms_key_id 비움)만 쓰고 복제하지 않는다.
run "secrets_use_the_default_key_and_have_no_replicas" {
  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(aws_secretsmanager_secret.app[k].kms_key_id, "set") == null
    ])
    error_message = "비밀에 kms_key_id를 설정하면 안 된다(실행 역할 정책에 kms:Decrypt가 없다). 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.kms_key_id })}"
  }

  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      length(try(aws_secretsmanager_secret.app[k].replica, ["missing"])) == 0
    ])
    error_message = "비밀에 replica(다른 리전 복제)가 있다. 계약에 없다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.replica })}"
  }
}

# 설명이 비었거나 한글·줄바꿈이 섞이는 것을 막는다 — 이 설명 규칙은 가짜 provider가 걸러 주지 않아, 실제 apply에서야 거부될 수 있다.
run "secret_descriptions_are_ascii_and_not_empty" {
  assert {
    # 허용 문자: 영문·숫자·공백과 . _ : / ( ) # , @ [ ] + = ; { } ! $ * & -  (줄바꿈·한글은 정규식에 걸려 실패한다)
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", try(aws_secretsmanager_secret.app[k].description, "")))
    ])
    error_message = "비밀 description은 영어 ASCII 한 줄이고 비어 있지 않아야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.description })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 4. ECS 실행 역할 — 필요한 것만 읽는다
# ─────────────────────────────────────────────────────────────────────────────

# 서비스마다 실행 역할이 하나씩이고 이름이 <name>-<서비스>-exec인지 본다. 한 역할을 여러 서비스가 나눠 쓰면 서비스별로 읽을 수 있는 비밀을 나눌 수 없다.
run "execution_roles_are_named_one_per_service" {
  assert {
    condition     = toset(keys(aws_iam_role.execution)) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "실행 역할은 backend·pipeline-worker·ai-engine 서비스마다 하나씩 있어야 한다. 지금 키: ${jsonencode(keys(aws_iam_role.execution))}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_iam_role.execution[svc].name, null) == "whycode-staging-${svc}-exec"
    ])
    error_message = "실행 역할 이름은 <project>-<environment>-<서비스>-exec(예: whycode-staging-backend-exec)여야 한다. 지금 값: ${jsonencode({ for k, r in aws_iam_role.execution : k => r.name })}"
  }
}

# 신뢰 정책이 ECS 작업 말고 다른 주체에게 열리거나, 다른 계정에서 이 역할을 빌릴 수 있게 되는 것을 막는다(confused deputy 방어: aws:SourceAccount가 이 계정이어야 한다).
run "execution_role_trusts_only_ecs_tasks_of_this_account" {
  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role.execution[svc].assume_role_policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              try(s.Effect, null) == "Allow"
              && toset(try(keys(s.Principal), [])) == toset(["Service"])
              && toset(flatten([try(s.Principal.Service, [])])) == toset(["ecs-tasks.amazonaws.com"])
              && length(flatten([try(s.Principal.Service, [])])) == 1
              && toset(flatten([try(s.Action, [])])) == toset(["sts:AssumeRole"])
            )
          ])
        )
      ]
    ]))
    error_message = "실행 역할 신뢰 정책은 단일 Allow 문이고, 주체(Principal)는 Service = ecs-tasks.amazonaws.com 하나뿐, Action은 sts:AssumeRole이어야 한다. 지금 정책: ${jsonencode({ for k, r in aws_iam_role.execution : k => try(jsondecode(r.assume_role_policy), r.assume_role_policy) })}"
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role.execution[svc].assume_role_policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Condition.StringEquals["aws:SourceAccount"], [])])) == toset(["123456789012"])
              && length(flatten([try(s.Condition.StringEquals["aws:SourceAccount"], [])])) == 1
            )
          ])
        )
      ]
    ]))
    error_message = "실행 역할 신뢰 정책에는 Condition.StringEquals[\"aws:SourceAccount\"] = 이 계정 번호(123456789012) 조건이 있어야 한다. 지금 정책: ${jsonencode({ for k, r in aws_iam_role.execution : k => try(jsondecode(r.assume_role_policy), r.assume_role_policy) })}"
  }

  assert {
    # 신뢰 정책에 "이것만 빼고 전부"(NotPrincipal·NotAction)나 엉뚱한 키(Resource)가 끼어드는 것을 막는다.
    # 조건(Condition)에 aws:SourceArn 같은 값으로 범위를 더 좁히는 것은 허용하므로, 값 속 와일드카드는 따로 막지 않는다(주체는 위에서 정확히 비교한다).
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for s in flatten([try(jsondecode(aws_iam_role.execution[svc].assume_role_policy).Statement, [])]) :
        length(setintersection(toset(try(keys(s), ["unreadable"])), toset(["NotPrincipal", "NotAction", "NotResource", "Resource"]))) == 0
      ]
    ]))
    error_message = "실행 역할 신뢰 정책 문에 NotPrincipal·NotAction·NotResource·Resource가 있다. 지금 정책: ${jsonencode({ for k, r in aws_iam_role.execution : k => try(jsondecode(r.assume_role_policy), r.assume_role_policy) })}"
  }
}

# 실행 정책이 다른 서비스의 역할에 붙거나, 이름이 달라지거나, 문이 늘어나는 것을 막는다 — 문은 ECR 토큰·ECR 읽기·로그·비밀 읽기 네 개뿐이다.
run "execution_policy_is_attached_to_own_role_with_four_allow_statements" {
  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_iam_role_policy.execution[svc].role, null) == try(aws_iam_role.execution[svc].id, "missing-role:${svc}")
    ])
    error_message = "실행 정책은 같은 서비스의 실행 역할(role = aws_iam_role.execution[<서비스>].id)에 붙어야 한다. 지금 role: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => p.role })}, 역할 id: ${jsonencode({ for k, r in aws_iam_role.execution : k => r.id })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_iam_role_policy.execution[svc].name, null) == "base"
    ])
    error_message = "실행 정책 이름은 base여야 한다. 지금 값: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => p.name })}"
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] :
        length(stmts) == 4 && alltrue([for s in stmts : try(s.Effect, null) == "Allow"])
      ]
    ]))
    error_message = "실행 정책은 Allow 문 네 개(ECR 토큰, ECR 읽기, 로그, 비밀 읽기)여야 한다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    # NotAction·NotResource는 "이것만 빼고 전부"라 필요한 것만 읽는다는 원칙을 뒤집는다.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for s in flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])]) :
        length(setintersection(toset(try(keys(s), ["unreadable"])), toset(["NotAction", "NotResource", "Principal"]))) == 0
      ]
    ]))
    error_message = "실행 정책 문에 NotAction·NotResource·Principal이 있다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }
}

# 서비스가 읽는 비밀이 reader 정의와 어긋나는 것을 막는다 — 읽을 비밀이 모자라면 컨테이너가 기동하지 못하고, 많으면 다른 서비스의 비밀이 샌다.
# backend는 {backend, shared-credentials, shared-internal}, pipeline-worker는 {pipeline-worker, shared-credentials, shared-alert, shared-internal}, ai-engine은 {ai-engine, shared-alert, shared-internal}만이다.
run "execution_policy_reads_exactly_the_secrets_the_service_is_reader_of" {
  assert {
    # GetSecretValue를 가진 문은 정확히 하나, 그 문의 Action은 그것 하나뿐, Resource는 reader인 비밀 ARN 집합과 같다.
    condition = alltrue(flatten([
      for svc, secret_keys in {
        "backend"         = ["backend", "shared-credentials", "shared-internal"]
        "pipeline-worker" = ["pipeline-worker", "shared-credentials", "shared-alert", "shared-internal"]
        "ai-engine"       = ["ai-engine", "shared-alert", "shared-internal"]
        } : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] : (
          length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")]) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Action, [])])) == toset(["secretsmanager:GetSecretValue"])
              && try(s.Effect, null) == "Allow"
              && toset(flatten([try(s.Resource, [])])) == toset([for k in secret_keys : try(aws_secretsmanager_secret.app[k].arn, "missing-secret:${k}")])
            )
            if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")
          ])
        )
      ]
    ]))
    error_message = "서비스가 읽는 비밀 ARN 집합이 reader 정의와 다르다(GetSecretValue 문은 서비스마다 하나, Action은 GetSecretValue뿐). 서비스별 지금 Resource: ${jsonencode({ for svc, p in aws_iam_role_policy.execution : svc => flatten([for s in flatten([try(jsondecode(p.policy).Statement, [])]) : flatten([try(s.Resource, [])]) if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")]) })}, 비밀 ARN → 비밀 키: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : s.arn => k })}"
  }

  assert {
    # 읽기 문의 Resource에 와일드카드가 섞이는 것을 따로 막는다(집합 비교에서도 걸리지만, 이유를 바로 알 수 있게 한다).
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for s in flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])]) :
        alltrue([for r in flatten([try(s.Resource, [])]) : !strcontains(r, "*")])
        if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")
      ]
    ]))
    error_message = "비밀 읽기 문의 Resource에 와일드카드(*)가 있다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }
}

# 다른 서비스 전용 비밀이 새는 것을 음성으로 따로 막는다 — 예: backend 정책에 pipeline-worker·ai-engine 전용 비밀이나 shared-alert가 있으면 안 된다.
run "execution_policy_does_not_leak_other_services_secrets" {
  assert {
    condition = alltrue(flatten([
      for svc, forbidden_keys in {
        "backend"         = ["pipeline-worker", "ai-engine", "shared-alert"]
        "pipeline-worker" = ["backend", "ai-engine"]
        "ai-engine"       = ["backend", "pipeline-worker", "shared-credentials"]
        } : [
        for k in forbidden_keys : (
          !contains(
            flatten([for s in flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])]) : flatten([try(s.Resource, [])])]),
            try(aws_secretsmanager_secret.app[k].arn, "missing-secret:${k}"),
          )
          && !strcontains(try(aws_iam_role_policy.execution[svc].policy, ""), try(aws_secretsmanager_secret.app[k].arn, "missing-secret:${k}"))
        )
      ]
    ]))
    error_message = "서비스 실행 정책에 읽으면 안 되는 비밀 ARN이 들어 있다. backend는 pipeline-worker·ai-engine·shared-alert, pipeline-worker는 backend·ai-engine, ai-engine은 backend·pipeline-worker·shared-credentials를 읽으면 안 된다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}, 비밀 ARN → 비밀 키: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : s.arn => k })}"
  }
}

# ECR 읽기가 모든 저장소로, 로그가 모든 로그 그룹으로 넓어지는 것을 막는다 — 서비스는 자기 이미지만 당기고 자기 로그 그룹에만 쓴다.
run "execution_policy_scopes_ecr_pull_and_logs_to_own_resources" {
  assert {
    # ECR 읽기 문: BatchGetImage를 가진 문은 하나, Action은 읽기 셋뿐, Resource는 그 서비스 저장소 ARN 하나.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] : (
          length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "ecr:BatchGetImage")]) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Action, [])])) == toset(["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"])
              && try(s.Effect, null) == "Allow"
              && length(flatten([try(s.Resource, [])])) == 1
              && toset(flatten([try(s.Resource, [])])) == toset([try(aws_ecr_repository.service[svc].arn, "missing-repo:${svc}")])
            )
            if contains(flatten([try(s.Action, [])]), "ecr:BatchGetImage")
          ])
        )
      ]
    ]))
    error_message = "ECR 읽기 문은 BatchCheckLayerAvailability·GetDownloadUrlForLayer·BatchGetImage만, 그 서비스 저장소 ARN 하나에만 허용해야 한다. 서비스별 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}, 저장소 ARN: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.arn })}"
  }

  assert {
    # 로그 문: PutLogEvents를 가진 문은 하나, Action은 CreateLogStream·PutLogEvents뿐, Resource는 그 서비스 로그 그룹 ARN 뒤에 :*를 붙인 것 하나.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] : (
          length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "logs:PutLogEvents")]) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Action, [])])) == toset(["logs:CreateLogStream", "logs:PutLogEvents"])
              && try(s.Effect, null) == "Allow"
              && length(flatten([try(s.Resource, [])])) == 1
              && toset(flatten([try(s.Resource, [])])) == toset(["${try(aws_cloudwatch_log_group.service[svc].arn, "missing-log-group:${svc}")}:*"])
            )
            if contains(flatten([try(s.Action, [])]), "logs:PutLogEvents")
          ])
        )
      ]
    ]))
    error_message = "로그 문은 CreateLogStream·PutLogEvents만, 그 서비스 로그 그룹 ARN 뒤에 :*를 붙인 하나에만 허용해야 한다. 서비스별 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}, 로그 그룹 ARN: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.arn })}"
  }

  assert {
    # 다른 서비스의 저장소·로그 그룹 ARN이 정책 어디에도 없다.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for other in [for o in ["backend", "pipeline-worker", "ai-engine"] : o if o != svc] : (
          !strcontains(try(aws_iam_role_policy.execution[svc].policy, ""), try(aws_ecr_repository.service[other].arn, "missing-repo:${other}"))
          && !strcontains(try(aws_iam_role_policy.execution[svc].policy, ""), try(aws_cloudwatch_log_group.service[other].arn, "missing-log-group:${other}"))
        )
      ]
    ]))
    error_message = "실행 정책에 다른 서비스의 저장소·로그 그룹 ARN이 들어 있다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}, 저장소 ARN: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.arn })}, 로그 그룹 ARN: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.arn })}"
  }
}

# Resource "*"가 늘어나는 것을 막는다 — "*"를 허용하는 문은 ecr:GetAuthorizationToken 하나뿐이다(이 작업은 특정 자원을 지정할 수 없다).
run "execution_policy_allows_star_resource_only_for_ecr_auth_token" {
  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] : (
          length([for s in stmts : s if contains(flatten([try(s.Resource, [])]), "*")]) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Action, [])])) == toset(["ecr:GetAuthorizationToken"])
              && try(s.Effect, null) == "Allow"
              && length(flatten([try(s.Resource, [])])) == 1
            )
            if contains(flatten([try(s.Resource, [])]), "*")
          ])
        )
      ]
    ]))
    error_message = "Resource가 \"*\"인 문은 ecr:GetAuthorizationToken 하나뿐이어야 한다(그 문의 Resource도 \"*\" 하나). 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    # GetAuthorizationToken 문이 사라지는 것도 막는다 — 없으면 이미지를 당기지 못한다.
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] :
        length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "ecr:GetAuthorizationToken")]) == 1
      ]
    ]))
    error_message = "ecr:GetAuthorizationToken을 허용하는 문이 서비스마다 하나 있어야 한다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }
}

# secretsmanager:*·iam:*·ecr:* 같은 와일드카드 Action을 막는다 — 와일드카드는 필요한 것만 읽는다는 원칙을 한 줄로 무너뜨린다.
run "execution_policy_has_no_wildcard_actions" {
  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for s in flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])]) : (
          length(flatten([try(s.Action, [])])) > 0
          && alltrue([for a in flatten([try(s.Action, [])]) : !strcontains(a, "*")])
        )
      ]
    ]))
    error_message = "실행 정책의 Action에 와일드카드(*)가 있거나 Action이 비어 있다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => try(jsondecode(p.policy), p.policy) })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 5. CI 역할 — 허용된 GitHub 환경만 빌린다
# ─────────────────────────────────────────────────────────────────────────────

# CI 역할 이름이 어긋나는 것을 막는다 — 이름은 GitHub 워크플로의 역할 ARN과 이 모듈의 출력이 가리키는 대상이다.
run "ci_roles_are_named_per_environment" {
  assert {
    condition     = aws_iam_role.ci_push.name == "whycode-staging-ci-push"
    error_message = "ci_push 역할 이름은 <project>-<environment>-ci-push(whycode-staging-ci-push)여야 한다. 지금 값: ${jsonencode(aws_iam_role.ci_push.name)}"
  }

  assert {
    condition     = aws_iam_role.ci_deploy.name == "whycode-staging-ci-deploy"
    error_message = "ci_deploy 역할 이름은 <project>-<environment>-ci-deploy(whycode-staging-ci-deploy)여야 한다. 지금 값: ${jsonencode(aws_iam_role.ci_deploy.name)}"
  }
}

# 신뢰하는 주체가 GitHub OIDC provider가 아니거나 Action이 sts:AssumeRoleWithWebIdentity가 아닌 것을 막는다 — Action이 sts:AssumeRole이면 GitHub 토큰으로는 역할을 빌릴 수 없다.
run "ci_trust_names_github_provider_and_web_identity_action" {
  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              try(s.Effect, null) == "Allow"
              && toset(try(keys(s.Principal), [])) == toset(["Federated"])
              && toset(flatten([try(s.Principal.Federated, [])])) == toset([var.github_oidc_provider_arn])
              && length(flatten([try(s.Principal.Federated, [])])) == 1
              && toset(flatten([try(s.Action, [])])) == toset(["sts:AssumeRoleWithWebIdentity"])
              && length(flatten([try(s.Action, [])])) == 1
            )
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책은 단일 Allow 문이고, Principal은 Federated = github_oidc_provider_arn 하나뿐, Action은 sts:AssumeRoleWithWebIdentity 하나여야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }
}

# 조건이 느슨해지는 것을 막는다 — Condition은 StringEquals 하나뿐이고 키는 aud·sub 둘뿐이며 값은 정확한 문자열이다(저장소와 environment가 한 쌍으로 고정).
run "ci_trust_condition_is_one_string_equals_with_aud_and_sub" {
  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([for s in stmts : toset(try(keys(s.Condition), [])) == toset(["StringEquals"])])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 Condition 연산자는 StringEquals 하나뿐이어야 한다(StringLike 등 금지). 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }

  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts :
            toset(try(keys(s.Condition.StringEquals), [])) == toset(["token.actions.githubusercontent.com:aud", "token.actions.githubusercontent.com:sub"])
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 StringEquals 키는 token.actions.githubusercontent.com:aud와 :sub 정확히 둘이어야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }

  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:aud"], [])])) == toset(["sts.amazonaws.com"])
              && length(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:aud"], [])])) == 1
            )
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 aud 조건은 sts.amazonaws.com 하나여야 한다(없으면 다른 용도로 발급된 토큰도 받는다). 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }

  assert {
    # sub는 repo:<저장소>:environment:<GitHub environment> 한 문자열이다. 저장소만 있거나 와일드카드(repo:owner/repo:*)면 어느 브랜치·환경에서든 역할을 빌린다.
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:sub"], [])])) == toset(["repo:se-zero/history-tracker:environment:staging"])
              && length(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:sub"], [])])) == 1
            )
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 sub 조건은 \"repo:se-zero/history-tracker:environment:staging\" 정확히 하나여야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }
}

# 와일드카드·StringLike가 신뢰 정책 어디에도 없게 막는다 — 한 글자 *가 들어가면 허용한 GitHub 환경 밖의 워크플로도 역할을 빌린다.
run "ci_trust_has_no_wildcards" {
  assert {
    condition     = !strcontains(aws_iam_role.ci_push.assume_role_policy, "*") && !strcontains(aws_iam_role.ci_push.assume_role_policy, "StringLike")
    error_message = "ci_push 신뢰 정책에 와일드카드(*)나 StringLike가 있다. 지금 정책: ${aws_iam_role.ci_push.assume_role_policy}"
  }

  assert {
    condition     = !strcontains(aws_iam_role.ci_deploy.assume_role_policy, "*") && !strcontains(aws_iam_role.ci_deploy.assume_role_policy, "StringLike")
    error_message = "ci_deploy 신뢰 정책에 와일드카드(*)나 StringLike가 있다. 지금 정책: ${aws_iam_role.ci_deploy.assume_role_policy}"
  }
}

# 역할에 인라인 블록이나 관리형 정책이 몰래 붙는 것을 막는다 — 권한은 aws_iam_role_policy 한 곳에서만 정한다. role의 managed_policy_arns에 AdministratorAccess 같은 관리형 정책을 걸면 여기의 최소 권한 검사를 우회한다.
# (ci_deploy 역할은 이 묶음에서 권한이 없어야 하므로 특히 비어 있어야 한다.)
run "roles_carry_no_inline_blocks_or_managed_policies" {
  assert {
    condition = alltrue(concat(
      [for r in aws_iam_role.execution : length(r.inline_policy) == 0],
      [length(aws_iam_role.ci_push.inline_policy) == 0, length(aws_iam_role.ci_deploy.inline_policy) == 0],
    ))
    error_message = "역할에 inline_policy 블록이 있다. 권한은 aws_iam_role_policy로만 준다. 지금 값: 실행 역할 ${jsonencode({ for k, r in aws_iam_role.execution : k => r.inline_policy })}, ci_push ${jsonencode(aws_iam_role.ci_push.inline_policy)}, ci_deploy ${jsonencode(aws_iam_role.ci_deploy.inline_policy)}"
  }

  assert {
    condition = alltrue(concat(
      [for r in aws_iam_role.execution : length(coalesce(r.managed_policy_arns, [])) == 0],
      [length(coalesce(aws_iam_role.ci_push.managed_policy_arns, [])) == 0, length(coalesce(aws_iam_role.ci_deploy.managed_policy_arns, [])) == 0],
    ))
    error_message = "역할에 managed_policy_arns가 있다. 관리형 정책을 붙이지 않는다. 지금 값: 실행 역할 ${jsonencode({ for k, r in aws_iam_role.execution : k => r.managed_policy_arns })}, ci_push ${jsonencode(aws_iam_role.ci_push.managed_policy_arns)}, ci_deploy ${jsonencode(aws_iam_role.ci_deploy.managed_policy_arns)}"
  }
}

# push 권한이 다른 역할에 붙거나 이름이 바뀌는 것을 막는다 — 이미지를 올리는 권한은 ci_push 역할에만 있고 ci_deploy에는 이 묶음에서 권한이 없다.
run "ci_push_policy_is_attached_to_push_role_only" {
  assert {
    condition     = aws_iam_role_policy.ci_push.role == aws_iam_role.ci_push.id
    error_message = "ci_push 정책은 ci_push 역할(role = aws_iam_role.ci_push.id)에 붙어야 한다. 지금 role: ${jsonencode(aws_iam_role_policy.ci_push.role)}, ci_push id: ${aws_iam_role.ci_push.id}, ci_deploy id: ${aws_iam_role.ci_deploy.id}"
  }

  assert {
    condition     = aws_iam_role_policy.ci_push.role != aws_iam_role.ci_deploy.id
    error_message = "ci_push 정책이 ci_deploy 역할에 붙어 있다. 배포 역할은 이 묶음에서 권한이 없어야 한다. 지금 role: ${jsonencode(aws_iam_role_policy.ci_push.role)}"
  }

  assert {
    condition     = aws_iam_role_policy.ci_push.name == "ecr-push"
    error_message = "ci_push 정책 이름은 ecr-push여야 한다. 지금 값: ${jsonencode(aws_iam_role_policy.ci_push.name)}"
  }
}

# push 권한이 어긋나는 것을 막는다 — 문은 토큰 발급과 이미지 올리기 둘뿐이고, 올리기는 정확히 다섯 Action을 세 저장소에만 허용한다(하나가 빠지면 푸시가 실패하고, 저장소가 빠지면 그 서비스를 못 올린다).
run "ci_push_policy_allows_only_token_and_layer_push_on_the_three_repositories" {
  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] :
      length(stmts) == 2 && alltrue([for s in stmts : try(s.Effect, null) == "Allow"])
    ])
    error_message = "ci_push 정책은 Allow 문 두 개(토큰 발급, 이미지 올리기)여야 한다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }

  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] : (
        length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "ecr:GetAuthorizationToken")]) == 1
        && alltrue([
          for s in stmts : (
            toset(flatten([try(s.Action, [])])) == toset(["ecr:GetAuthorizationToken"])
            && toset(flatten([try(s.Resource, [])])) == toset(["*"])
            && length(flatten([try(s.Resource, [])])) == 1
          )
          if contains(flatten([try(s.Action, [])]), "ecr:GetAuthorizationToken")
        ])
      )
    ])
    error_message = "ci_push 정책의 토큰 문은 ecr:GetAuthorizationToken 하나를 Resource \"*\" 하나에 허용해야 한다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }

  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] : (
        length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "ecr:PutImage")]) == 1
        && alltrue([
          for s in stmts :
          toset(flatten([try(s.Action, [])])) == toset(["ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"])
          && length(flatten([try(s.Action, [])])) == 5
          if contains(flatten([try(s.Action, [])]), "ecr:PutImage")
        ])
      )
    ])
    error_message = "ci_push 정책의 올리기 문은 BatchCheckLayerAvailability·InitiateLayerUpload·UploadLayerPart·CompleteLayerUpload·PutImage 정확히 다섯 Action이어야 한다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }

  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] :
      alltrue([
        for s in stmts : (
          length(flatten([try(s.Resource, [])])) == 3
          && toset(flatten([try(s.Resource, [])])) == toset([for svc in ["backend", "pipeline-worker", "ai-engine"] : try(aws_ecr_repository.service[svc].arn, "missing-repo:${svc}")])
        )
        if contains(flatten([try(s.Action, [])]), "ecr:PutImage")
      ])
      && length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "ecr:PutImage")]) == 1
    ])
    error_message = "ci_push 정책의 올리기 문 Resource는 세 서비스 저장소 ARN 전부, 정확히 3개여야 한다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}, 저장소 ARN: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.arn })}"
  }
}

# ci_push에 ecr:*나 Resource "*"가 끼는 것을 막는다 — 토큰 발급 문 하나만 "*"를 쓸 수 있고, 와일드카드 Action이 있으면 저장소를 지우거나 정책을 바꿀 수도 있게 된다.
run "ci_push_policy_has_no_wildcard_action_and_one_star_resource" {
  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] : (
        length(stmts) > 0
        && alltrue([
          for s in stmts : (
            length(flatten([try(s.Action, [])])) > 0
            && alltrue([for a in flatten([try(s.Action, [])]) : !strcontains(a, "*")])
          )
        ])
      )
    ])
    error_message = "ci_push 정책의 Action에 와일드카드(*)가 있거나 Action이 비어 있다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }

  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] : (
        length([for s in stmts : s if contains(flatten([try(s.Resource, [])]), "*")]) == 1
        && alltrue([
          for s in stmts : toset(flatten([try(s.Action, [])])) == toset(["ecr:GetAuthorizationToken"])
          if contains(flatten([try(s.Resource, [])]), "*")
        ])
      )
    ])
    error_message = "ci_push 정책에서 Resource가 \"*\"인 문은 ecr:GetAuthorizationToken 하나뿐이어야 한다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }

  assert {
    condition = one([
      for stmts in [flatten([try(jsondecode(aws_iam_role_policy.ci_push.policy).Statement, [])])] :
      alltrue([
        for s in stmts :
        length(setintersection(toset(try(keys(s), ["unreadable"])), toset(["NotAction", "NotResource", "Principal"]))) == 0
      ])
    ])
    error_message = "ci_push 정책 문에 NotAction·NotResource·Principal이 있다. 지금 정책: ${try(jsonencode(jsondecode(aws_iam_role_policy.ci_push.policy)), aws_iam_role_policy.ci_push.policy)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 6. 알림(SNS)
# ─────────────────────────────────────────────────────────────────────────────

# 주제 이름이 어긋나거나 AWS 관리형 키로 암호화되는 것을 막는다 — AWS 관리형 키(alias/aws/sns)로 암호화한 주제에는 CloudWatch 경보가 게시하지 못해 알림이 조용히 사라진다.
run "sns_topic_is_named_and_not_encrypted_with_a_managed_key" {
  assert {
    condition     = aws_sns_topic.alerts.name == "whycode-staging-alerts"
    error_message = "SNS 주제 이름은 <project>-<environment>-alerts(whycode-staging-alerts)여야 한다. 지금 값: ${jsonencode(aws_sns_topic.alerts.name)}"
  }

  assert {
    # 설정하지 않으면 null이다(이 속성은 계산 속성이 아니라서 가짜 provider도 null로 둔다).
    condition     = try(aws_sns_topic.alerts.kms_master_key_id, "set") == null
    error_message = "SNS 주제에 kms_master_key_id를 설정하면 안 된다(AWS 관리형 키로 암호화한 주제에는 CloudWatch 경보가 게시하지 못한다). 지금 값: ${jsonencode(aws_sns_topic.alerts.kms_master_key_id)}"
  }
}

# 구독이 엉뚱한 주제·방식·주소로 연결되는 것을 막는다 — email-json이면 사람이 읽을 수 없는 JSON이 오고, 주소가 변수와 다르면 알림이 다른 사람에게 간다.
run "sns_email_subscription_points_at_alert_email" {
  assert {
    condition     = aws_sns_topic_subscription.alerts_email.topic_arn == aws_sns_topic.alerts.arn
    error_message = "구독의 topic_arn은 alerts 주제의 ARN이어야 한다. 지금 값: ${jsonencode(aws_sns_topic_subscription.alerts_email.topic_arn)}, 주제 ARN: ${aws_sns_topic.alerts.arn}"
  }

  assert {
    condition     = aws_sns_topic_subscription.alerts_email.protocol == "email"
    error_message = "구독 protocol은 email이어야 한다. 지금 값: ${jsonencode(aws_sns_topic_subscription.alerts_email.protocol)}"
  }

  assert {
    condition     = aws_sns_topic_subscription.alerts_email.endpoint == var.alert_email && var.alert_email == "ops@example.com"
    error_message = "구독 endpoint는 변수 alert_email(ops@example.com)이어야 한다. 지금 값: ${jsonencode(aws_sns_topic_subscription.alerts_email.endpoint)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 7. 예산
# ─────────────────────────────────────────────────────────────────────────────

# 예산의 이름·종류·주기·한도·단위가 어긋나는 것을 막는다 — 주기가 ANNUALLY면 한 달 안에 한도를 넘어도 경보가 안 오고, 단위가 USD가 아니면 한도 숫자의 뜻이 달라진다.
run "budget_is_a_monthly_cost_limit_in_usd" {
  assert {
    condition     = aws_budgets_budget.monthly.name == "whycode-staging-monthly"
    error_message = "예산 이름은 <project>-<environment>-monthly(whycode-staging-monthly)여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.name)}"
  }

  assert {
    condition     = aws_budgets_budget.monthly.budget_type == "COST"
    error_message = "예산 budget_type은 COST여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.budget_type)}"
  }

  assert {
    condition     = aws_budgets_budget.monthly.time_unit == "MONTHLY"
    error_message = "예산 time_unit은 MONTHLY여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.time_unit)}"
  }

  assert {
    # limit_amount는 문자열이다. 변수 50이 tostring되어 "50"이다("50.0"·"50.00"이면 계약과 다르다).
    condition     = aws_budgets_budget.monthly.limit_amount == "50"
    error_message = "예산 limit_amount는 tostring(monthly_budget_usd)인 \"50\"이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.limit_amount)}"
  }

  assert {
    condition     = aws_budgets_budget.monthly.limit_unit == "USD"
    error_message = "예산 limit_unit은 USD여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.limit_unit)}"
  }
}

# 알림이 하나만 있거나 임계값·기준이 어긋나는 것을 막는다 — 80%(조기 경고)와 100%(한도 도달) 둘 다, 예측(FORECASTED)이 아니라 실제(ACTUAL) 지출 기준이어야 한다.
run "budget_notifies_at_80_and_100_percent_of_actual_spend" {
  assert {
    condition     = length(aws_budgets_budget.monthly.notification) == 2
    error_message = "예산 notification은 정확히 2개여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    # notification은 집합이라 인덱스로 못 읽는다. 임계값만 모아 집합으로 비교한다(둘이 같은 값이면 집합 크기가 1이라 실패한다).
    condition     = toset([for n in aws_budgets_budget.monthly.notification : n.threshold]) == toset([80, 100])
    error_message = "예산 notification의 threshold는 80과 100이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    condition     = alltrue([for n in aws_budgets_budget.monthly.notification : n.comparison_operator == "GREATER_THAN"])
    error_message = "예산 notification의 comparison_operator는 모두 GREATER_THAN이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    condition     = alltrue([for n in aws_budgets_budget.monthly.notification : n.threshold_type == "PERCENTAGE"])
    error_message = "예산 notification의 threshold_type은 모두 PERCENTAGE여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    condition     = alltrue([for n in aws_budgets_budget.monthly.notification : n.notification_type == "ACTUAL"])
    error_message = "예산 notification의 notification_type은 모두 ACTUAL이어야 한다(FORECASTED는 예측이라 한도를 넘은 뒤에야 알 수도 있다). 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    # 수신자는 변수 이메일 하나뿐이다. SNS 주제를 수신자로 얹으면 예산 서비스가 게시할 권한이 없어 알림이 사라진다.
    condition = alltrue([
      for n in aws_budgets_budget.monthly.notification : (
        length(coalesce(n.subscriber_email_addresses, [])) == 1
        && toset(coalesce(n.subscriber_email_addresses, [])) == toset([var.alert_email])
        && length(coalesce(n.subscriber_sns_topic_arns, [])) == 0
      )
    ])
    error_message = "예산 notification 수신자는 변수 alert_email(ops@example.com) 하나뿐이어야 한다(SNS 주제 수신자 없음). 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }
}

# 비용 필터가 붙는 것을 막는다 — 계정 전체 지출 기준이어야 한다. 비용 태그를 청구 콘솔에서 켜지 않으면 태그 필터 예산은 조용히 0원으로 보여 경보가 울리지 않는다.
run "budget_covers_the_whole_account" {
  assert {
    condition     = length(aws_budgets_budget.monthly.cost_filter) == 0
    error_message = "예산에 cost_filter가 있다. 계정 전체 지출 기준이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.cost_filter)}"
  }

  assert {
    # 새 방식(filter_expression)으로도 같은 필터를 걸 수 있으므로 함께 막는다.
    condition     = length(aws_budgets_budget.monthly.filter_expression) == 0
    error_message = "예산에 filter_expression이 있다. 계정 전체 지출 기준이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.filter_expression)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 8. 출력 — 다음 단계(network·edge·data·app 모듈, CI 워크플로)로 넘길 값
# ─────────────────────────────────────────────────────────────────────────────

# 출력이 엉뚱한 속성이나 일부 서비스만 내보내는 것을 막는다 — ECR 출력은 CI 푸시와 ECS 작업 정의의 이미지 주소가, 로그 그룹 출력은 작업 정의의 로그 설정이 가리킨다.
run "outputs_return_ecr_and_log_group_values" {
  assert {
    condition     = toset(try(keys(output.ecr_repository_urls), [])) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "ecr_repository_urls 출력의 키는 backend·pipeline-worker·ai-engine이어야 한다. 지금 값: ${jsonencode(output.ecr_repository_urls)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.ecr_repository_urls[svc], null) == try(aws_ecr_repository.service[svc].repository_url, "missing-repo:${svc}")
    ])
    error_message = "ecr_repository_urls 출력은 서비스별 repository_url이어야 한다. 지금 값: ${jsonencode(output.ecr_repository_urls)}, 기대: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.repository_url })}"
  }

  assert {
    condition     = toset(try(keys(output.ecr_repository_arns), [])) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "ecr_repository_arns 출력의 키는 backend·pipeline-worker·ai-engine이어야 한다. 지금 값: ${jsonencode(output.ecr_repository_arns)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.ecr_repository_arns[svc], null) == try(aws_ecr_repository.service[svc].arn, "missing-repo:${svc}")
    ])
    error_message = "ecr_repository_arns 출력은 서비스별 arn이어야 한다. 지금 값: ${jsonencode(output.ecr_repository_arns)}, 기대: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.arn })}"
  }

  assert {
    condition     = toset(try(keys(output.log_group_names), [])) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "log_group_names 출력의 키는 backend·pipeline-worker·ai-engine이어야 한다. 지금 값: ${jsonencode(output.log_group_names)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.log_group_names[svc], null) == try(aws_cloudwatch_log_group.service[svc].name, "missing-log-group:${svc}")
    ])
    error_message = "log_group_names 출력은 서비스별 로그 그룹 이름이어야 한다. 지금 값: ${jsonencode(output.log_group_names)}, 기대: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.name })}"
  }
}

# 출력이 엉뚱한 속성이나 일부만 내보내는 것을 막는다 — 비밀 ARN은 ECS 작업 정의의 secrets가, 실행 역할은 작업 정의의 executionRoleArn이 가리킨다.
run "outputs_return_secret_and_execution_role_values" {
  assert {
    condition = toset(try(keys(output.secret_arns), [])) == toset([
      "backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal",
    ])
    error_message = "secret_arns 출력의 키는 비밀 여섯 개 전부여야 한다. 지금 값: ${jsonencode(output.secret_arns)}"
  }

  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(output.secret_arns[k], null) == try(aws_secretsmanager_secret.app[k].arn, "missing-secret:${k}")
    ])
    error_message = "secret_arns 출력은 비밀 키별 arn이어야 한다. 지금 값: ${jsonencode(output.secret_arns)}, 기대: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.arn })}"
  }

  assert {
    condition     = toset(try(keys(output.execution_role_arns), [])) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "execution_role_arns 출력의 키는 backend·pipeline-worker·ai-engine이어야 한다. 지금 값: ${jsonencode(output.execution_role_arns)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.execution_role_arns[svc], null) == try(aws_iam_role.execution[svc].arn, "missing-role:${svc}")
    ])
    error_message = "execution_role_arns 출력은 서비스별 실행 역할 arn이어야 한다. 지금 값: ${jsonencode(output.execution_role_arns)}, 기대: ${jsonencode({ for k, r in aws_iam_role.execution : k => r.arn })}"
  }

  assert {
    condition     = toset(try(keys(output.execution_role_names), [])) == toset(["backend", "pipeline-worker", "ai-engine"])
    error_message = "execution_role_names 출력의 키는 backend·pipeline-worker·ai-engine이어야 한다. 지금 값: ${jsonencode(output.execution_role_names)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.execution_role_names[svc], null) == try(aws_iam_role.execution[svc].name, "missing-role:${svc}")
    ])
    error_message = "execution_role_names 출력은 서비스별 실행 역할 이름이어야 한다. 지금 값: ${jsonencode(output.execution_role_names)}, 기대: ${jsonencode({ for k, r in aws_iam_role.execution : k => r.name })}"
  }
}

# 출력이 엉뚱한 속성을 내보내는 것을 막는다 — CI 역할 ARN은 GitHub 워크플로의 role-to-assume이, 배포 역할 이름은 다음 묶음의 정책이, 주제 ARN은 경보의 알림 대상이 가리킨다.
run "outputs_return_ci_role_and_topic_values" {
  assert {
    condition     = output.ci_push_role_arn == aws_iam_role.ci_push.arn
    error_message = "ci_push_role_arn 출력이 ci_push 역할 arn과 다르다. 지금 값: ${jsonencode(output.ci_push_role_arn)}, 기대: ${aws_iam_role.ci_push.arn}"
  }

  assert {
    condition     = output.ci_deploy_role_arn == aws_iam_role.ci_deploy.arn
    error_message = "ci_deploy_role_arn 출력이 ci_deploy 역할 arn과 다르다. 지금 값: ${jsonencode(output.ci_deploy_role_arn)}, 기대: ${aws_iam_role.ci_deploy.arn}"
  }

  assert {
    condition     = output.ci_deploy_role_name == aws_iam_role.ci_deploy.name
    error_message = "ci_deploy_role_name 출력이 ci_deploy 역할 이름과 다르다. 지금 값: ${jsonencode(output.ci_deploy_role_name)}, 기대: ${aws_iam_role.ci_deploy.name}"
  }

  assert {
    condition     = output.sns_topic_arn == aws_sns_topic.alerts.arn
    error_message = "sns_topic_arn 출력이 alerts 주제 arn과 다르다. 지금 값: ${jsonencode(output.sns_topic_arn)}, 기대: ${aws_sns_topic.alerts.arn}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 9. AWS가 거부하는 값 — 가짜 provider는 일부(IAM 역할 이름 패턴, ARN 형식)만 스스로 검사하고 대부분은 걸러 주지 않아, 실제 apply에서야 알게 되는 이름·설명 규칙
# ─────────────────────────────────────────────────────────────────────────────

# 이름에 AWS가 받지 않는 문자나 길이가 들어가는 것을 막는다. 역할 이름은 최대 64자이고 /를 못 쓰며, SNS 주제는 영문·숫자·_·-만, 예산 이름은 :와 \를 못 쓴다.
run "names_follow_aws_character_rules" {
  assert {
    # 계약의 공통 이름 규칙(영문·숫자와 . _ / + = @ -)을 모든 역할·SNS·예산 이름에 건다.
    condition = alltrue(concat(
      [for r in aws_iam_role.execution : can(regex("^[A-Za-z0-9._/+=@-]+$", r.name))],
      [can(regex("^[A-Za-z0-9._/+=@-]+$", aws_iam_role.ci_push.name)), can(regex("^[A-Za-z0-9._/+=@-]+$", aws_iam_role.ci_deploy.name))],
      [can(regex("^[A-Za-z0-9._/+=@-]+$", aws_sns_topic.alerts.name))],
      [can(regex("^[A-Za-z0-9._/+=@-]+$", aws_budgets_budget.monthly.name))],
    ))
    error_message = "역할·SNS·예산 이름은 영문·숫자와 . _ / + = @ - 만 써야 한다. 지금 값: 실행 역할 ${jsonencode({ for k, r in aws_iam_role.execution : k => r.name })}, ci_push ${jsonencode(aws_iam_role.ci_push.name)}, ci_deploy ${jsonencode(aws_iam_role.ci_deploy.name)}, SNS ${jsonencode(aws_sns_topic.alerts.name)}, 예산 ${jsonencode(aws_budgets_budget.monthly.name)}"
  }

  assert {
    # IAM 역할 이름: 영문·숫자와 _ + = , . @ - 만, 1~64자(/는 못 쓴다).
    condition = alltrue(concat(
      [for r in aws_iam_role.execution : can(regex("^[A-Za-z0-9_+=,.@-]{1,64}$", r.name))],
      [can(regex("^[A-Za-z0-9_+=,.@-]{1,64}$", aws_iam_role.ci_push.name)), can(regex("^[A-Za-z0-9_+=,.@-]{1,64}$", aws_iam_role.ci_deploy.name))],
    ))
    error_message = "IAM 역할 이름은 영문·숫자와 _ + = , . @ - 만, 1~64자여야 한다. 지금 값: 실행 역할 ${jsonencode({ for k, r in aws_iam_role.execution : k => r.name })}, ci_push ${jsonencode(aws_iam_role.ci_push.name)}, ci_deploy ${jsonencode(aws_iam_role.ci_deploy.name)}"
  }

  assert {
    # IAM 인라인 정책 이름: 영문·숫자와 _ + = , . @ - 만, 1~128자.
    condition = alltrue(concat(
      [for p in aws_iam_role_policy.execution : can(regex("^[A-Za-z0-9_+=,.@-]{1,128}$", p.name))],
      [can(regex("^[A-Za-z0-9_+=,.@-]{1,128}$", aws_iam_role_policy.ci_push.name))],
    ))
    error_message = "IAM 정책 이름은 영문·숫자와 _ + = , . @ - 만, 1~128자여야 한다. 지금 값: 실행 정책 ${jsonencode({ for k, p in aws_iam_role_policy.execution : k => p.name })}, ci_push ${jsonencode(aws_iam_role_policy.ci_push.name)}"
  }

  assert {
    # SNS 주제 이름: 영문·숫자·_·- 만, 1~256자.
    condition     = can(regex("^[A-Za-z0-9_-]{1,256}$", aws_sns_topic.alerts.name))
    error_message = "SNS 주제 이름은 영문·숫자·_·- 만, 1~256자여야 한다. 지금 값: ${jsonencode(aws_sns_topic.alerts.name)}"
  }

  assert {
    # 예산 이름: 1~100자, :와 \는 못 쓴다.
    condition     = can(regex("^[^:\\\\]{1,100}$", aws_budgets_budget.monthly.name))
    error_message = "예산 이름은 1~100자이고 :와 \\를 못 쓴다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.name)}"
  }

  assert {
    # ECR 저장소 이름: 소문자·숫자와 . _ - 구분자, / 로 나뉜 경로, 2~256자(대문자를 못 쓴다).
    condition = alltrue([
      for r in aws_ecr_repository.service :
      can(regex("^([a-z0-9]+([._-][a-z0-9]+)*/)*[a-z0-9]+([._-][a-z0-9]+)*$", r.name)) && length(r.name) >= 2 && length(r.name) <= 256
    ])
    error_message = "ECR 저장소 이름은 소문자·숫자와 . _ - 구분자, / 경로로 2~256자여야 한다. 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.name })}"
  }

  assert {
    # 로그 그룹 이름: 영문·숫자와 . - _ / # 만, 1~512자.
    condition = alltrue([
      for g in aws_cloudwatch_log_group.service :
      can(regex("^[A-Za-z0-9._/#-]{1,512}$", g.name))
    ])
    error_message = "로그 그룹 이름은 영문·숫자와 . - _ / # 만, 1~512자여야 한다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.name })}"
  }

  assert {
    # 비밀 이름: 영문·숫자와 / _ + = . @ - 만, 1~512자(공백을 못 쓴다).
    condition = alltrue([
      for s in aws_secretsmanager_secret.app :
      can(regex("^[A-Za-z0-9/_+=.@-]{1,512}$", s.name))
    ])
    error_message = "비밀 이름은 영문·숫자와 / _ + = . @ - 만, 1~512자여야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.name })}"
  }

  assert {
    # 비밀 설명: 영어 ASCII 한 줄(허용 문자는 secret_descriptions_are_ascii_and_not_empty와 같다), 비어 있지 않고 2048자 이하.
    # 정규식 반복 횟수는 1000이 한계라({1,2048}은 컴파일 오류) 길이는 length()로 따로 본다.
    condition = alltrue([
      for s in aws_secretsmanager_secret.app :
      can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", s.description)) && length(s.description) <= 2048
    ])
    error_message = "비밀 description은 영어 ASCII 한 줄, 1~2048자여야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.description })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 10. 변수가 값으로 흘러가는지 — 고정 문자열이 아니라 변수에서 나온다는 증거
# ─────────────────────────────────────────────────────────────────────────────

# 이름이 "whycode"·"staging" 같은 고정 문자열로 박히는 것을 막는다 — project·environment를 바꾸면 모든 이름이 따라 바뀌어야 한다.
run "names_follow_project_and_environment_variables" {
  variables {
    project     = "sample"
    environment = "prod"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_ecr_repository.service[svc].name, null) == "sample-prod/${svc}"
    ])
    error_message = "ECR 저장소 이름이 project·environment를 따라가야 한다(sample-prod/<서비스>). 지금 값: ${jsonencode({ for k, r in aws_ecr_repository.service : k => r.name })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].name, null) == "/sample/prod/${svc}"
    ])
    error_message = "로그 그룹 이름이 project·environment를 따라가야 한다(/sample/prod/<서비스>). 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.name })}"
  }

  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(aws_secretsmanager_secret.app[k].name, null) == "sample/prod/${k}"
    ])
    error_message = "비밀 이름이 project·environment를 따라가야 한다(sample/prod/<키>). 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.name })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_iam_role.execution[svc].name, null) == "sample-prod-${svc}-exec"
    ])
    error_message = "실행 역할 이름이 project·environment를 따라가야 한다(sample-prod-<서비스>-exec). 지금 값: ${jsonencode({ for k, r in aws_iam_role.execution : k => r.name })}"
  }

  assert {
    condition     = aws_iam_role.ci_push.name == "sample-prod-ci-push" && aws_iam_role.ci_deploy.name == "sample-prod-ci-deploy"
    error_message = "CI 역할 이름이 project·environment를 따라가야 한다(sample-prod-ci-push·sample-prod-ci-deploy). 지금 값: ${jsonencode(aws_iam_role.ci_push.name)}, ${jsonencode(aws_iam_role.ci_deploy.name)}"
  }

  assert {
    condition     = aws_sns_topic.alerts.name == "sample-prod-alerts" && aws_budgets_budget.monthly.name == "sample-prod-monthly"
    error_message = "SNS 주제·예산 이름이 project·environment를 따라가야 한다(sample-prod-alerts·sample-prod-monthly). 지금 값: ${jsonencode(aws_sns_topic.alerts.name)}, ${jsonencode(aws_budgets_budget.monthly.name)}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(output.log_group_names[svc], null) == "/sample/prod/${svc}"
    ]) && output.ci_deploy_role_name == "sample-prod-ci-deploy"
    error_message = "출력이 새 이름을 따라가야 한다. log_group_names: ${jsonencode(output.log_group_names)}, ci_deploy_role_name: ${jsonencode(output.ci_deploy_role_name)}"
  }
}

# CI 신뢰 정책이 고정 문자열이 되는 것을 막는다 — 저장소·GitHub environment·OIDC provider를 바꾸면 sub와 Principal이 따라 바뀌어야 한다.
# (환경 이름이 production이어도 환경 변수 environment는 prod이고, GitHub environment는 production으로 서로 다르다.)
run "ci_trust_follows_github_variables" {
  variables {
    environment              = "prod"
    github_repository        = "example-org/other-repo"
    github_environment       = "production"
    github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/oidc.example.com"
  }

  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:sub"], [])])) == toset(["repo:example-org/other-repo:environment:production"])
              && length(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:sub"], [])])) == 1
            )
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 sub는 변수를 따라 \"repo:example-org/other-repo:environment:production\"이어야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }

  assert {
    condition = alltrue(flatten([
      for role, policy in {
        "ci_push"   = aws_iam_role.ci_push.assume_role_policy
        "ci_deploy" = aws_iam_role.ci_deploy.assume_role_policy
        } : [
        for stmts in [flatten([try(jsondecode(policy).Statement, [])])] : (
          length(stmts) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Principal.Federated, [])])) == toset(["arn:aws:iam::123456789012:oidc-provider/oidc.example.com"])
              && toset(flatten([try(s.Condition.StringEquals["token.actions.githubusercontent.com:aud"], [])])) == toset(["sts.amazonaws.com"])
              && toset(try(keys(s.Condition.StringEquals), [])) == toset(["token.actions.githubusercontent.com:aud", "token.actions.githubusercontent.com:sub"])
              && toset(try(keys(s.Condition), [])) == toset(["StringEquals"])
            )
          ])
        )
      ]
    ]))
    error_message = "CI 역할 신뢰 정책의 Principal.Federated는 변수 github_oidc_provider_arn을 따라가야 하고, aud 조건·조건 키 구성은 그대로여야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }

  assert {
    condition     = !strcontains(aws_iam_role.ci_push.assume_role_policy, "*") && !strcontains(aws_iam_role.ci_deploy.assume_role_policy, "*")
    error_message = "변수를 바꿔도 CI 신뢰 정책에 와일드카드(*)가 없어야 한다. 지금 정책: ci_push ${aws_iam_role.ci_push.assume_role_policy}, ci_deploy ${aws_iam_role.ci_deploy.assume_role_policy}"
  }
}

# 숫자·이메일 변수가 고정값으로 박히는 것을 막는다 — 변수를 바꾸면 수명 주기 개수, 로그 보존, 비밀 삭제 유예, 예산 한도, 알림 주소가 따라 바뀌어야 한다.
# 예시 이메일은 ops@example.com 하나만 쓰는 규칙이라, 파일 수준 변수를 upper()로 대문자로 바꾼 값으로 "고정값이 아니라 변수에서 나왔다"를 확인한다.
run "numbers_and_email_flow_into_resources" {
  variables {
    ecr_max_image_count         = 5
    log_retention_days          = 30
    secret_recovery_window_days = 7
    monthly_budget_usd          = 120
    alert_email                 = upper(var.alert_email)
  }

  assert {
    condition = alltrue(flatten([
      for svc in ["backend", "pipeline-worker", "ai-engine"] : [
        for rules in [try(jsondecode(aws_ecr_lifecycle_policy.service[svc].policy).rules, [])] :
        length(rules) == 1 && alltrue([for r in rules : try(r.selection.countNumber, null) == 5])
      ]
    ]))
    error_message = "수명 주기 규칙의 countNumber가 ecr_max_image_count(5)를 따라가야 한다. 지금 정책: ${jsonencode({ for k, p in aws_ecr_lifecycle_policy.service : k => try(jsondecode(p.policy), p.policy) })}"
  }

  assert {
    condition = alltrue([
      for svc in ["backend", "pipeline-worker", "ai-engine"] :
      try(aws_cloudwatch_log_group.service[svc].retention_in_days, null) == 30
    ])
    error_message = "로그 그룹 retention_in_days가 log_retention_days(30)를 따라가야 한다. 지금 값: ${jsonencode({ for k, g in aws_cloudwatch_log_group.service : k => g.retention_in_days })}"
  }

  assert {
    condition = alltrue([
      for k in ["backend", "pipeline-worker", "ai-engine", "shared-credentials", "shared-alert", "shared-internal"] :
      try(aws_secretsmanager_secret.app[k].recovery_window_in_days, null) == 7
    ])
    error_message = "비밀 recovery_window_in_days가 secret_recovery_window_days(7)를 따라가야 한다. 지금 값: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : k => s.recovery_window_in_days })}"
  }

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "120"
    error_message = "예산 limit_amount가 monthly_budget_usd(120)의 문자열 \"120\"이어야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.limit_amount)}"
  }

  assert {
    condition = alltrue([
      for n in aws_budgets_budget.monthly.notification :
      toset(coalesce(n.subscriber_email_addresses, [])) == toset([upper("ops@example.com")]) && length(coalesce(n.subscriber_email_addresses, [])) == 1
    ]) && length(aws_budgets_budget.monthly.notification) == 2
    error_message = "예산 알림 수신자가 alert_email(대문자로 바꾼 값)을 따라가야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.notification)}"
  }

  assert {
    condition     = aws_sns_topic_subscription.alerts_email.endpoint == upper("ops@example.com")
    error_message = "SNS 구독 endpoint가 alert_email(대문자로 바꾼 값)을 따라가야 한다. 지금 값: ${jsonencode(aws_sns_topic_subscription.alerts_email.endpoint)}"
  }
}

# 실행 정책이 비밀 이름을 하드코딩하는 것을 막는다 — 정책의 비밀 목록은 app_secrets의 readers에서 계산돼야 하므로, 비밀 정의를 바꾸면 서비스별 읽기 대상이 따라 바뀌어야 한다.
# backend → {alpha, gamma}, pipeline-worker → {beta}, ai-engine → {beta, gamma}.
run "execution_policy_follows_app_secret_readers" {
  variables {
    app_secrets = {
      "alpha" = {
        description = "Read only by the backend"
        readers     = ["backend"]
      }
      "beta" = {
        description = "Read by pipeline-worker and ai-engine"
        readers     = ["pipeline-worker", "ai-engine"]
      }
      "gamma" = {
        description = "Read by backend and ai-engine"
        readers     = ["backend", "ai-engine"]
      }
    }
  }

  assert {
    condition     = toset(keys(aws_secretsmanager_secret.app)) == toset(["alpha", "beta", "gamma"])
    error_message = "비밀은 app_secrets의 키(alpha·beta·gamma)와 같아야 한다. 지금 키: ${jsonencode(keys(aws_secretsmanager_secret.app))}"
  }

  assert {
    condition = alltrue(flatten([
      for svc, secret_keys in {
        "backend"         = ["alpha", "gamma"]
        "pipeline-worker" = ["beta"]
        "ai-engine"       = ["beta", "gamma"]
        } : [
        for stmts in [flatten([try(jsondecode(aws_iam_role_policy.execution[svc].policy).Statement, [])])] : (
          length(stmts) == 4
          && length([for s in stmts : s if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")]) == 1
          && alltrue([
            for s in stmts : (
              toset(flatten([try(s.Resource, [])])) == toset([for k in secret_keys : try(aws_secretsmanager_secret.app[k].arn, "missing-secret:${k}")])
              && length(flatten([try(s.Resource, [])])) == length(secret_keys)
            )
            if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")
          ])
        )
      ]
    ]))
    error_message = "서비스가 읽는 비밀은 app_secrets의 readers에서 계산돼야 한다(backend → alpha·gamma, pipeline-worker → beta, ai-engine → beta·gamma). 서비스별 지금 Resource: ${jsonencode({ for svc, p in aws_iam_role_policy.execution : svc => flatten([for s in flatten([try(jsondecode(p.policy).Statement, [])]) : flatten([try(s.Resource, [])]) if contains(flatten([try(s.Action, [])]), "secretsmanager:GetSecretValue")]) })}, 비밀 ARN → 비밀 키: ${jsonencode({ for k, s in aws_secretsmanager_secret.app : s.arn => k })}"
  }

  assert {
    condition     = toset(try(keys(output.secret_arns), [])) == toset(["alpha", "beta", "gamma"])
    error_message = "secret_arns 출력의 키는 app_secrets의 키여야 한다. 지금 값: ${jsonencode(output.secret_arns)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 11. 경계값 — 검증이 지나치게 엄격해 정상 값을 막지 않는지(plan만으로 확인)
# ─────────────────────────────────────────────────────────────────────────────

# 삭제 유예 기간의 아래쪽 경계 7(허용 범위 7~30의 시작)을 막지 않는다.
run "accepts_secret_recovery_window_of_7_days" {
  command = plan

  variables {
    secret_recovery_window_days = 7
  }

  assert {
    condition     = aws_secretsmanager_secret.app["backend"].recovery_window_in_days == 7
    error_message = "secret_recovery_window_days = 7은 허용 범위(0 또는 7~30)여야 한다. 지금 값: ${jsonencode(aws_secretsmanager_secret.app["backend"].recovery_window_in_days)}"
  }
}

# 삭제 유예 기간의 위쪽 경계 30(허용 범위의 끝)을 막지 않는다.
run "accepts_secret_recovery_window_of_30_days" {
  command = plan

  variables {
    secret_recovery_window_days = 30
  }

  assert {
    condition     = aws_secretsmanager_secret.app["backend"].recovery_window_in_days == 30
    error_message = "secret_recovery_window_days = 30은 허용 범위(0 또는 7~30)여야 한다. 지금 값: ${jsonencode(aws_secretsmanager_secret.app["backend"].recovery_window_in_days)}"
  }
}

# 저장소 이름에 점·밑줄·하이픈·숫자·대문자가 든 정상 값을 막지 않는다 — GitHub 저장소·소유자 이름에 흔한 문자다.
run "accepts_github_repository_with_dots_underscores_and_hyphens" {
  command = plan

  variables {
    github_repository = "Example_Org.1/repo-name_2.x"
  }

  assert {
    condition     = var.github_repository == "Example_Org.1/repo-name_2.x"
    error_message = "owner/repo에 점·밑줄·하이픈·숫자·대문자가 든 값은 허용돼야 한다."
  }
}

# 이메일에 + 태그와 하위 도메인이 든 정상 값을 막지 않는다 — 파일 수준 변수에서 replace()로 @ 앞에 +alerts를, 뒤에 mail.을 붙여 만든다.
run "accepts_alert_email_with_plus_tag_and_subdomain" {
  command = plan

  variables {
    alert_email = replace(var.alert_email, "@", "+alerts@mail.")
  }

  assert {
    condition     = var.alert_email == replace("ops@example.com", "@", "+alerts@mail.")
    error_message = "+ 태그와 하위 도메인이 든 이메일은 허용돼야 한다. 지금 값: ${jsonencode(var.alert_email)}"
  }
}

# 소수 예산 한도도 막지 않는다 — 0보다 크기만 하면 된다.
run "accepts_fractional_monthly_budget" {
  command = plan

  variables {
    monthly_budget_usd = 0.5
  }

  assert {
    condition     = aws_budgets_budget.monthly.limit_amount == "0.5"
    error_message = "monthly_budget_usd = 0.5는 허용돼야 하고 limit_amount는 \"0.5\"여야 한다. 지금 값: ${jsonencode(aws_budgets_budget.monthly.limit_amount)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 12. 변수 검증 — 잘못된 값은 plan 단계에서 막힌다(command = plan, expect_failures)
# ─────────────────────────────────────────────────────────────────────────────

# environment가 staging·prod 밖이면 막는다 — 이름 접두사가 모든 자원에 박히므로 오타 하나가 환경을 새로 만든다.
run "rejects_unknown_environment" {
  command = plan

  variables {
    environment = "dev"
  }

  expect_failures = [
    var.environment,
  ]
}

# environment에 GitHub 쪽 이름(production)을 넣는 실수를 막는다 — Terraform 환경은 prod, GitHub environment는 production으로 서로 다르다.
run "rejects_github_environment_name_as_environment" {
  command = plan

  variables {
    environment = "production"
  }

  expect_failures = [
    var.environment,
  ]
}

# github_environment가 staging·production 밖이면 막는다 — 신뢰 정책의 sub에 들어가는 값이라 오타가 나면 아무 워크플로도 역할을 빌리지 못한다.
run "rejects_unknown_github_environment" {
  command = plan

  variables {
    github_environment = "dev"
  }

  expect_failures = [
    var.github_environment,
  ]
}

# github_environment에 Terraform 쪽 이름(prod)을 넣는 실수를 막는다 — GitHub environment는 production이다.
run "rejects_terraform_environment_name_as_github_environment" {
  command = plan

  variables {
    github_environment = "prod"
  }

  expect_failures = [
    var.github_environment,
  ]
}

# github_repository에서 소유자가 빠진 값을 막는다 — sub가 repo:history-tracker:...가 되어 어떤 워크플로도 일치하지 않는다.
run "rejects_github_repository_without_owner" {
  command = plan

  variables {
    github_repository = "history-tracker"
  }

  expect_failures = [
    var.github_repository,
  ]
}

# github_repository에 와일드카드가 든 값을 막는다 — sub가 repo:se-zero/*:...가 되어 소유자의 모든 저장소가 역할을 빌린다.
run "rejects_github_repository_with_wildcard" {
  command = plan

  variables {
    github_repository = "se-zero/*"
  }

  expect_failures = [
    var.github_repository,
  ]
}

# github_repository에 경로가 하나 더 붙은 값을 막는다 — owner/repo 두 칸뿐이다.
run "rejects_github_repository_with_extra_path" {
  command = plan

  variables {
    github_repository = "se-zero/history-tracker/extra"
  }

  expect_failures = [
    var.github_repository,
  ]
}

# alert_email에 @가 없는 값을 막는다 — 구독 확인 메일이 가지 않아 알림이 영영 오지 않는다.
run "rejects_alert_email_without_at_sign" {
  command = plan

  variables {
    alert_email = "ops.example.com"
  }

  expect_failures = [
    var.alert_email,
  ]
}

# alert_email에 도메인의 점이 없는 값을 막는다.
run "rejects_alert_email_without_domain_dot" {
  command = plan

  variables {
    alert_email = "ops@example"
  }

  expect_failures = [
    var.alert_email,
  ]
}

# alert_email에 공백이 든 값을 막는다.
run "rejects_alert_email_with_whitespace" {
  command = plan

  variables {
    alert_email = "ops @example.com"
  }

  expect_failures = [
    var.alert_email,
  ]
}

# alert_email에 @가 둘인 값을 막는다.
run "rejects_alert_email_with_two_at_signs" {
  command = plan

  variables {
    alert_email = "ops@@example.com"
  }

  expect_failures = [
    var.alert_email,
  ]
}

# 월 예산이 0이면 막는다 — 한도 0은 첫 요금부터 경보가 울리고, 의미 있는 한도가 아니다.
run "rejects_zero_monthly_budget" {
  command = plan

  variables {
    monthly_budget_usd = 0
  }

  expect_failures = [
    var.monthly_budget_usd,
  ]
}

# 월 예산이 음수면 막는다.
run "rejects_negative_monthly_budget" {
  command = plan

  variables {
    monthly_budget_usd = -5
  }

  expect_failures = [
    var.monthly_budget_usd,
  ]
}

# 삭제 유예 기간이 허용값(0 또는 7~30) 밖이면 막는다 — Secrets Manager는 1~6일을 거부한다.
run "rejects_secret_recovery_window_of_3_days" {
  command = plan

  variables {
    secret_recovery_window_days = 3
  }

  expect_failures = [
    var.secret_recovery_window_days,
  ]
}

# 삭제 유예 기간의 위쪽 경계(31)를 막는다.
run "rejects_secret_recovery_window_of_31_days" {
  command = plan

  variables {
    secret_recovery_window_days = 31
  }

  expect_failures = [
    var.secret_recovery_window_days,
  ]
}

# 삭제 유예 기간의 아래쪽 경계 바로 밖(6)을 막는다.
run "rejects_secret_recovery_window_of_6_days" {
  command = plan

  variables {
    secret_recovery_window_days = 6
  }

  expect_failures = [
    var.secret_recovery_window_days,
  ]
}

# 삭제 유예 기간이 음수면 막는다.
run "rejects_negative_secret_recovery_window" {
  command = plan

  variables {
    secret_recovery_window_days = -1
  }

  expect_failures = [
    var.secret_recovery_window_days,
  ]
}

# reader가 backend·pipeline-worker·ai-engine 밖이면 막는다 — 오타가 나면 그 비밀을 읽는 서비스가 조용히 하나도 없게 된다.
run "rejects_app_secret_reader_that_is_not_a_service" {
  command = plan

  variables {
    app_secrets = {
      "backend" = {
        description = "Backend-only application secrets"
        readers     = ["frontend"]
      }
    }
  }

  expect_failures = [
    var.app_secrets,
  ]
}

# 정상 reader와 알 수 없는 reader가 섞여 있어도 막는다 — 하나라도 틀리면 거부해야 한다.
run "rejects_app_secret_with_one_unknown_reader_among_valid_ones" {
  command = plan

  variables {
    app_secrets = {
      "shared" = {
        description = "Shared application secrets"
        readers     = ["backend", "pipeline_worker"]
      }
    }
  }

  expect_failures = [
    var.app_secrets,
  ]
}
