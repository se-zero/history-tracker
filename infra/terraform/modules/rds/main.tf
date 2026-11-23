locals {
  # 이름 접두사를 한 곳에서 만들어, 환경을 바꾸면 모든 이름이 같이 바뀌게 한다(예: whycode-staging).
  name_prefix = "${var.project}-${var.environment}"

  # DB를 쓰는 서비스는 backend와 pipeline-worker뿐이다. execution_role_names에는 ai-engine도 들어 있어(platform 출력을 그대로 받는다)
  # 그 맵의 키로 돌리면 ai-engine까지 DB 비밀을 읽게 된다. 그래서 읽을 서비스를 이 고정 집합으로 못 박는다.
  db_clients = toset(["backend", "pipeline-worker"])
}

resource "aws_db_subnet_group" "this" {
  name        = "${local.name_prefix}-db"
  description = "Private subnets for the ${local.name_prefix} PostgreSQL instance"
  subnet_ids  = var.subnet_ids
}

# PostgreSQL 15 이상은 rds.force_ssl의 기본값이 이미 1이지만, 기본값은 AWS가 바꿀 수 있어 TLS 강제를 코드에 못 박는다.
resource "aws_db_parameter_group" "this" {
  name        = "${local.name_prefix}-postgres16"
  family      = "postgres16"
  description = "PostgreSQL 16 parameters for ${local.name_prefix}"

  parameter {
    name  = "rds.force_ssl"
    value = "1"

    # 정적 파라미터는 immediate를 거부당하지만 pending-reboot는 정적·동적 모두 받는다. 이 그룹은 인스턴스를 처음 만들 때 붙어
    # 재부팅이 따로 필요 없으니, 적용 방식 때문에 apply가 막힐 일만 없앤다.
    apply_method = "pending-reboot"
  }
}

# 마스터 비밀번호는 apply 중에만 만들어지고 상태·계획 파일에 남지 않는다(random_password 같은 일반 자원은 값이 상태에 평문으로 남는다).
# 값은 쓰기 전용 인자 둘(DB의 password_wo, 비밀의 secret_string_wo)에만 넘긴다. 일반 인자에 넣으면 Terraform이 오류로 막는다.
# 구두점을 뺀 것은 RDS가 마스터 비밀번호에 / " @ 를 허용하지 않아서, 그 규칙에 걸릴 일을 통째로 없애려는 것이다.
ephemeral "aws_secretsmanager_random_password" "master" {
  password_length     = 32
  exclude_punctuation = true
}

# 비밀번호는 쓰기 전용 인자로만 넣고 일반 password는 쓰지 않는다. RDS 관리형 마스터 비밀(manage_master_user_password)도 쓰지 않는다:
# 기본 7일마다 비밀번호가 바뀌어 이미 뜬 ECS 태스크의 연결이 끊기고, password_wo와는 함께 쓸 수도 없다.
# 쓰기 전용 인자는 password_wo_version이 바뀔 때만 다시 전송되므로, 비밀번호를 바꾸려면 master_password_version을 올린다.
# DB만 새로 만들어지는 apply나 apply가 중간에 실패해 DB와 비밀의 비밀번호가 어긋났을 수 있을 때도 올려서 다시 맞춘다.
resource "aws_db_instance" "this" {
  identifier = "${local.name_prefix}-postgres"

  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  # 저장소 종류는 기본값에 맡기지 않고 gp3로 못 박는다. 암호화는 기본이 꺼져 있고, 만든 뒤에는 켤 수 없다(스냅샷을 복원해 새로 만들어야 한다).
  allocated_storage = var.allocated_storage
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.master_username

  password_wo         = ephemeral.aws_secretsmanager_random_password.master.random_password
  password_wo_version = var.master_password_version

  db_subnet_group_name   = aws_db_subnet_group.this.name
  parameter_group_name   = aws_db_parameter_group.this.name
  vpc_security_group_ids = [var.security_group_id]

  # 인터넷에 열지 않고 단일 AZ로 둔다(1주 운영 기준). provider 기본값과 같아도 명시해, 기본값이 바뀌어도 노출·요금이 따라 바뀌지 않게 한다.
  publicly_accessible = false
  multi_az            = false

  backup_retention_period = var.backup_retention_period

  # engine_version에 메이저만("16") 적으면 마이너는 RDS가 고르는데, 이 값이 켜져 있어야 그 표기를 받는다.
  auto_minor_version_upgrade = true

  # 환경을 지운 뒤 남는 스냅샷이 어느 환경 것인지 태그로 알아볼 수 있어야 정리할 수 있다.
  copy_tags_to_snapshot = true

  deletion_protection = var.deletion_protection

  # 최종 스냅샷을 남기는 삭제는 스냅샷 이름이 있어야 AWS가 받는다. 생략하면 이름을 두지 않는다.
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.name_prefix}-postgres-final"
}

