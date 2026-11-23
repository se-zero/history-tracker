# T3 Neo4j 모듈(EC2 1·암호화 데이터 볼륨·인스턴스 역할·비밀·백업 버킷·읽기 권한)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/neo4j test
#
# 검사의 중심은 네 가지다.
#   1. 인스턴스가 인터넷에 열리지 않는다(공인 IP 없음, IMDSv2 필수, 접속은 SSM으로만). 보안 그룹은 network 모듈이 정한다.
#   2. 그래프 데이터는 인스턴스와 따로 있는 암호화 볼륨에 있다. 인스턴스가 교체돼도(user-data가 바뀌면 교체한다) 데이터가 남는다.
#   3. Neo4j 비밀번호가 상태에도 user-data에도 남지 않는다. 비밀번호는 ephemeral로 만들어 쓰기 전용으로 Secrets Manager에 넣고,
#      인스턴스가 부팅할 때 자기 역할 권한으로 읽어 온다. 그래서 user-data에는 비밀 ARN만 있고 비밀번호는 없다.
#   4. 백업 버킷은 비공개이고 암호화·버전 관리가 켜져 있으며, 인스턴스 역할은 백업을 올리고 읽을 수만 있다(지우지 못한다).
#
# 읽는 법
# - user-data는 가짜 provider로 실행할 수 없다. 여기서는 렌더된 스크립트에 핵심 줄이 있는지만 본다. 문법은 `bash -n`, 동작은 apply 뒤 SSM 접속으로 확인한다.
# - 쓰기 전용 인자(secret_string_wo)는 값을 읽을 수 없어 "일반 secret_string이 비어 있고 버전만 있다"를 본다.
# - 가짜 provider는 계산 속성에만 값을 만들고(apply 단계), 테스트 파일에는 locals를 쓸 수 없다. 오류로 끝나는 변수 검증 run은 맨 뒤에 둔다.
# - 정책은 jsonencode로 쓴다(aws_iam_policy_document의 json은 가짜 provider 아래에서 무작위 문자열이 된다). 문은 Action·Resource를 정렬해 내용으로 비교한다.

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_ssm_parameter" {
    defaults = {
      insecure_value = "ami-0123456789abcdef0"
    }
  }

  # 데이터 볼륨의 AZ는 인스턴스가 아니라 서브넷에서 읽는다(아래 data_volume run 참고).
  mock_data "aws_subnet" {
    defaults = {
      availability_zone = "ap-northeast-2a"
    }
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

# 계약 밖 자원이 덧붙는 것을 막는다 — 아래 run들은 이름이 적힌 자원만 볼 수 있다. 인터넷에 열린 두 번째 인스턴스나 공개 버킷 정책, 비밀번호를 상태에 남기는
# random_password가 몰래 끼어들지 못하게 한다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_secretsmanager_secret.neo4j",
      "aws_secretsmanager_secret_version.neo4j",
      "aws_s3_bucket.backup",
      "aws_s3_bucket_public_access_block.backup",
      "aws_s3_bucket_ownership_controls.backup",
      "aws_s3_bucket_versioning.backup",
      "aws_s3_bucket_server_side_encryption_configuration.backup",
      "aws_s3_bucket_lifecycle_configuration.backup",
      "aws_s3_bucket_policy.backup",
      "aws_iam_role.neo4j",
      "aws_iam_role_policy_attachment.ssm",
      "aws_iam_role_policy.neo4j",
      "aws_iam_instance_profile.neo4j",
      "aws_instance.neo4j",
      "aws_ebs_volume.data",
      "aws_volume_attachment.data",
      "aws_iam_role_policy.read_secret",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 17개와 정확히 같아야 한다. 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
  }

  assert {
    condition = toset(["aws_caller_identity.current", "aws_ssm_parameter.ami", "aws_subnet.this"]) == toset(flatten([
      for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*data\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]
    ]))
    error_message = "data 소스는 aws_caller_identity.current(버킷 이름), aws_ssm_parameter.ami(AMI), aws_subnet.this(데이터 볼륨의 AZ) 셋뿐이어야 한다."
  }

  # 인스턴스가 자기 비밀을 읽기 전에 비밀의 값이 먼저 들어 있어야 하고, AMI가 새로 나와도 인스턴스가 교체되면 안 된다.
  # 둘 다 속성으로는 볼 수 없는 설정이라 소스에서 확인한다. 'ignore_changes = [ami]'가 없으면 AWS가 AL2023 이미지를 새로 낼 때마다
  # 다음 apply가 인스턴스를 교체해(Neo4j 재기동·사설 IP 변경) ai-engine이 끊긴다.
  # 인스턴스 블록만 잘라(terraform fmt 형식에서 자원의 닫는 괄호는 줄 맨 앞에 있다) 그 안에서 찾는다.
  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_instance\"\\s+\"neo4j\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("depends_on\\s*=\\s*\\[[^\\]]*aws_secretsmanager_secret_version\\.neo4j", b))
    ]) == 1
    error_message = "aws_instance.neo4j는 depends_on에 aws_secretsmanager_secret_version.neo4j를 걸어야 한다(부팅 스크립트가 비밀 값을 읽는다)."
  }

  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_instance\"\\s+\"neo4j\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("ignore_changes\\s*=\\s*\\[\\s*ami\\s*\\]", b))
    ]) == 1
    error_message = "aws_instance.neo4j는 lifecycle { ignore_changes = [ami] }로 AMI 갱신이 인스턴스를 교체하지 않게 해야 한다."
  }

  assert {
    condition = toset(["aws_secretsmanager_random_password.neo4j"]) == toset(flatten([
      for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*ephemeral\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]
    ]))
    error_message = "ephemeral 자원은 aws_secretsmanager_random_password.neo4j 하나여야 한다(비밀번호는 상태에 남지 않는 ephemeral로만 만든다)."
  }

  assert {
    condition     = length(flatten([for f in fileset(path.module, "*.tf") : regexall("(?m)^\\s*module\\s+\"", file("${path.module}/${f}"))])) == 0
    error_message = "이 모듈은 다른 모듈을 부르면 안 된다."
  }

  assert {
    condition     = length(fileset(path.module, "*.tf.json")) == 0
    error_message = "자원은 *.tf에만 선언한다. 지금 *.tf.json 파일: ${jsonencode(fileset(path.module, "*.tf.json"))}"
  }
}

