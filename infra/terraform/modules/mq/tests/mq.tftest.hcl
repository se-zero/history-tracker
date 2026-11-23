# T3 Amazon MQ(RabbitMQ) 모듈(브로커 1·관리자 비밀번호·비밀 껍데기와 값·읽기 권한)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/mq test
#
# 검사의 중심은 세 가지다.
#   1. 브로커가 인터넷에 열리지 않고(비공개·단일 인스턴스) RabbitMQ 4.x가 요구하는 mq.m7g에서만 돈다.
#   2. 관리자 비밀번호는 상태에 남을 수밖에 없다(aws_mq_broker.user.password에는 쓰기 전용 인자가 없다). 그래서 최소한 안정된 값
#      (random_password)이어야 하고, 사용자·URL에 그대로 쓸 수 있게 영숫자로만 만든다. 비밀 값은 쓰기 전용으로만 넣는다.
#   3. 비밀의 url이 브로커 주소와 어긋나지 않는다. 쓰기 전용 비밀은 버전이 같으면 다시 쓰이지 않아서, 브로커가 새로 만들어져도
#      옛 주소가 남는다. 그래서 비밀 버전이 브로커 id에서 파생돼야 한다.
#
# 읽는 법
# - 쓰기 전용 인자(secret_string_wo)는 값을 읽을 수 없다. "일반 secret_string이 비어 있고 버전이 브로커 id를 따른다"를 본다.
#   url 조립(amqps://사용자:비밀번호@호스트:5671/)은 값을 읽을 수 없어 소스에서 형태를 확인하고(아래 secret_url run), 실제 접속은 apply 뒤 확인으로 본다.
# - 브로커의 instances는 계산 속성이라 가짜 provider에서는 빈 목록이다. 아래 override_resource가 AWS가 돌려주는 모양으로 채운다.
#   4.2 이상은 amqps 엔드포인트와 Prometheus용 https(16001) 엔드포인트가 함께 오므로, 일부러 https를 앞에 둬서 amqps를 골라 쓰는지 본다.
# - 가짜 provider는 계산 속성에만 값을 만들고(apply 단계), 테스트 파일에는 locals를 쓸 수 없다. 오류로 끝나는 변수 검증 run은 맨 뒤에 둔다.

# 가짜 aws·random provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {}

# 가짜 random provider의 비밀번호는 8글자라 aws_mq_broker의 길이 검사(12~250자)에 걸린다. 32자 영숫자 값을 준다.
mock_provider "random" {
  mock_resource "random_password" {
    defaults = {
      result = "Ab3dEf6hJk9mNp2qRs5tUv8wXy1zAb4c"
    }
  }
}

override_resource {
  target = aws_mq_broker.this
  values = {
    id  = "b-12345678-90ab-cdef-1234-567890abcdef"
    arn = "arn:aws:mq:ap-northeast-2:123456789012:broker:whycode-staging-rabbitmq:b-12345678-90ab-cdef-1234-567890abcdef"
    instances = [
      {
        console_url = "https://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.on.aws"
        endpoints = [
          "https://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.amazonaws.com:16001",
          "amqps://b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.amazonaws.com:5671",
        ]
        ip_address = "10.20.10.25"
      },
    ]
  }
}

variables {
  environment       = "staging"
  subnet_id         = "subnet-0123456789abcdef0"
  security_group_id = "sg-0123456789abcdef0"

  execution_role_names = {
    backend           = "whycode-staging-backend-exec"
    "pipeline-worker" = "whycode-staging-pipeline-worker-exec"
    "ai-engine"       = "whycode-staging-ai-engine-exec"
  }
}

