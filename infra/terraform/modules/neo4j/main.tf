# 백업 버킷 이름에 쓸 계정 번호. 저장소가 공개라 계정 번호를 코드에 적지 않고 읽어 온다.
data "aws_caller_identity" "current" {}

# 데이터 볼륨의 AZ는 인스턴스가 아니라 서브넷에서 읽는다.
# 이 모듈에서 인스턴스는 user-data가 바뀔 때마다 교체되는데(user_data_replace_on_change), 교체 계획에서는 인스턴스의
# availability_zone이 "apply 뒤에 정해짐"이 된다. AZ는 바꾸면 볼륨을 새로 만드는 인자라서 그 값을 참조하면 Terraform이
# 데이터 볼륨까지 교체하려 한다 — 그래프 데이터가 든 볼륨이 지워진다. 서브넷의 AZ는 인스턴스와 무관하게 고정이라 이 연결을 끊는다.
data "aws_subnet" "this" {
  id = var.subnet_id
}

# 최신 Amazon Linux 2023 표준 이미지(AWS CLI·SSM 에이전트가 들어 있다). AMI id는 비밀이 아니라서
# sensitive로 표시되는 value 대신 insecure_value를 쓴다. 안 그러면 plan에서 ami 값이 가려진다.
data "aws_ssm_parameter" "ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  # 이름 접두사를 한 곳에서 만들어, 환경을 바꾸면 모든 이름이 같이 바뀌게 한다(예: whycode-staging).
  name_prefix = "${var.project}-${var.environment}"

  # 이 비밀을 읽는 서비스. Neo4j에 붙는 것은 ai-engine뿐이라 다른 서비스의 실행 역할에는 읽기 권한을 주지 않는다.
  # 키가 서비스 이름이라 var.execution_role_names에서 그 서비스의 역할 이름을 찾는다.
  secret_readers = toset(["ai-engine"])
}

# 비밀번호는 ephemeral 자원으로 만든다. ephemeral 값은 상태 파일과 plan 파일에 기록되지 않는다.
# 구두점을 빼고 영숫자만 쓴다. 비밀번호가 셸·환경변수·URI 어느 경로를 거쳐도 인용이나 이스케이프가 필요 없게 하려는 것이고,
# 길이 32의 영숫자면 추측할 수 없는 값으로 충분하다.
ephemeral "aws_secretsmanager_random_password" "neo4j" {
  password_length     = 32
  exclude_punctuation = true
}

resource "aws_secretsmanager_secret" "neo4j" {
  name        = "${var.project}/${var.environment}/neo4j"
  description = "Neo4j credentials for the knowledge graph database (JSON object with username and password)"

  # 기본 0: 지우면 바로 사라진다. 7~30일을 두면 삭제 예약 상태의 비밀은 같은 이름으로 다시 만들 수 없어서,
  # 환경을 지웠다 다시 만들 때 "삭제 예약된 이름" 오류로 막힌다.
  recovery_window_in_days = var.secret_recovery_window_days

  # kms_key_id·replica는 쓰지 않는다. 고객 관리형 키를 쓰면 인스턴스 역할과 실행 역할에 kms:Decrypt가 있어야 하는데
  # 정책에 없어서 비밀을 읽지 못하고, 복제는 다른 리전에 사본(과 요금)을 더 만든다.
}

# 값은 쓰기 전용(secret_string_wo)으로만 넣는다. 일반 secret_string은 상태 파일에 평문으로 남는다.
# 쓰기 전용 인자는 값을 비교할 수 없어서 변경 여부를 secret_string_wo_version으로 알린다.
# 이 값을 올리지 않으면 이후 apply가 비밀을 다시 쓰지 않는다. 그래서 plan마다 새 무작위 비밀번호가 만들어져도 비밀은 그대로다.
resource "aws_secretsmanager_secret_version" "neo4j" {
  secret_id = aws_secretsmanager_secret.neo4j.id

  secret_string_wo = jsonencode({
    username = "neo4j"
    password = ephemeral.aws_secretsmanager_random_password.neo4j.random_password
  })
  secret_string_wo_version = var.password_version
}