# 인스턴스가 인터넷에 열리거나, IMDSv1이 남거나, 루트 디스크가 암호화되지 않는 것을 막는다.
run "instance_is_private_imdsv2_and_encrypted" {
  assert {
    condition = (
      aws_instance.neo4j.instance_type == "t3.large"
      && aws_instance.neo4j.ami == "ami-0123456789abcdef0"
      && data.aws_ssm_parameter.ami.name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
      && aws_instance.neo4j.associate_public_ip_address == false
      && aws_instance.neo4j.subnet_id == var.subnet_id
      && toset(aws_instance.neo4j.vpc_security_group_ids) == toset([var.security_group_id])
      && aws_instance.neo4j.iam_instance_profile == aws_iam_instance_profile.neo4j.name
      && aws_instance.neo4j.user_data_replace_on_change == true
    )
    error_message = "인스턴스는 t3.large이고 공인 IP 없이 입력받은 서브넷·보안 그룹 하나에 놓이며, SSM에서 읽은 AMI를 쓰고 인스턴스 프로필이 붙어야 한다. user-data가 바뀌면 인스턴스를 교체한다."
  }

  assert {
    condition = (
      length(aws_instance.neo4j.metadata_options) == 1
      && one(aws_instance.neo4j.metadata_options).http_tokens == "required"
      && one(aws_instance.neo4j.metadata_options).http_endpoint == "enabled"
      && one(aws_instance.neo4j.metadata_options).http_put_response_hop_limit == 1
    )
    error_message = "메타데이터는 IMDSv2 필수(http_tokens = required), 홉 제한 1이어야 한다(컨테이너가 인스턴스 자격 증명을 훔쳐 가지 못하게 한다)."
  }

  assert {
    condition = (
      length(aws_instance.neo4j.root_block_device) == 1
      && one(aws_instance.neo4j.root_block_device).volume_type == "gp3"
      && one(aws_instance.neo4j.root_block_device).volume_size == 20
      && one(aws_instance.neo4j.root_block_device).encrypted == true
      && one(aws_instance.neo4j.root_block_device).delete_on_termination == true
    )
    error_message = "루트 디스크는 암호화된 gp3 20GiB이고 인스턴스와 함께 지워져야 한다."
  }
}