# 이름은 platform 모듈의 앱 비밀과 같은 규칙(<project>/<environment>/<키>)이다.
resource "aws_secretsmanager_secret" "master" {
  name        = "${var.project}/${var.environment}/rds"
  description = "RDS PostgreSQL master credentials (JSON object with username and password)"

  # 기본 0: 지우면 바로 사라진다. 7~30일을 두면 삭제 예약 상태의 비밀은 같은 이름으로 다시 만들 수 없어서,
  # 환경을 지웠다 다시 만들 때 "삭제 예약된 이름" 오류로 막힌다.
  recovery_window_in_days = var.secret_recovery_window_days

  # kms_key_id는 쓰지 않는다. 고객 관리형 키를 쓰면 실행 역할에 kms:Decrypt가 따로 있어야 하는데 정책에 없다.
}

# DB의 password_wo와 같은 ephemeral 값을 같은 버전 변수로 넣어, 비밀 속 비밀번호가 DB의 마스터 비밀번호와 한 번에 같이 바뀌게 한다.
# 일반 secret_string은 상태에 평문으로 남아 쓰지 않는다. 비밀번호를 바꾸는 절차는 aws_db_instance 주석과 같다(버전을 올린다).
resource "aws_secretsmanager_secret_version" "master" {
  secret_id = aws_secretsmanager_secret.master.id

  secret_string_wo = jsonencode({
    username = var.master_username
    password = ephemeral.aws_secretsmanager_random_password.master.random_password
  })
  secret_string_wo_version = var.master_password_version

  # DB가 만들어진 뒤에 쓴다. 동시에 만들면, DB 생성이 실패한 첫 apply 뒤에 다시 apply할 때 DB는 새 비밀번호를 받는데
  # 비밀은 버전이 그대로라 옛 값이 남아 둘이 어긋난다. 이렇게 두면 DB가 실패한 apply에서는 비밀도 만들어지지 않아서
  # 다시 apply할 때 둘이 같은 값으로 함께 만들어진다. DB만 교체되는 경우(-replace 등)에는 여전히 버전을 올려야 맞출 수 있다.
  depends_on = [aws_db_instance.this]
}

# ECS는 태스크를 띄울 때 실행 역할로 비밀을 읽어 환경변수로 넣어 준다(앱 코드가 쓰는 task role이 아니다).
# 읽기 권한은 이 비밀 하나의 GetSecretValue뿐이고 와일드카드는 없다. 정책은 aws_iam_policy_document 대신 jsonencode로 쓴다:
# 테스트의 가짜 provider 아래에서는 그 데이터 소스의 json이 무작위 문자열이라 정책 내용을 읽어 검사할 수 없다.
resource "aws_iam_role_policy" "read_secret" {
  for_each = local.db_clients

  role = var.execution_role_names[each.key]
  name = "read-rds-secret"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.master.arn]
      },
    ]
  })
}
