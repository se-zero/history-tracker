# T3 RDS 모듈(PostgreSQL 16 인스턴스·서브넷 그룹·파라미터 그룹·비밀 껍데기와 값·읽기 권한)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/rds test
#
# 검사의 중심은 세 가지다.
#   1. 데이터베이스가 인터넷에 열리지 않고(비공개·암호화·TLS 강제) 단일 AZ·백업 7일의 계약대로다.
#   2. 마스터 비밀번호가 Terraform 상태에 남지 않는다. password_wo(쓰기 전용)와 ephemeral 생성을 써서, 상태에는 password가 null이고
#      버전 번호만 남는다. RDS 관리형 비밀(manage_master_user_password)은 7일마다 비밀번호를 바꿔 이미 뜬 태스크의 연결을 끊으므로 쓰지 않는다.
#   3. 비밀을 읽을 수 있는 것은 backend·pipeline-worker의 실행 역할뿐이고, 자기 비밀 하나만 읽는다.
#
# 읽는 법
# - 쓰기 전용 인자(password_wo·secret_string_wo)는 상태에 값이 없어서 테스트에서도 값을 읽을 수 없다. 그래서 "값이 맞다"가 아니라
#   "일반 인자(password·secret_string)가 비어 있고 버전만 있다"를 본다. 비밀번호가 비밀 값과 같은 ephemeral에서 나온다는 것은 코드 읽기로 확인한다.
# - 가짜 provider는 계산 속성에만 값을 만들고(apply 단계), plan에서는 미정이다. 계산값에 기대는 run은 기본 command = apply다.
# - 테스트 파일에는 locals를 쓸 수 없다. 오류로 끝나는 변수 검증 run(expect_failures)은 맨 뒤에 둔다.
# - 모듈 소스에 선언된 자원·ephemeral 주소를 계약과 대조하는 첫 run만 정규식으로 소스를 읽는다.

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {}

variables {
  environment = "staging"
  subnet_ids  = ["subnet-0123456789abcdef0", "subnet-0fedcba9876543210"]

  security_group_id = "sg-0123456789abcdef0"

  execution_role_names = {
    backend           = "whycode-staging-backend-exec"
    "pipeline-worker" = "whycode-staging-pipeline-worker-exec"
    "ai-engine"       = "whycode-staging-ai-engine-exec"
  }
}

# 계약 밖 자원이 덧붙는 것을 막는다 — 아래 run들은 이름이 적힌 자원만 볼 수 있다. 특히 비밀번호를 상태에 남기는 random_password 자원이나
# 값이 평문으로 남는 aws_secretsmanager_secret_version 변형이 몰래 끼어들지 못하게 한다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_db_subnet_group.this",
      "aws_db_parameter_group.this",
      "aws_db_instance.this",
      "aws_secretsmanager_secret.master",
      "aws_secretsmanager_secret_version.master",
      "aws_iam_role_policy.read_secret",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 6개와 정확히 같아야 한다. 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
  }

  assert {
    condition = toset(["aws_secretsmanager_random_password.master"]) == toset(flatten([
      for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*ephemeral\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]
    ]))
    error_message = "ephemeral 자원은 aws_secretsmanager_random_password.master 하나여야 한다(비밀번호는 상태에 남지 않는 ephemeral로만 만든다)."
  }

  assert {
    condition     = length(flatten([for f in fileset(path.module, "*.tf") : regexall("(?m)^\\s*(module|data)\\s+\"", file("${path.module}/${f}"))])) == 0
    error_message = "이 모듈은 다른 모듈이나 data 소스를 쓰지 않는다."
  }

  assert {
    condition     = length(fileset(path.module, "*.tf.json")) == 0
    error_message = "자원은 *.tf에만 선언한다. 지금 *.tf.json 파일: ${jsonencode(fileset(path.module, "*.tf.json"))}"
  }
}