# 그래프 데이터가 인스턴스와 같이 사라지거나, 암호화되지 않거나, 다른 AZ에 만들어져 붙지 못하는 것을 막는다.
# AZ는 인스턴스의 속성이 아니라 서브넷에서 읽어야 한다. 인스턴스를 교체하면 그 속성이 '적용 뒤에 정해짐'이 되고, AZ는 바꾸면 볼륨을 새로 만드는
# 인자라 Terraform이 볼륨까지 교체하려 한다(데이터 삭제). 이 모듈에서 인스턴스는 user-data가 바뀔 때마다 교체되므로 반드시 끊어야 한다.
# 가짜 provider 아래에서 인스턴스의 availability_zone은 무작위 문자열이라, 서브넷에서 읽었다면 고정 값(ap-northeast-2a)과 같고 아니면 달라진다.
run "data_volume_is_separate_encrypted_and_in_the_subnet_az" {
  assert {
    condition = (
      aws_ebs_volume.data.type == "gp3"
      && aws_ebs_volume.data.size == 40
      && aws_ebs_volume.data.encrypted == true
      && aws_ebs_volume.data.availability_zone == "ap-northeast-2a"
      && aws_ebs_volume.data.availability_zone == data.aws_subnet.this.availability_zone
    )
    error_message = "데이터 볼륨은 인스턴스가 놓일 서브넷의 AZ(data.aws_subnet.this)에 만든 암호화된 gp3 40GiB여야 한다. 인스턴스의 availability_zone을 쓰면 인스턴스 교체 때 볼륨도 교체된다."
  }

  assert {
    condition = (
      aws_volume_attachment.data.device_name == "/dev/sdf"
      && aws_volume_attachment.data.volume_id == aws_ebs_volume.data.id
      && aws_volume_attachment.data.instance_id == aws_instance.neo4j.id
      && aws_volume_attachment.data.stop_instance_before_detaching == true
    )
    error_message = "데이터 볼륨은 /dev/sdf로 이 인스턴스에 붙고, 떼기 전에 인스턴스를 멈춰야 한다."
  }
}