# Neo4j를 Docker로 띄우는 호스트 한 대. 공인 IP도 SSH 키도 없고 접속은 SSM으로만 한다.
#
# user-data가 바뀌면 인스턴스를 교체한다. 부팅 스크립트는 첫 부팅 때 한 번만 돌아서, 교체하지 않으면 바뀐 설정(이미지·메모리)이
# 반영되지 않는다. 데이터는 따로 있는 볼륨에 있어 교체해도 남는다. 스크립트의 주석 한 줄만 고쳐도 user-data가 달라지므로,
# user_data.sh.tftpl을 고친 apply는 Neo4j를 재기동하고 사설 IP를 바꿀 수 있다.
resource "aws_instance" "neo4j" {
  ami                         = data.aws_ssm_parameter.ami.insecure_value
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [var.security_group_id]
  iam_instance_profile        = aws_iam_instance_profile.neo4j.name
  associate_public_ip_address = false

  # 템플릿에는 비밀번호가 아니라 비밀의 ARN만 넘긴다. 스크립트가 부팅할 때 인스턴스 역할로 값을 읽어 온다.
  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    secret_arn     = aws_secretsmanager_secret.neo4j.arn
    neo4j_image    = var.neo4j_image
    heap_size      = var.heap_size
    pagecache_size = var.pagecache_size
  })
  user_data_replace_on_change = true

  # IMDSv2 필수에 홉 제한 1. 컨테이너에서 나가는 요청은 홉이 하나 더 늘어서 메타데이터에 닿지 못하므로,
  # 컨테이너가 뚫려도 인스턴스 역할의 자격 증명을 가져가지 못한다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  # 루트 디스크에는 OS와 Docker 이미지만 있고 그래프 데이터는 없다. 그래서 인스턴스와 함께 지운다.
  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gb
    encrypted             = true
    delete_on_termination = true
  }

  tags = {
    Name = "${local.name_prefix}-neo4j"
  }

  # 부팅 스크립트는 첫 부팅 때 비밀의 값을 읽는다. 인스턴스는 비밀 자체(ARN)에만 의존하고 값에는 의존하지 않아서,
  # 적지 않으면 값이 들어가기 전에 인스턴스가 뜰 수 있다. 읽기 권한(역할 정책)과 SSM 접속 권한도 먼저 붙어 있어야 한다.
  depends_on = [
    aws_secretsmanager_secret_version.neo4j,
    aws_iam_role_policy.neo4j,
    aws_iam_role_policy_attachment.ssm,
  ]

  # ami는 data 소스가 읽은 "그 시점의 최신 이미지"라서 AWS가 AL2023 이미지를 새로 낼 때마다 값이 달라진다.
  # 그때마다 인스턴스가 교체되면 Neo4j가 재기동되고 사설 IP가 바뀌어 ai-engine이 끊긴다.
  # 이미지를 일부러 바꾸려면 terraform apply -replace로 인스턴스를 직접 교체한다.
  lifecycle {
    ignore_changes = [ami]
  }
}

# 그래프 데이터는 인스턴스와 따로 있는 암호화 볼륨에 둔다. 인스턴스가 교체돼도 이 볼륨은 남아 새 인스턴스에 다시 붙는다.
# AZ를 서브넷에서 읽는 이유는 위 data "aws_subnet" 주석에 있다.
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.this.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = {
    Name = "${local.name_prefix}-neo4j-data"
  }
}

# /dev/sdf로 붙이면 Nitro 인스턴스에서는 NVMe 장치(/dev/nvme1n1)로 보인다. 부팅 스크립트가 그 이름이 나타나길 기다린다.
# 인스턴스를 교체할 때 볼륨을 떼기 전에 인스턴스를 먼저 멈춘다. 켜진 채로 떼면 Neo4j가 체크포인트를 마치지 못해
# 파일 시스템과 데이터베이스가 손상될 수 있다.
resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.neo4j.id

  stop_instance_before_detaching = true
}