# 인스턴스가 인터넷에 열리거나, 암호화가 빠지거나, 다중 AZ로 요금이 늘어나는 것을 막는다.
run "instance_is_private_encrypted_single_az_postgres_16" {
  assert {
    condition = (
      aws_db_instance.this.engine == "postgres"
      && aws_db_instance.this.engine_version == "16"
      && aws_db_instance.this.instance_class == "db.t4g.small"
      && aws_db_instance.this.allocated_storage == 20
      && aws_db_instance.this.storage_type == "gp3"
      && aws_db_instance.this.storage_encrypted == true
      && aws_db_instance.this.publicly_accessible == false
      && aws_db_instance.this.multi_az == false
      && aws_db_instance.this.db_name == "history_tracker"
      && aws_db_instance.this.username == "history_tracker"
      && aws_db_instance.this.identifier == "whycode-staging-postgres"
    )
    error_message = "인스턴스는 비공개·암호화된 gp3 20GiB·단일 AZ PostgreSQL 16(db.t4g.small, 이름 whycode-staging-postgres)이어야 한다."
  }

  assert {
    condition = (
      aws_db_instance.this.backup_retention_period == 7
      && aws_db_instance.this.auto_minor_version_upgrade == true
      && aws_db_instance.this.copy_tags_to_snapshot == true
      && aws_db_instance.this.deletion_protection == false
    )
    error_message = "백업 7일, 마이너 버전 자동 업그레이드, 스냅샷에 태그 복사, 삭제 보호 꺼짐(환경을 지울 수 있어야 한다)이어야 한다."
  }

  assert {
    condition = (
      aws_db_instance.this.db_subnet_group_name == aws_db_subnet_group.this.name
      && aws_db_instance.this.parameter_group_name == aws_db_parameter_group.this.name
      && toset(aws_db_instance.this.vpc_security_group_ids) == toset([var.security_group_id])
    )
    error_message = "인스턴스는 이 모듈의 서브넷 그룹·파라미터 그룹과 입력받은 보안 그룹 하나만 써야 한다."
  }
}

# 마스터 비밀번호가 상태에 남는 것을 막는다(핵심). 일반 password가 비어 있고 관리형 비밀이 꺼져 있고 쓰기 전용 버전만 있어야 한다.
# 관리형 비밀은 7일마다 비밀번호를 바꿔 이미 뜬 태스크의 연결을 끊는다.
run "master_password_is_write_only_and_never_in_state" {
  assert {
    condition = (
      aws_db_instance.this.password == null
      && try(aws_db_instance.this.manage_master_user_password, false) != true
      && aws_db_instance.this.password_wo_version == 1
    )
    error_message = "마스터 비밀번호는 쓰기 전용(password_wo, 버전 1)이어야 하고 일반 password·관리형 비밀은 쓰지 않는다."
  }
}

# TLS가 아닌 연결이 허용되는 것을 막는다. PostgreSQL 15 이상의 기본값이 1이지만 기본값에 기대지 않고 명시한다.
run "parameter_group_forces_ssl" {
  assert {
    condition = (
      aws_db_parameter_group.this.family == "postgres16"
      && aws_db_parameter_group.this.name == "whycode-staging-postgres16"
      && length(aws_db_parameter_group.this.parameter) == 1
      && one([for p in aws_db_parameter_group.this.parameter : p.name]) == "rds.force_ssl"
      && one([for p in aws_db_parameter_group.this.parameter : p.value]) == "1"
    )
    error_message = "파라미터 그룹은 postgres16 계열이고 rds.force_ssl = 1 하나여야 한다. 지금 값: ${jsonencode(aws_db_parameter_group.this.parameter)}"
  }

  assert {
    condition     = toset(aws_db_subnet_group.this.subnet_ids) == toset(var.subnet_ids) && aws_db_subnet_group.this.name == "whycode-staging-db"
    error_message = "서브넷 그룹은 입력받은 비공개 서브넷 둘이어야 한다."
  }
}

# 비밀 값이 코드나 상태에 평문으로 남는 것을 막는다. 값은 쓰기 전용으로만 넣고, 비밀 껍데기와 값의 버전이 인스턴스와 같이 움직인다.
run "secret_holds_the_password_write_only_with_the_same_version" {
  assert {
    condition = (
      aws_secretsmanager_secret.master.name == "whycode/staging/rds"
      && aws_secretsmanager_secret.master.recovery_window_in_days == 0
      && aws_secretsmanager_secret_version.master.secret_id == aws_secretsmanager_secret.master.id
    )
    error_message = "비밀 이름은 whycode/staging/rds, 삭제 유예 0일(지웠다 같은 이름으로 다시 만들 수 있게)이고 값은 이 비밀에 들어가야 한다."
  }

  assert {
    condition = (
      aws_secretsmanager_secret_version.master.secret_string == null
      && aws_secretsmanager_secret_version.master.secret_string_wo_version == 1
      && aws_secretsmanager_secret_version.master.secret_string_wo_version == aws_db_instance.this.password_wo_version
    )
    error_message = "비밀 값은 쓰기 전용(secret_string_wo)이어야 하고 버전이 인스턴스 비밀번호 버전과 같아야 한다. 일반 secret_string은 상태에 평문으로 남는다."
  }
}