# 부팅 스크립트가 Neo4j를 compose와 다른 설정으로 띄우거나, 비밀번호가 스크립트에 새겨지거나, 데이터가 루트 디스크에 쌓이는 것을 막는다.
run "user_data_installs_mounts_and_runs_neo4j_like_compose" {
  assert {
    condition = (
      startswith(aws_instance.neo4j.user_data, "#!/bin/bash")
      && strcontains(aws_instance.neo4j.user_data, "set -euo pipefail")
      && strcontains(aws_instance.neo4j.user_data, "dnf install -y docker")
    )
    error_message = "user-data는 #!/bin/bash로 시작하고 set -euo pipefail을 쓰며 dnf로 docker를 설치해야 한다."
  }

  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, "neo4j:5.26-community")
      && strcontains(aws_instance.neo4j.user_data, "-p 7687:7687")
      && length(regexall("(--publish[ =]|\\s-p\\s+)[0-9]", aws_instance.neo4j.user_data)) == 1
      && !can(regex("\\s-P\\s|--publish-all", aws_instance.neo4j.user_data))
      && strcontains(aws_instance.neo4j.user_data, "--restart unless-stopped")
      && strcontains(aws_instance.neo4j.user_data, "--stop-timeout 60")
      && strcontains(aws_instance.neo4j.user_data, "--log-opt max-size=10m")
      && strcontains(aws_instance.neo4j.user_data, "--log-opt max-file=3")
    )
    error_message = "컨테이너는 neo4j:5.26-community이고 포트는 bolt(7687) 하나만 게시하며(브라우저 7474는 열지 않는다) 재부팅 때 다시 떠야 한다. 멈출 때 데이터베이스가 체크포인트를 마치도록 --stop-timeout 60을 주고, 로그는 compose처럼 10m×3개로 돌린다."
  }

  # 그래프 데이터와 로그가 컨테이너 안이 아니라 마운트한 데이터 볼륨에 쌓여야 한다(compose의 neo4j_data·neo4j_logs 볼륨에 해당).
  # 이 줄이 빠지면 데이터가 컨테이너 층(루트 디스크)에 쌓여 인스턴스를 교체할 때 사라진다.
  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, "-v /var/lib/neo4j/data:/data")
      && strcontains(aws_instance.neo4j.user_data, "-v /var/lib/neo4j/logs:/logs")
    )
    error_message = "컨테이너는 -v /var/lib/neo4j/data:/data 와 -v /var/lib/neo4j/logs:/logs 로 데이터 볼륨의 디렉터리를 마운트해야 한다."
  }

  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, "NEO4J_PLUGINS='[\"apoc\"]'")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_dbms_security_procedures_unrestricted='apoc.*'")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_heap_initial__size=2G")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_heap_max__size=2G")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_pagecache_size=1G")
    )
    error_message = "APOC 플러그인과 메모리 설정(heap 2G·pagecache 1G)이 docker-compose.prod.yml과 같아야 한다."
  }

  # 비밀번호는 스크립트에 없고, 부팅할 때 인스턴스 역할 권한으로 Secrets Manager에서 읽는다. 리전은 비밀 ARN에서 얻는다(IMDS에 기대지 않는다).
  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, aws_secretsmanager_secret.neo4j.arn)
      && strcontains(aws_instance.neo4j.user_data, "get-secret-value")
      && strcontains(aws_instance.neo4j.user_data, "--region")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_AUTH")
      && !strcontains(aws_instance.neo4j.user_data, "password1234")
    )
    error_message = "스크립트는 비밀 ARN으로 aws secretsmanager get-secret-value(--region 지정)를 불러 비밀번호를 읽고 NEO4J_AUTH를 만들어야 하며, 비밀번호 문자열을 담으면 안 된다."
  }

  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, "/dev/nvme1n1")
      && strcontains(aws_instance.neo4j.user_data, "/var/lib/neo4j")
      && strcontains(aws_instance.neo4j.user_data, "nofail")
      && strcontains(aws_instance.neo4j.user_data, "RequiresMountsFor=/var/lib/neo4j")
    )
    error_message = "데이터 볼륨은 NVMe 장치(/dev/nvme1n1)를 /var/lib/neo4j에 마운트하고(fstab nofail), docker가 마운트 뒤에 뜨도록 RequiresMountsFor를 걸어야 한다."
  }

  # 볼륨은 인스턴스가 만들어진 뒤에 붙으므로 스크립트가 장치가 나타날 때까지 기다려야 한다. 기다리지 않으면 첫 부팅이 장치 없음으로 실패한다.
  # 비밀을 읽는 재시도 반복문에도 for·sleep이 있어서, 반복문이 블록 장치 검사(-b)를 직접 감싸는지로 본다.
  assert {
    condition = (
      can(regex("(?m)^\\s*(until|while)\\s[^\\n]*\\s-b\\s|^\\s*for\\s[^\\n]*\\n[^\\n]*\\s-b\\s", aws_instance.neo4j.user_data))
      && can(regex("(?m)^[^#\\n]*\\ssleep\\s", aws_instance.neo4j.user_data))
    )
    error_message = "스크립트는 데이터 볼륨 장치가 나타날 때까지 반복해서 기다려야 한다(until·while·for 반복문 안에서 [ -b 장치 ] 검사와 sleep)."
  }

  # docker는 지금 떠 있어야 하고(docker run이 바로 이어진다) 재부팅 뒤에도 떠야 한다(컨테이너가 --restart로 다시 뜨려면 데몬이 먼저 떠야 한다).
  assert {
    condition = (
      can(regex("(?m)^[^#\\n]*systemctl\\s+enable\\s+--now\\s+docker", aws_instance.neo4j.user_data))
      || (
        can(regex("(?m)^[^#\\n]*systemctl\\s+enable\\s+docker", aws_instance.neo4j.user_data))
        && can(regex("(?m)^[^#\\n]*systemctl\\s+start\\s+docker", aws_instance.neo4j.user_data))
      )
    )
    error_message = "docker 데몬을 지금 시작하고 부팅 때도 켜야 한다(systemctl enable --now docker)."
  }

  # 인스턴스가 교체되면 이 스크립트가 데이터가 든 볼륨을 붙인 채 다시 돈다. 파일 시스템이 이미 있을 때 mkfs를 하면 그래프 데이터가 전부 지워진다.
  # 그래서 mkfs.xfs는 blkid로 '파일 시스템이 없음'을 확인한 if 바로 안에서만 불려야 한다.
  assert {
    condition     = can(regex("(?s)if\\s+!\\s+blkid[^\\n]*;\\s*then\\s+mkfs\\.xfs", aws_instance.neo4j.user_data))
    error_message = "mkfs.xfs는 'if ! blkid ...; then mkfs.xfs ...' 안에서만 실행돼야 한다(이미 데이터가 있는 볼륨을 다시 포맷하면 안 된다)."
  }

  # 주석을 뺀 줄에서 세므로 설명 주석에 mkfs를 적어도 된다.
  assert {
    condition     = length(regexall("(?m)^[^#\\n]*mkfs", aws_instance.neo4j.user_data)) == 1
    error_message = "주석이 아닌 줄의 mkfs 호출은 blkid로 지킨 한 곳뿐이어야 한다. 지금 ${length(regexall("(?m)^[^#\\n]*mkfs", aws_instance.neo4j.user_data))}곳."
  }
}

