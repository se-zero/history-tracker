# RabbitMQ 브로커 하나와, 앱이 접속할 정보를 담는 비밀, 그 비밀을 읽는 서비스의 권한을 만든다.
# 앱은 비밀의 url 하나로 접속하므로, 브로커가 새로 만들어져도 비밀이 옛 주소로 남지 않게 하는 것이 이 모듈의 핵심이다.

locals {
  # 브로커를 쓰는 서비스는 pipeline-worker(발행)와 ai-engine(소비)뿐이다. backend는 큐를 쓰지 않아 비밀을 읽을 이유가 없다.
  secret_readers = toset(["pipeline-worker", "ai-engine"])

  # RabbitMQ 4.2 이상은 instances[0].endpoints에 amqps://…:5671과 Prometheus용 https://…:16001이 함께 오고 순서가 보장되지 않는다.
  # 첫 원소를 쓰면 https 주소가 접속 주소로 잡힐 수 있어 스킴으로 고른다. 단일 인스턴스라 amqps 주소는 정확히 하나이고, 아니면 오류로 멈춘다.
  amqps_endpoint = one([for e in aws_mq_broker.this.instances[0].endpoints : e if startswith(e, "amqps://")])
  amqps_parts    = regex("^amqps://([^:/]+):([0-9]+)", local.amqps_endpoint)
  amqp_host      = local.amqps_parts[0]
  amqp_port      = tonumber(local.amqps_parts[1])
}

# 관리자 비밀번호. "비밀은 상태에 넣지 않는다"는 원칙의 유일한 예외다.
# aws_mq_broker의 user.password에는 쓰기 전용 인자가 없어서 어떤 값을 넣어도 상태 파일에 평문으로 남는다
# (provider 문서: stored in the raw state as plain-text). ephemeral 값은 일반 인자에 넣을 수 없어 피해 갈 길도 없다.
# 그래서 최선은 값을 안정시키는 것이다. random_password는 입력이 같으면 다시 만들어지지 않는다.
# user 블록이 바뀌면 브로커가 새로 만들어져 큐의 메시지가 사라지므로 비밀번호가 흔들리면 안 된다.
# 상태 파일을 읽을 수 있는 사람은 이 비밀번호도 읽을 수 있다.
resource "random_password" "admin" {
  # 영숫자만 쓴다. 비밀번호를 url에 그대로 넣는데 @ : / 같은 문자가 섞이면 접속 문자열이 깨지고,
  # Amazon MQ는 , : = 도 금지한다. 32자 영숫자는 AWS의 길이(12~250자)·고유 문자 4개 이상 조건을 넉넉히 넘는다.
  length  = 32
  special = false

  # 세 값 모두 provider 기본값이 true지만 계산 속성이라 적지 않으면 값이 provider 쪽에 달려 있다.
  # 영숫자 조합이 코드에서 읽히도록 명시한다.
  upper   = true
  lower   = true
  numeric = true
}

resource "aws_mq_broker" "this" {
  broker_name = "${var.project}-${var.environment}-rabbitmq"

  engine_type    = "RabbitMQ"
  engine_version = var.engine_version

  # 4.2 이상은 mq.m7g에서만 돈다(변수 검증이 막는다).
  host_instance_type = var.host_instance_type

  # AWS 이전 계획의 기준안이 단일 인스턴스(Single-AZ)다. 다중 AZ 클러스터(CLUSTER_MULTI_AZ)는 인스턴스가 셋이라
  # 요금이 몇 배가 되고, 고가용성을 들일지는 1주 실측 뒤에 정한다. 단일 인스턴스는 서브넷 하나만 받는다.
  deployment_mode = "SINGLE_INSTANCE"
  subnet_ids      = [var.subnet_id]
  security_groups = [var.security_group_id]

  # 비공개 서브넷 안에서만 닿는다. 인터넷에서 브로커로 들어오는 경로를 만들지 않는다.
  publicly_accessible = false

  # RabbitMQ 3.13 이상은 true여야 한다. 패치 버전은 AWS가 자동으로 올린다.
  auto_minor_version_upgrade = true

  # RabbitMQ는 생성 때 관리자 사용자를 하나만 받는다. console_access·groups는 ActiveMQ 전용 인자라 쓰지 않는다.
  # user 블록이 바뀌면 브로커가 새로 만들어진다 — 아래 비밀 버전이 브로커 id를 따르는 이유다.
  user {
    username = var.admin_username
    password = random_password.admin.result
  }
}