# 쓰기 전용 값은 읽을 수 없어서, DB의 마스터 비밀번호와 비밀에 들어가는 비밀번호가 같은 값에서 나오는지는 속성으로 볼 수 없다.
# 둘이 어긋나면 앱이 DB에 접속하지 못하는데 다른 검사는 모두 통과한다. 그래서 소스에서 두 자리가 같은 ephemeral 값을 가리키는지 확인한다.
# 또 비밀은 DB가 만들어진 뒤에 써야 한다. 동시에 만들면, DB 생성이 실패한 첫 apply 뒤에 다시 apply할 때 DB는 새 비밀번호를 받는데
# 비밀은 버전이 그대로라 옛 값이 남아 둘이 어긋난다(그때는 master_password_version을 올려야 맞출 수 있다).
run "db_and_secret_share_one_ephemeral_password_and_the_secret_waits_for_the_db" {
  command = plan

  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_db_instance\"\\s+\"this\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("password_wo\\s*=\\s*ephemeral\\.aws_secretsmanager_random_password\\.master\\.random_password", b))
    ]) == 1
    error_message = "aws_db_instance.this의 password_wo는 ephemeral.aws_secretsmanager_random_password.master.random_password여야 한다."
  }

  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_secretsmanager_secret_version\"\\s+\"master\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("secret_string_wo\\s*=\\s*jsonencode\\(\\{[^}]*password\\s*=\\s*ephemeral\\.aws_secretsmanager_random_password\\.master\\.random_password", b))
    ]) == 1
    error_message = "비밀 값(secret_string_wo)의 password는 DB의 password_wo와 같은 ephemeral.aws_secretsmanager_random_password.master.random_password여야 한다."
  }

  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_secretsmanager_secret_version\"\\s+\"master\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("depends_on\\s*=\\s*\\[[^\\]]*aws_db_instance\\.this", b))
    ]) == 1
    error_message = "aws_secretsmanager_secret_version.master는 depends_on에 aws_db_instance.this를 걸어 DB가 만들어진 뒤에 써야 한다."
  }
}

# 비밀을 읽는 권한이 다른 서비스로 새거나 와일드카드로 넓어지는 것을 막는다.
# DB는 backend와 pipeline-worker만 쓴다(ai-engine은 PostgreSQL을 쓰지 않는다).
run "only_backend_and_worker_exec_roles_can_read_the_secret" {
  assert {
    condition     = toset(keys(aws_iam_role_policy.read_secret)) == toset(["backend", "pipeline-worker"])
    error_message = "비밀을 읽는 정책은 backend와 pipeline-worker 두 서비스에만 있어야 한다. 지금 키: ${jsonencode(keys(aws_iam_role_policy.read_secret))}"
  }

  assert {
    condition = alltrue([
      for k, p in aws_iam_role_policy.read_secret :
      p.role == var.execution_role_names[k]
      && p.name == "read-rds-secret"
      && length(jsondecode(p.policy).Statement) == 1
      && jsondecode(p.policy).Statement[0].Effect == "Allow"
      && toset(flatten([jsondecode(p.policy).Statement[0].Action])) == toset(["secretsmanager:GetSecretValue"])
      && toset(flatten([jsondecode(p.policy).Statement[0].Resource])) == toset([aws_secretsmanager_secret.master.arn])
    ])
    error_message = "각 정책은 자기 서비스 실행 역할에 붙어 이 비밀의 GetSecretValue 하나만 허용해야 한다(와일드카드·다른 비밀 없음). 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.read_secret : k => p.policy })}"
  }
}

run "outputs_return_connection_values_and_the_secret" {
  assert {
    condition = (
      output.address == aws_db_instance.this.address
      && output.port == aws_db_instance.this.port
      && output.db_name == "history_tracker"
      && output.master_username == "history_tracker"
      && output.secret_arn == aws_secretsmanager_secret.master.arn
      && output.identifier == aws_db_instance.this.identifier
    )
    error_message = "출력은 접속 주소·포트·DB 이름·사용자 이름·비밀 ARN·식별자와 같아야 한다."
  }
}