# 계약 밖 자원이 덧붙는 것을 막는다 — 아래 run들은 이름이 적힌 자원만 볼 수 있다. 비밀 값이 평문으로 남는 변형이나 두 번째 브로커, 공개 접근 규칙이 몰래 끼어들지 못하게 한다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "random_password.admin",
      "aws_mq_broker.this",
      "aws_secretsmanager_secret.mq",
      "aws_secretsmanager_secret_version.mq",
      "aws_iam_role_policy.read_secret",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 5개와 정확히 같아야 한다. 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
  }

  assert {
    condition     = length(flatten([for f in fileset(path.module, "*.tf") : regexall("(?m)^\\s*(module|data|ephemeral)\\s+\"", file("${path.module}/${f}"))])) == 0
    error_message = "이 모듈은 다른 모듈·data 소스·ephemeral 자원을 쓰지 않는다."
  }

  assert {
    condition     = length(fileset(path.module, "*.tf.json")) == 0
    error_message = "자원은 *.tf에만 선언한다. 지금 *.tf.json 파일: ${jsonencode(fileset(path.module, "*.tf.json"))}"
  }
}

# 브로커가 인터넷에 열리거나, 다중 인스턴스로 요금이 늘거나, 4.x가 돌 수 없는 인스턴스 종류가 되는 것을 막는다.
run "broker_is_private_single_instance_rabbitmq_4_on_m7g" {
  assert {
    condition = (
      aws_mq_broker.this.broker_name == "whycode-staging-rabbitmq"
      && aws_mq_broker.this.engine_type == "RabbitMQ"
      && aws_mq_broker.this.engine_version == "4.3"
      && aws_mq_broker.this.host_instance_type == "mq.m7g.medium"
      && aws_mq_broker.this.deployment_mode == "SINGLE_INSTANCE"
      && aws_mq_broker.this.publicly_accessible == false
      && aws_mq_broker.this.auto_minor_version_upgrade == true
    )
    error_message = "브로커는 비공개 단일 인스턴스 RabbitMQ 4.3(mq.m7g.medium, 이름 whycode-staging-rabbitmq, 마이너 자동 업그레이드)이어야 한다."
  }

  assert {
    condition = (
      toset(aws_mq_broker.this.subnet_ids) == toset([var.subnet_id])
      && toset(aws_mq_broker.this.security_groups) == toset([var.security_group_id])
    )
    error_message = "단일 인스턴스 브로커는 서브넷 하나와 입력받은 보안 그룹 하나만 써야 한다."
  }
}

# 관리자 사용자가 둘 이상 생기거나(RabbitMQ는 생성 때 관리자 하나만 받는다), 비밀번호가 안정된 값이 아니거나, 콘솔·그룹 인자(ActiveMQ 전용)가 섞이는 것을 막는다.
run "admin_user_uses_the_stable_alphanumeric_password" {
  assert {
    condition = (
      length(aws_mq_broker.this.user) == 1
      && one([for u in aws_mq_broker.this.user : u.username]) == "whycode_admin"
      && one([for u in aws_mq_broker.this.user : u.password]) == random_password.admin.result
      && one([for u in aws_mq_broker.this.user : try(length(u.groups), 0)]) == 0
      && one([for u in aws_mq_broker.this.user : try(u.console_access, false)]) != true
    )
    error_message = "관리자 사용자는 하나이고 사용자 이름이 whycode_admin, 비밀번호가 random_password.admin의 값이어야 한다(그룹·콘솔 접근 없음)."
  }

  assert {
    condition = (
      random_password.admin.length == 32
      && random_password.admin.special == false
      && random_password.admin.upper == true
      && random_password.admin.lower == true
      && random_password.admin.numeric == true
    )
    error_message = "비밀번호는 32자 영숫자여야 하고 upper·lower·numeric을 기본값에 기대지 않고 명시해야 한다(특수문자는 URL에 넣을 때 인코딩이 필요하고, Amazon MQ는 , : = 를 금지한다)."
  }
}

# 앱이 접속할 주소가 Prometheus용 https 엔드포인트로 잘못 잡히거나 포트를 못 읽는 것을 막는다.
run "outputs_pick_the_amqps_endpoint_and_return_ids" {
  assert {
    condition = (
      output.amqp_host == "b-12345678-90ab-cdef-1234-567890abcdef.mq.ap-northeast-2.amazonaws.com"
      && output.amqp_port == 5671
    )
    error_message = "접속 호스트·포트는 엔드포인트 목록에서 amqps://로 시작하는 것을 골라 읽어야 한다. 지금 값: ${jsonencode({ host = output.amqp_host, port = output.amqp_port })}"
  }

  assert {
    condition = (
      output.broker_id == aws_mq_broker.this.id
      && output.broker_arn == aws_mq_broker.this.arn
      && output.secret_arn == aws_secretsmanager_secret.mq.arn
    )
    error_message = "출력은 브로커 id·ARN과 비밀 ARN이어야 한다."
  }
}