# 이 스크립트는 첫 부팅 때 한 번만 돈다. 그런데 인스턴스는 인터넷 출구(NAT) 경로가 만들어지기 전에 뜰 수 있다 — network 모듈이 내보내는
# 서브넷 ID는 NAT 경로 생성을 기다리지 않는다. 그때 재시도가 없으면 dnf 실패에서 스크립트가 멈춰 Neo4j가 없는 인스턴스가 남는다
# (ECS 태스크와 달리 다시 시도해 줄 곳이 없다). 그래서 인터넷이 필요한 두 단계(패키지 설치, 이미지 내려받기)는 retry 함수 안에서 돌고,
# retry는 끝내 안 되면 실패로 끝나야 한다(실패를 삼키면 설치 없이 다음 줄로 넘어간다).
# 이 문제를 모듈 전체 depends_on으로 풀지 않는 이유: 그러면 데이터 볼륨의 AZ를 읽는 data 소스도 network 변경이 있을 때마다 apply 뒤로 미뤄져
# 볼륨 교체 계획이 날 수 있다.
run "network_steps_are_retried_and_a_final_failure_stops_the_script" {
  assert {
    condition = length([
      for b in regexall("(?sm)^retry\\(\\) \\{.*?\\n\\}", aws_instance.neo4j.user_data) : b
      if can(regex("\\n\\s*(for|while|until)\\s", b)) && can(regex("\\n\\s*sleep\\s+10\\b", b)) && can(regex("\\n\\s*return 1\\b", b))
    ]) == 1
    error_message = "retry 함수가 하나 있어야 한다. 반복문 안에서 10초씩 쉬며 다시 시도하고, 끝내 안 되면 return 1로 실패해야 한다."
  }

  assert {
    condition = (
      can(regex("(?m)^retry\\s+dnf\\s+install\\s+-y\\s+docker\\b", aws_instance.neo4j.user_data))
      && can(regex("(?m)^retry\\s+docker\\s+pull\\s+\"?neo4j:5\\.26-community\"?\\s*$", aws_instance.neo4j.user_data))
    )
    error_message = "패키지 설치(retry dnf install -y docker ...)와 이미지 내려받기(retry docker pull <이미지>)는 retry로 불러야 한다."
  }
}

# Neo4j 비밀번호가 상태나 코드에 평문으로 남는 것을 막는다. 값은 쓰기 전용으로만 넣는다.
run "secret_holds_the_password_write_only" {
  assert {
    condition = (
      aws_secretsmanager_secret.neo4j.name == "whycode/staging/neo4j"
      && aws_secretsmanager_secret.neo4j.recovery_window_in_days == 0
      && aws_secretsmanager_secret_version.neo4j.secret_id == aws_secretsmanager_secret.neo4j.id
      && aws_secretsmanager_secret_version.neo4j.secret_string == null
      && aws_secretsmanager_secret_version.neo4j.secret_string_wo_version == 1
    )
    error_message = "비밀 이름은 whycode/staging/neo4j, 삭제 유예 0일이고 값은 쓰기 전용(secret_string_wo, 버전 1)이어야 한다. 일반 secret_string은 상태에 평문으로 남는다."
  }

  # 쓰기 전용 값은 읽을 수 없어서, 비밀 속 비밀번호가 상태에 남지 않는 ephemeral 값에서 나온다는 것을 소스에서 확인한다.
  # 이 자리에 고정 문자열이나 변수가 들어가면 비밀번호가 코드나 상태에 남는다.
  assert {
    condition = length([
      for b in flatten([for f in fileset(path.module, "*.tf") : regexall("(?s)resource\\s+\"aws_secretsmanager_secret_version\"\\s+\"neo4j\"\\s*\\{.*?\\n\\}", file("${path.module}/${f}"))]) : b
      if can(regex("secret_string_wo\\s*=\\s*jsonencode\\(\\{[^}]*password\\s*=\\s*ephemeral\\.aws_secretsmanager_random_password\\.neo4j\\.random_password", b))
    ]) == 1
    error_message = "비밀 값(secret_string_wo)의 password는 ephemeral.aws_secretsmanager_random_password.neo4j.random_password여야 한다."
  }
}