# platform 모듈의 비밀은 값을 사람이 CLI로 넣지만, 이 비밀에는 브로커가 만들어져야 아는 주소와 Terraform이 만든
# 비밀번호가 들어 있다. 사람이 옮겨 적게 하면 브로커가 새로 만들어질 때마다 어긋나므로, 값도 Terraform이 넣되
# 아래처럼 쓰기 전용으로만 넣는다.
resource "aws_secretsmanager_secret" "mq" {
  name        = "${var.project}/${var.environment}/mq"
  description = "RabbitMQ admin credentials and AMQPS connection URL for pipeline-worker and ai-engine (JSON object: username, password, url)"

  # 기본 0: platform 모듈의 비밀과 같은 이유다. 삭제 예약 중인 이름은 다시 만들 수 없어서
  # 환경을 지웠다 다시 만들 때 "삭제 예약된 이름" 오류로 막힌다.
  recovery_window_in_days = var.secret_recovery_window_days

  # kms_key_id는 쓰지 않는다. 고객 관리형 키를 쓰면 실행 역할에 kms:Decrypt가 있어야 하는데
  # 읽기 정책에는 GetSecretValue뿐이라 컨테이너가 비밀을 읽지 못한다.
}

resource "aws_secretsmanager_secret_version" "mq" {
  secret_id = aws_secretsmanager_secret.mq.id

  # 값은 쓰기 전용(secret_string_wo)으로만 넣는다. 일반 secret_string은 비밀번호가 든 JSON이 상태 파일에 평문으로 남는다
  # (비밀번호 자체는 위 random_password·브로커 user 블록의 예외로 상태에 있다).
  # url은 앱이 RABBITMQ_URL 하나만 받으면 되도록 완성된 접속 문자열로 둔다. 비밀번호가 영숫자뿐이라 URL 인코딩 없이 그대로 넣는다.
  secret_string_wo = jsonencode({
    username = var.admin_username
    password = random_password.admin.result
    url      = "amqps://${var.admin_username}:${random_password.admin.result}@${local.amqp_host}:${local.amqp_port}/"
  })

  # 쓰기 전용 인자는 값을 읽어 비교할 수 없어서 버전이 바뀔 때만 다시 전송된다. 그런데 브로커가 새로 만들어지면
  # url의 호스트가 바뀐다. 버전이 그대로면 비밀에 옛 호스트가 남아 앱이 사라진 브로커로 접속한다.
  # 그래서 브로커 id를 버전에 섞어, 브로커가 새로 만들어질 때 비밀도 자동으로 다시 쓰이게 한다.
  # secret_version은 브로커와 무관하게 값을 다시 쓰고 싶을 때 사람이 올린다.
  # id 해시는 앞 7자리(16진수, 최대 약 2.7억)만 써서, 사람이 올리는 작은 secret_version을 더해도 32비트 정수 안에 들어간다.
  secret_string_wo_version = var.secret_version + parseint(substr(sha256(aws_mq_broker.this.id), 0, 7), 16)
}

# 권한은 이 비밀 하나의 GetSecretValue뿐이고, 와일드카드나 다른 비밀로 넓히지 않는다. platform 모듈이 만든 실행 역할에
# 이 비밀의 ARN을 아는 이 모듈이 정책을 덧붙인다(platform의 base 정책과 이름이 달라 서로 덮어쓰지 않는다).
# 정책은 aws_iam_policy_document 대신 jsonencode로 쓴다. 가짜 provider 아래에서는 그 데이터 소스의 json이
# 무작위 문자열이 돼 테스트가 정책 내용을 읽을 수 없다.
resource "aws_iam_role_policy" "read_secret" {
  for_each = local.secret_readers

  role = var.execution_role_names[each.key]
  name = "read-mq-secret"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.mq.arn]
      },
    ]
  })
}