# 값이 고정 문자열이 아니라 변수에서 나오는지 본다. 운영 값(최종 스냅샷 유지·비밀번호 버전 올림)과 스테이징 값(스냅샷 생략)이 둘 다 흐른다.
run "variables_flow_into_names_snapshots_and_versions" {
  variables {
    environment             = "prod"
    skip_final_snapshot     = true
    master_password_version = 2
    allocated_storage       = 50
    backup_retention_period = 14
    deletion_protection     = true
    instance_class          = "db.t4g.medium"
    engine_version          = "16.4"
    db_name                 = "other_db"
    master_username         = "other_user"
  }

  assert {
    condition = (
      aws_db_instance.this.identifier == "whycode-prod-postgres"
      && aws_secretsmanager_secret.master.name == "whycode/prod/rds"
      && aws_db_instance.this.skip_final_snapshot == true
      && aws_db_instance.this.password_wo_version == 2
      && aws_secretsmanager_secret_version.master.secret_string_wo_version == 2
      && aws_db_instance.this.allocated_storage == 50
      && aws_db_instance.this.backup_retention_period == 14
      && aws_db_instance.this.deletion_protection == true
      && aws_db_instance.this.instance_class == "db.t4g.medium"
      && aws_db_instance.this.engine_version == "16.4"
      && aws_db_instance.this.db_name == "other_db"
      && aws_db_instance.this.username == "other_user"
    )
    error_message = "환경 이름·스냅샷 생략·비밀번호 버전·용량·백업·삭제 보호·인스턴스 종류·엔진 버전·DB 이름·사용자 이름이 변수에서 나와야 한다."
  }
}

# 최종 스냅샷을 남기는 환경(운영)에서는 스냅샷 이름이 있어야 한다. 이름 없이 삭제하면 AWS가 삭제를 거부한다.
run "final_snapshot_is_named_unless_skipped" {
  variables {
    skip_final_snapshot = false
  }

  assert {
    condition = (
      aws_db_instance.this.skip_final_snapshot == false
      && aws_db_instance.this.final_snapshot_identifier == "whycode-staging-postgres-final"
    )
    error_message = "최종 스냅샷을 남기면 final_snapshot_identifier가 whycode-staging-postgres-final이어야 한다. 지금 값: ${jsonencode(aws_db_instance.this.final_snapshot_identifier)}"
  }
}

run "final_snapshot_name_is_absent_when_skipped" {
  variables {
    skip_final_snapshot = true
  }

  assert {
    condition     = aws_db_instance.this.skip_final_snapshot == true && aws_db_instance.this.final_snapshot_identifier == null
    error_message = "스냅샷을 생략하면 final_snapshot_identifier를 두지 않는다. 지금 값: ${jsonencode(aws_db_instance.this.final_snapshot_identifier)}"
  }
}

# 이름이 AWS 규칙(소문자 영문·숫자·하이픈, 63자 이하, 영문으로 시작, 하이픈으로 끝나지 않음)에 맞는지 본다. 가짜 provider는 이 문자 규칙을 걸러 주지 못한다.
run "names_follow_aws_rules" {
  assert {
    condition = alltrue([
      for n in [aws_db_instance.this.identifier, aws_db_subnet_group.this.name, aws_db_parameter_group.this.name] :
      can(regex("^[a-z][a-z0-9-]{0,61}[a-z0-9]$", n)) && !strcontains(n, "--")
    ])
    error_message = "DB 식별자·서브넷 그룹·파라미터 그룹 이름은 소문자 영문으로 시작하고 영문·숫자·하이픈만 쓰며 하이픈으로 끝나거나 연달아 쓰지 않는다."
  }

  assert {
    condition = alltrue([
      for d in [aws_db_subnet_group.this.description, aws_db_parameter_group.this.description] :
      can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", d))
    ])
    error_message = "서브넷 그룹·파라미터 그룹 설명은 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$*만 쓴다(RDS API가 그 밖의 문자를 거부한다)."
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

run "subnet_ids_reject_one_subnet" {
  command = plan

  variables {
    subnet_ids = ["subnet-0123456789abcdef0"]
  }

  expect_failures = [var.subnet_ids]
}

run "subnet_ids_reject_three_subnets" {
  command = plan

  variables {
    subnet_ids = ["subnet-0123456789abcdef0", "subnet-0fedcba9876543210", "subnet-00000000000000000"]
  }

  expect_failures = [var.subnet_ids]
}

run "allocated_storage_rejects_below_the_gp3_minimum" {
  command = plan

  variables {
    allocated_storage = 19
  }

  expect_failures = [var.allocated_storage]
}

run "backup_retention_rejects_36_days" {
  command = plan

  variables {
    backup_retention_period = 36
  }

  expect_failures = [var.backup_retention_period]
}

run "secret_recovery_window_rejects_three_days" {
  command = plan

  variables {
    secret_recovery_window_days = 3
  }

  expect_failures = [var.secret_recovery_window_days]
}

run "master_password_version_rejects_zero" {
  command = plan

  variables {
    master_password_version = 0
  }

  expect_failures = [var.master_password_version]
}