# 인스턴스 역할이 넓어지거나(백업을 지우거나 다른 비밀을 읽는 것) 신뢰 주체가 바뀌는 것을 막는다.
run "instance_role_can_read_its_secret_write_backups_and_use_ssm_only" {
  assert {
    condition = (
      aws_iam_role.neo4j.name == "whycode-staging-neo4j"
      && length(jsondecode(aws_iam_role.neo4j.assume_role_policy).Statement) == 1
      && jsonencode(jsondecode(aws_iam_role.neo4j.assume_role_policy).Statement[0]) == jsonencode({
        Effect    = "Allow"
        Principal = { Service = "ec2.amazonaws.com" }
        Action    = "sts:AssumeRole"
      })
      && aws_iam_instance_profile.neo4j.role == aws_iam_role.neo4j.name
    )
    error_message = "역할은 EC2 서비스만 맡을 수 있고 인스턴스 프로필이 이 역할을 가리켜야 한다. 지금 신뢰 정책: ${aws_iam_role.neo4j.assume_role_policy}"
  }

  assert {
    condition = (
      aws_iam_role_policy_attachment.ssm.role == aws_iam_role.neo4j.name
      && aws_iam_role_policy_attachment.ssm.policy_arn == "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    )
    error_message = "SSM 접속을 위한 관리형 정책 AmazonSSMManagedInstanceCore가 이 역할에 붙어야 한다."
  }

  assert {
    condition = (
      aws_iam_role_policy.neo4j.role == aws_iam_role.neo4j.name
      && aws_iam_role_policy.neo4j.name == "neo4j-instance"
      && toset([
        for s in jsondecode(aws_iam_role_policy.neo4j.policy).Statement :
        jsonencode({ Effect = s.Effect, Action = sort(flatten([s.Action])), Resource = sort(flatten([s.Resource])) })
        ]) == toset([
        jsonencode({ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [aws_secretsmanager_secret.neo4j.arn] }),
        jsonencode({ Effect = "Allow", Action = ["s3:GetBucketLocation", "s3:ListBucket"], Resource = [aws_s3_bucket.backup.arn] }),
        jsonencode({ Effect = "Allow", Action = ["s3:AbortMultipartUpload", "s3:GetObject", "s3:PutObject"], Resource = ["${aws_s3_bucket.backup.arn}/*"] }),
      ])
      && length(jsondecode(aws_iam_role_policy.neo4j.policy).Statement) == 3
    )
    error_message = "인스턴스 정책은 자기 비밀 읽기, 백업 버킷 목록·위치 조회, 백업 객체 쓰기·읽기 세 문뿐이어야 한다(삭제·와일드카드 없음). 지금 정책: ${aws_iam_role_policy.neo4j.policy}"
  }
}

# 백업 버킷이 공개되거나, 암호화·버전 관리가 빠지거나, 평문 HTTP 요청을 받는 것을 막는다.
run "backup_bucket_is_private_encrypted_versioned_and_tls_only" {
  assert {
    condition = (
      aws_s3_bucket.backup.bucket == "whycode-staging-neo4j-backup-123456789012"
      && aws_s3_bucket.backup.force_destroy == false
      && aws_s3_bucket_public_access_block.backup.bucket == aws_s3_bucket.backup.id
      && aws_s3_bucket_public_access_block.backup.block_public_acls
      && aws_s3_bucket_public_access_block.backup.block_public_policy
      && aws_s3_bucket_public_access_block.backup.ignore_public_acls
      && aws_s3_bucket_public_access_block.backup.restrict_public_buckets
      && one(aws_s3_bucket_ownership_controls.backup.rule).object_ownership == "BucketOwnerEnforced"
      && one(aws_s3_bucket_versioning.backup.versioning_configuration).status == "Enabled"
    )
    error_message = "백업 버킷은 공개 접근·ACL을 막고 버전 관리를 켜야 하며, 기본은 destroy가 비어 있지 않은 버킷을 지우지 않는다(백업은 지우지 않는 것이 기본이다)."
  }

  assert {
    condition = (
      aws_s3_bucket_server_side_encryption_configuration.backup.bucket == aws_s3_bucket.backup.id
      && length(aws_s3_bucket_server_side_encryption_configuration.backup.rule) == 1
      && one([for r in aws_s3_bucket_server_side_encryption_configuration.backup.rule : one(r.apply_server_side_encryption_by_default).sse_algorithm]) == "AES256"
    )
    error_message = "백업 버킷 기본 암호화는 AES256 하나여야 한다."
  }

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.backup.bucket == aws_s3_bucket.backup.id
      && length(aws_s3_bucket_lifecycle_configuration.backup.rule) == 2
      && alltrue([for r in aws_s3_bucket_lifecycle_configuration.backup.rule : r.status == "Enabled"])
      && one([for r in aws_s3_bucket_lifecycle_configuration.backup.rule : one(r.noncurrent_version_expiration).noncurrent_days if r.id == "expire-noncurrent-versions"]) == 30
      && one([for r in aws_s3_bucket_lifecycle_configuration.backup.rule : one(r.abort_incomplete_multipart_upload).days_after_initiation if r.id == "abort-incomplete-multipart-uploads"]) == 7
    )
    error_message = "수명 주기는 이전 버전을 30일 뒤 지우고 끝나지 않은 멀티파트 업로드를 7일 뒤 지우는 두 규칙(둘 다 Enabled)이어야 한다. 현재 버전은 지우지 않는다."
  }

  assert {
    condition = (
      aws_s3_bucket_policy.backup.bucket == aws_s3_bucket.backup.id
      && length(jsondecode(aws_s3_bucket_policy.backup.policy).Statement) == 1
      && jsonencode(jsondecode(aws_s3_bucket_policy.backup.policy).Statement[0]) == jsonencode({
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.backup.arn, "${aws_s3_bucket.backup.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      })
    )
    error_message = "백업 버킷 정책은 TLS가 아닌 요청을 거부하는 문 하나뿐이어야 한다(허용은 인스턴스 역할의 IAM 정책이 맡는다). 지금 정책: ${aws_s3_bucket_policy.backup.policy}"
  }
}

# 비밀을 읽는 권한이 다른 서비스로 새거나 와일드카드로 넓어지는 것을 막는다. Neo4j는 ai-engine만 쓴다.
run "only_ai_engine_exec_role_can_read_the_secret" {
  assert {
    condition     = toset(keys(aws_iam_role_policy.read_secret)) == toset(["ai-engine"])
    error_message = "비밀을 읽는 정책은 ai-engine 하나에만 있어야 한다. 지금 키: ${jsonencode(keys(aws_iam_role_policy.read_secret))}"
  }

  assert {
    condition = alltrue([
      for k, p in aws_iam_role_policy.read_secret :
      p.role == var.execution_role_names[k]
      && p.name == "read-neo4j-secret"
      && length(jsondecode(p.policy).Statement) == 1
      && jsondecode(p.policy).Statement[0].Effect == "Allow"
      && toset(flatten([jsondecode(p.policy).Statement[0].Action])) == toset(["secretsmanager:GetSecretValue"])
      && toset(flatten([jsondecode(p.policy).Statement[0].Resource])) == toset([aws_secretsmanager_secret.neo4j.arn])
    ])
    error_message = "정책은 ai-engine 실행 역할에 붙어 이 비밀의 GetSecretValue 하나만 허용해야 한다. 지금 정책: ${jsonencode({ for k, p in aws_iam_role_policy.read_secret : k => p.policy })}"
  }
}

run "outputs_return_instance_volume_bucket_and_secret" {
  assert {
    condition = (
      output.instance_id == aws_instance.neo4j.id
      && output.private_ip == aws_instance.neo4j.private_ip
      && output.secret_arn == aws_secretsmanager_secret.neo4j.arn
      && output.backup_bucket_name == aws_s3_bucket.backup.bucket
      && output.backup_bucket_arn == aws_s3_bucket.backup.arn
      && output.data_volume_id == aws_ebs_volume.data.id
    )
    error_message = "출력은 인스턴스 id·사설 IP·비밀 ARN·백업 버킷 이름과 ARN·데이터 볼륨 id와 같아야 한다."
  }
}

# 값이 고정 문자열이 아니라 변수에서 나오는지 본다(user-data 안의 이미지·메모리 값까지).
run "variables_flow_into_names_sizes_user_data_and_versions" {
  variables {
    environment                 = "prod"
    instance_type               = "m5.xlarge"
    root_volume_size_gb         = 30
    data_volume_size_gb         = 100
    neo4j_image                 = "neo4j:5.26.1-community"
    heap_size                   = "4G"
    pagecache_size              = "2G"
    password_version            = 2
    backup_bucket_force_destroy = true
    backup_noncurrent_days      = 60
  }

  assert {
    condition = (
      aws_iam_role.neo4j.name == "whycode-prod-neo4j"
      && aws_secretsmanager_secret.neo4j.name == "whycode/prod/neo4j"
      && aws_s3_bucket.backup.bucket == "whycode-prod-neo4j-backup-123456789012"
      && aws_s3_bucket.backup.force_destroy == true
      && aws_instance.neo4j.instance_type == "m5.xlarge"
      && one(aws_instance.neo4j.root_block_device).volume_size == 30
      && aws_ebs_volume.data.size == 100
      && aws_secretsmanager_secret_version.neo4j.secret_string_wo_version == 2
      && one([for r in aws_s3_bucket_lifecycle_configuration.backup.rule : one(r.noncurrent_version_expiration).noncurrent_days if r.id == "expire-noncurrent-versions"]) == 60
    )
    error_message = "환경 이름·인스턴스 종류·볼륨 크기·비밀 버전·백업 버킷 설정이 변수에서 나와야 한다."
  }

  assert {
    condition = (
      strcontains(aws_instance.neo4j.user_data, "neo4j:5.26.1-community")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_heap_initial__size=4G")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_heap_max__size=4G")
      && strcontains(aws_instance.neo4j.user_data, "NEO4J_server_memory_pagecache_size=2G")
      && !strcontains(aws_instance.neo4j.user_data, "neo4j:5.26-community")
    )
    error_message = "user-data의 이미지와 메모리 값이 변수에서 나와야 한다."
  }
}