# 비밀 값이 평문으로 남는 것과, 브로커가 새로 만들어졌는데 비밀의 url이 옛 주소로 남는 것을 막는다.
run "secret_is_write_only_and_follows_the_broker_identity" {
  assert {
    condition = (
      aws_secretsmanager_secret.mq.name == "whycode/staging/mq"
      && aws_secretsmanager_secret.mq.recovery_window_in_days == 0
      && aws_secretsmanager_secret_version.mq.secret_id == aws_secretsmanager_secret.mq.id
      && aws_secretsmanager_secret_version.mq.secret_string == null
    )
    error_message = "비밀 이름은 whycode/staging/mq, 삭제 유예 0일이고 값은 쓰기 전용(secret_string_wo)이어야 한다. 일반 secret_string은 상태에 평문으로 남는다."
  }

  assert {
    condition     = aws_secretsmanager_secret_version.mq.secret_string_wo_version == var.secret_version + parseint(substr(sha256(aws_mq_broker.this.id), 0, 7), 16)
    error_message = "비밀 값의 버전은 변수 secret_version + 브로커 id의 해시(앞 7자리 16진수)여야 한다. 그래야 브로커가 새로 만들어질 때 비밀의 url도 다시 쓰인다. 지금 값: ${aws_secretsmanager_secret_version.mq.secret_string_wo_version}"
  }
}

# 비밀의 url은 쓰기 전용이라 값을 읽을 수 없다. 접속 문자열에서 포트나 끝의 슬래시, 비밀번호가 빠지거나 amqp://로 바뀌어도 다른 검사는 모두
# 통과하고, 앱이 브로커에 접속하지 못할 때에야 드러난다. 그래서 소스에서 amqps://<사용자>:<비밀번호>@<호스트>:<포트>/ 의 모양을 확인한다.
# 사용자·비밀번호는 브로커에 만든 관리자와 같은 값(var.admin_username, random_password.admin.result)이어야 한다.
run "secret_url_is_a_complete_amqps_connection_string" {
  command = plan

  assert {
    condition = length(flatten([
      for f in fileset(path.module, "*.tf") : regexall("url\\s*=\\s*\"amqps://\\$\\{var\\.admin_username\\}:\\$\\{random_password\\.admin\\.result\\}@\\$\\{[^}]+\\}:\\$\\{[^}]+\\}/\"", file("${path.module}/${f}"))
    ])) == 1
    error_message = "비밀의 url은 amqps://$${var.admin_username}:$${random_password.admin.result}@<호스트>:<포트>/ 모양으로 조립해야 한다(포트와 끝의 슬래시, 관리자 사용자·비밀번호가 모두 있어야 한다)."
  }
}

# 비밀을 읽는 권한이 다른 서비스로 새거나 와일드카드로 넓어지는 것을 막는다. 브로커는 worker(발행)와 ai-engine(소비)만 쓴다. backend는 쓰지 않는다.
run "only_worker_and_ai_engine_exec_roles_can_read_the_secret" {
  assert {
    condition     = toset(keys(aws_iam_role_policy.read_secret)) == toset(["pipeline-worker", "ai-engine"])
    error_message = "비밀을 읽는 정책은 pipeline-worker와 ai-engine 두 서비스에만 있어야 한다. 지금 키: ${jsonencode(keys(aws_iam_role_policy.read_secret))}"
  }

  assert {
    condition = alltrue([
      for k, p in aws_iam_role_policy.read_secret :
      p.role == var.execution_role_names[k]
      && p.name == "read-mq-secret"
      && length(jsondecode(p.policy).Statement) == 1
      && jsondecode(p.policy).Statement[0].Effect == "Allow"
      && toset(flatten([jsondecode(p.policy).Statement[0].Action])) == toset(["secretsmanager:GetSecretValue"])
      && toset(flatten([jsondecode(p.policy).Statement[0].Resource])) == toset([aws_secretsmanager_secret.mq.arn])
    ])
    error_message = "각 정책은 자기 서비스 실행 역할에 붙어 이 비밀의 GetSecretValue 하나만 허용해야 한다(와일드카드·다른 비밀 없음). 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.read_secret : k => p.policy })}"
  }
}