# 이름이 AWS 규칙에 맞는지 본다. 가짜 provider는 이 문자 규칙을 걸러 주지 못한다.
run "names_follow_aws_rules" {
  assert {
    condition = (
      can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", aws_s3_bucket.backup.bucket))
      && length(aws_iam_role.neo4j.name) <= 64
      && can(regex("^[A-Za-z0-9+=,.@_-]+$", aws_iam_role.neo4j.name))
    )
    error_message = "버킷 이름은 소문자·숫자·점·하이픈 3~63자, IAM 역할 이름은 64자 이하 영문·숫자·+=,.@_-여야 한다."
  }

  assert {
    condition     = can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", aws_secretsmanager_secret.neo4j.description))
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

run "data_volume_rejects_below_ten_gib" {
  command = plan

  variables {
    data_volume_size_gb = 9
  }

  expect_failures = [var.data_volume_size_gb]
}

run "root_volume_rejects_below_eight_gib" {
  command = plan

  variables {
    root_volume_size_gb = 7
  }

  expect_failures = [var.root_volume_size_gb]
}

run "heap_size_rejects_unitless_values" {
  command = plan

  variables {
    heap_size = "2"
  }

  expect_failures = [var.heap_size]
}

run "pagecache_size_rejects_lowercase_units" {
  command = plan

  variables {
    pagecache_size = "1g"
  }

  expect_failures = [var.pagecache_size]
}

run "neo4j_image_rejects_empty" {
  command = plan

  variables {
    neo4j_image = ""
  }

  expect_failures = [var.neo4j_image]
}

run "secret_recovery_window_rejects_three_days" {
  command = plan

  variables {
    secret_recovery_window_days = 3
  }

  expect_failures = [var.secret_recovery_window_days]
}

run "password_version_rejects_zero" {
  command = plan

  variables {
    password_version = 0
  }

  expect_failures = [var.password_version]
}

run "backup_noncurrent_days_rejects_zero" {
  command = plan

  variables {
    backup_noncurrent_days = 0
  }

  expect_failures = [var.backup_noncurrent_days]
}