# 값이 고정 문자열이 아니라 변수에서 나오는지 본다.
run "variables_flow_into_names_versions_and_sizes" {
  variables {
    environment        = "prod"
    engine_version     = "4.2"
    host_instance_type = "mq.m7g.large"
    admin_username     = "other-admin.1"
    secret_version     = 3
  }

  assert {
    condition = (
      aws_mq_broker.this.broker_name == "whycode-prod-rabbitmq"
      && aws_mq_broker.this.engine_version == "4.2"
      && aws_mq_broker.this.host_instance_type == "mq.m7g.large"
      && one([for u in aws_mq_broker.this.user : u.username]) == "other-admin.1"
      && aws_secretsmanager_secret.mq.name == "whycode/prod/mq"
      && aws_secretsmanager_secret_version.mq.secret_string_wo_version == 3 + parseint(substr(sha256(aws_mq_broker.this.id), 0, 7), 16)
    )
    error_message = "환경 이름·엔진 버전·인스턴스 종류·관리자 이름·비밀 버전이 변수에서 나와야 한다."
  }
}

# 이름·설명이 AWS 규칙에 맞는지 본다. 가짜 provider는 이 문자 규칙을 걸러 주지 못한다.
run "names_follow_aws_rules" {
  assert {
    condition     = can(regex("^[A-Za-z0-9_-]{1,50}$", aws_mq_broker.this.broker_name))
    error_message = "브로커 이름은 영문·숫자·하이픈·밑줄 50자 이하여야 한다."
  }

  assert {
    condition     = can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", aws_secretsmanager_secret.mq.description)) && length(aws_secretsmanager_secret.mq.description) <= 2048
    error_message = "비밀 설명은 영어 ASCII 한 줄이어야 한다."
  }
}

# ── 변수 검증(오류로 끝나는 run이라 맨 뒤) ────────────────────────────────────────────────

run "environment_rejects_unknown_names" {
  command = plan

  variables {
    environment = "dev"
  }

  expect_failures = [var.environment]
}

run "host_instance_type_rejects_types_that_cannot_run_rabbitmq_4" {
  command = plan

  variables {
    host_instance_type = "mq.t3.micro"
  }

  expect_failures = [var.host_instance_type]
}

run "host_instance_type_rejects_m5" {
  command = plan

  variables {
    host_instance_type = "mq.m5.large"
  }

  expect_failures = [var.host_instance_type]
}

run "engine_version_rejects_major_only" {
  command = plan

  variables {
    engine_version = "4"
  }

  expect_failures = [var.engine_version]
}

run "engine_version_rejects_patch_versions" {
  command = plan

  variables {
    engine_version = "4.3.1"
  }

  expect_failures = [var.engine_version]
}

run "admin_username_rejects_guest" {
  command = plan

  variables {
    admin_username = "guest"
  }

  expect_failures = [var.admin_username]
}

run "admin_username_rejects_one_character" {
  command = plan

  variables {
    admin_username = "a"
  }

  expect_failures = [var.admin_username]
}

run "admin_username_rejects_spaces_and_tildes" {
  command = plan

  variables {
    admin_username = "bad name~"
  }

  expect_failures = [var.admin_username]
}

run "secret_recovery_window_rejects_three_days" {
  command = plan

  variables {
    secret_recovery_window_days = 3
  }

  expect_failures = [var.secret_recovery_window_days]
}

run "secret_version_rejects_zero" {
  command = plan

  variables {
    secret_version = 0
  }

  expect_failures = [var.secret_version]
}
