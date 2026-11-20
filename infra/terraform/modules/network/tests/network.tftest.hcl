# T1 네트워크 모듈(VPC·서브넷·NAT·라우팅·보안 그룹)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/network test
#
# 핵심은 보안 그룹이다. 보안 그룹이 서비스 간 허용 경로의 전부라서, "열면 안 되는 길이 열리지 않는다"를 규칙 하나하나로 고정한다.
# - 인바운드 11개는 전부 보안 그룹 참조다. 주소 대역(0.0.0.0/0 포함)·접두사 목록 인바운드는 없고, ALB 보안 그룹에는 인바운드가 하나도 없다
#   (CloudFront 쪽 보안 그룹은 다음 묶음 T2가 붙인다).
# - 아웃바운드 15개는 인바운드 11개와 짝인 보안 그룹 참조 11개(출발 = 인바운드의 출처, 목적지 = 인바운드의 대상, 같은 포트)와
#   바깥 API·AWS 엔드포인트용 443 4개(backend·pipeline_worker·ai_engine·neo4j → 0.0.0.0/0)다. 보안 그룹을 만들면 Terraform이
#   기본 아웃바운드(전부 허용)를 지우므로 짝이 없으면 통신이 안 된다. alb·rds·mq는 SG 참조 외의 아웃바운드가 없고, rds·mq는 아웃바운드가 아예 없다.
#   DNS(53) 규칙은 두지 않는다 — 보안 그룹은 VPC DNS 질의를 막지 못한다.
#
# 가짜 provider를 쓰는 규칙
# - 계산 속성(id·arn 등)은 apply 단계에서만 채워지므로 run은 기본값 command = apply로 둔다. 가짜라 실제로는 아무것도 만들어지지 않는다.
#   plan으로 바꾸면 계산값에 기대는 assert가 "Unknown condition value" 오류로 끝난다. 계산값을 읽지 않는 run(자원 목록, 변수 검증)만 plan이다.
# - run이 assert 실패가 아니라 오류로 끝나면 뒤 run이 전부 skip되므로, 값이 없을 수 있는 곳은 try()로 감싸 실패로 끝나게 한다.
#   run 순서도 같은 이유다. 자원 목록과 단일 자원이 늘어났는지를 앞에서 먼저 보고, 단일 자원(NAT·공개 서브넷)을 참조하는 run은 뒤에 두며,
#   "Missing expected failure"가 오류로 끝나는 변수 검증 run은 맨 뒤에 둔다.
# - 테스트 파일에는 locals를 쓸 수 없어서 조건식을 규칙마다 인라인으로 적는다. 규칙 for_each의 키 이름은 계약이 아니라서 키에 기대지 않고 값으로만 찾는다.
# - 가짜 provider는 provider 쪽 형식 검사(CIDR 문법, 인자 충돌)만 하고, AWS API가 거부하는 값(겹치는 CIDR, /28보다 작은 서브넷, 한글 description 등)은
#   걸러 주지 못한다. 그런 값은 아래 run이 직접 검사한다.
# - 계약에 적힌 주소의 자원만 볼 수 있는 한계는 첫 run이 메운다. 모듈 소스에 선언된 자원 주소를 계약의 21개와 대조해서,
#   다른 이름으로 덧붙인 규칙·라우트·NAT·보안 그룹이 조용히 끼어들지 못하게 한다(정규식으로 소스를 읽는 검사라 그 run만 소스 꼴에 기댄다).
# - Terraform에는 cidrcontains()가 없다(v1.17.2 확인). CIDR 포함·겹침은 cidrhost·cidrsubnet으로 계산한다.
#   "A 블록 안에 B의 첫 주소가 있다" ⇔ cidrsubnet("<B 첫 주소>/<A 접두사 길이>", 0, 0) == cidrsubnet(A, 0, 0)

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {}

# 모듈의 필수 변수(name)만 준다. vpc_cidr·availability_zones는 기본값으로 돌리고, 바꿔 넣어 보는 run은 따로 있다.
variables {
  name = "whycode-test"
}

# 계약에 없는 자원이 덧붙는 것을 막는다 — 아래 run들은 계약에 적힌 주소의 자원만 볼 수 있어서, 다른 이름의 규칙·라우트·NAT·보안 그룹이나
# 레거시 aws_security_group_rule로 길을 열어도 통과해 버린다. 그래서 모듈 소스에 선언된 자원 주소를 계약의 21개와 직접 대조한다.
# 소스를 정규식으로 읽으므로 자원은 파일에 resource "타입" "이름" 꼴로 쓴다. 자원을 일부러 늘릴 때는 이 목록과 해당 검사를 함께 고친다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_vpc.main",
      "aws_internet_gateway.main",
      "aws_subnet.public",
      "aws_subnet.private",
      "aws_eip.nat",
      "aws_nat_gateway.main",
      "aws_route_table.public",
      "aws_route_table.private",
      "aws_route.public_internet",
      "aws_route.private_nat",
      "aws_route_table_association.public",
      "aws_route_table_association.private",
      "aws_security_group.alb",
      "aws_security_group.backend",
      "aws_security_group.pipeline_worker",
      "aws_security_group.ai_engine",
      "aws_security_group.neo4j",
      "aws_security_group.rds",
      "aws_security_group.mq",
      "aws_vpc_security_group_ingress_rule.this",
      "aws_vpc_security_group_egress_rule.this",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 21개와 정확히 같아야 한다(덧붙은 자원도, 빠진 자원도 안 된다). 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
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

# NAT 게이트웨이·공개 서브넷·EIP가 count·for_each로 늘어나는 것을 막는다 — NAT는 켜 두는 동안 시간당 요금이 붙는 자원이라 하나만 두고,
# 공개 서브넷도 NAT만 두려고 하나뿐이다. 늘어나면 aws_nat_gateway.main.id 같은 계약 주소도 깨진다.
# 단일 인스턴스는 tolist·tomap으로 바뀌지 않고, count는 tolist로, for_each는 tomap으로 바뀐다. 이 방식이면 늘어나도 참조 오류가 아니라 이 검사의 실패로 끝난다.
run "nat_gateway_public_subnet_and_eip_are_single_instances" {
  assert {
    condition     = !can(tolist(aws_nat_gateway.main)) && !can(tomap(aws_nat_gateway.main))
    error_message = "NAT 게이트웨이는 aws_nat_gateway.main 하나뿐이어야 한다(count·for_each 금지). 지금 형태: ${can(tolist(aws_nat_gateway.main)) ? "count ${length(aws_nat_gateway.main[*])}개" : "for_each"}"
  }

  assert {
    condition     = !can(tolist(aws_subnet.public)) && !can(tomap(aws_subnet.public))
    error_message = "공개 서브넷은 aws_subnet.public 하나뿐이어야 한다(count·for_each 금지). 지금 형태: ${can(tolist(aws_subnet.public)) ? "count ${length(aws_subnet.public[*])}개" : "for_each"}"
  }

  assert {
    condition     = !can(tolist(aws_eip.nat)) && !can(tomap(aws_eip.nat))
    error_message = "NAT용 EIP는 aws_eip.nat 하나뿐이어야 한다(count·for_each 금지). 지금 형태: ${can(tolist(aws_eip.nat)) ? "count ${length(aws_eip.nat[*])}개" : "for_each"}"
  }
}

# VPC의 DNS 지원·호스트 이름이 꺼지거나 CIDR이 변수와 따로 노는 것을 막는다 — 꺼지면 VPC 안에서 RDS·MQ 같은 엔드포인트 이름이 사설 주소로 풀리지 않는다.
run "vpc_enables_dns_and_follows_the_cidr_variable" {
  assert {
    condition     = aws_vpc.main.cidr_block == var.vpc_cidr
    error_message = "VPC cidr_block은 변수 vpc_cidr와 같아야 한다. 지금 값: ${jsonencode(aws_vpc.main.cidr_block)}, 변수: ${jsonencode(var.vpc_cidr)}"
  }

  assert {
    condition     = aws_vpc.main.cidr_block == "10.20.0.0/16"
    error_message = "vpc_cidr를 주지 않으면 VPC cidr_block은 기본값 10.20.0.0/16이어야 한다. 지금 값: ${jsonencode(aws_vpc.main.cidr_block)}"
  }

  assert {
    condition     = aws_vpc.main.enable_dns_support == true
    error_message = "VPC enable_dns_support는 true여야 한다. 지금 값: ${jsonencode(aws_vpc.main.enable_dns_support)}"
  }

  assert {
    condition     = aws_vpc.main.enable_dns_hostnames == true
    error_message = "VPC enable_dns_hostnames는 true여야 한다. 지금 값: ${jsonencode(aws_vpc.main.enable_dns_hostnames)}"
  }
}

# 서브넷 구성(공개 1·비공개 2)이 흐트러지거나 다른 VPC에 놓이는 것을 막는다 — 비공개 서브넷이 하나뿐이면 ALB를 만들 수 없고(AZ 2개 필요), 다른 VPC에 놓이면 라우팅·보안 그룹이 이어지지 않는다.
run "one_public_and_two_private_subnets_in_the_vpc" {
  assert {
    condition     = length(aws_subnet.private) == 2
    error_message = "비공개 서브넷은 정확히 2개(count = 2)여야 한다. 지금 개수: ${length(aws_subnet.private)}"
  }

  assert {
    condition     = aws_subnet.public.vpc_id == aws_vpc.main.id
    error_message = "공개 서브넷이 aws_vpc.main에 속해 있지 않다. 지금 vpc_id: ${jsonencode(aws_subnet.public.vpc_id)}, VPC id: ${aws_vpc.main.id}"
  }

  assert {
    condition     = try(alltrue([for s in aws_subnet.private : s.vpc_id == aws_vpc.main.id]), false)
    error_message = "비공개 서브넷이 모두 aws_vpc.main에 속해야 한다. 지금 vpc_id: ${jsonencode([for s in aws_subnet.private : s.vpc_id])}, VPC id: ${aws_vpc.main.id}"
  }
}

# 서브넷의 AZ 배치가 변수와 어긋나거나 비공개 둘이 같은 AZ에 몰리는 것을 막는다 — 몰리면 ALB가 AZ 2개 조건을 못 채우고, 변수로 AZ를 바꿔도 반영되지 않는다.
run "subnets_use_the_two_zones_from_the_variable" {
  assert {
    condition     = aws_subnet.public.availability_zone == var.availability_zones[0]
    error_message = "공개 서브넷은 첫 번째 AZ(var.availability_zones[0])에 있어야 한다. 지금 값: ${jsonencode(aws_subnet.public.availability_zone)}, 변수: ${jsonencode(var.availability_zones)}"
  }

  assert {
    condition     = try(alltrue([for i, s in aws_subnet.private : s.availability_zone == var.availability_zones[i]]), false)
    error_message = "비공개 서브넷 i는 var.availability_zones[i]에 있어야 한다. 지금 AZ: ${jsonencode([for s in aws_subnet.private : s.availability_zone])}, 변수: ${jsonencode(var.availability_zones)}"
  }

  assert {
    condition     = try(length(toset([for s in aws_subnet.private : s.availability_zone])) == 2, false)
    error_message = "비공개 서브넷 둘의 AZ가 서로 달라야 한다. 지금 AZ: ${jsonencode([for s in aws_subnet.private : s.availability_zone])}"
  }

  assert {
    condition     = try(toset([for s in aws_subnet.private : s.availability_zone]) == toset(var.availability_zones), false)
    error_message = "비공개 서브넷 둘의 AZ가 변수 availability_zones 둘과 같아야 한다. 지금 AZ: ${jsonencode([for s in aws_subnet.private : s.availability_zone])}, 변수: ${jsonencode(var.availability_zones)}"
  }
}

# 어느 서브넷이든 켜면 인스턴스에 공인 IP가 자동으로 붙는 것을 막는다 — 비공개 서브넷은 NAT로만 나가야 하고, 공개 서브넷도 NAT 하나만 두는 곳이라 공인 IP를 나눠 줄 이유가 없다.
# 적지 않으면 가짜 provider 아래에서 null이다(실제 기본값은 false). 그래서 null과 false를 모두 통과시킨다.
run "no_subnet_hands_out_public_ips" {
  assert {
    condition     = aws_subnet.public.map_public_ip_on_launch != true
    error_message = "공개 서브넷의 map_public_ip_on_launch는 true이면 안 된다. 지금 값: ${jsonencode(aws_subnet.public.map_public_ip_on_launch)}"
  }

  assert {
    condition     = try(alltrue([for s in aws_subnet.private : s.map_public_ip_on_launch != true]), false)
    error_message = "비공개 서브넷의 map_public_ip_on_launch는 true이면 안 된다. 지금 값: ${jsonencode([for s in aws_subnet.private : s.map_public_ip_on_launch])}"
  }
}

# 서브넷 CIDR이 VPC 밖이거나 AWS가 거부하는 접두사 길이인 것을 막는다 — 가짜 provider는 이런 값을 걸러 주지 못해 실제 apply에서야 실패한다.
# 서브넷 접두사는 /16~/28만 허용된다(호스트 비트가 남은 표기는 provider가 plan에서 먼저 거부한다).
run "subnet_cidrs_are_valid_and_inside_the_vpc" {
  assert {
    # B가 A 안에 있으려면 B의 첫 주소가 A 블록에 들어 있고 B의 접두사가 A보다 길거나 같아야 한다.
    condition = try(alltrue([
      for c in [aws_subnet.public.cidr_block, aws_subnet.private[0].cidr_block, aws_subnet.private[1].cidr_block] : (
        cidrsubnet("${cidrhost(c, 0)}/${split("/", var.vpc_cidr)[1]}", 0, 0) == cidrsubnet(var.vpc_cidr, 0, 0)
        && tonumber(split("/", c)[1]) >= tonumber(split("/", var.vpc_cidr)[1])
      )
    ]), false)
    error_message = "세 서브넷 CIDR은 모두 vpc_cidr(${var.vpc_cidr}) 안에 있어야 한다. 지금 값: 공개 ${jsonencode(aws_subnet.public.cidr_block)}, 비공개 ${jsonencode([for s in aws_subnet.private : s.cidr_block])}"
  }

  assert {
    condition = try(alltrue([
      for c in [aws_subnet.public.cidr_block, aws_subnet.private[0].cidr_block, aws_subnet.private[1].cidr_block] : (
        tonumber(split("/", c)[1]) >= 16
        && tonumber(split("/", c)[1]) <= 28
      )
    ]), false)
    error_message = "서브넷 CIDR의 접두사 길이는 /16~/28이어야 한다(AWS 제한). 지금 값: 공개 ${jsonencode(aws_subnet.public.cidr_block)}, 비공개 ${jsonencode([for s in aws_subnet.private : s.cidr_block])}"
  }
}

# 서브넷 CIDR이 서로 겹치는 것을 막는다 — 겹치면 AWS가 두 번째 서브넷 생성을 거부한다(가짜 provider는 통과시킨다).
# 접두사 블록은 서로 포함하거나 아예 떨어져 있어서, 어느 쪽도 상대의 첫 주소를 품지 않으면 겹치지 않는다.
run "subnet_cidrs_do_not_overlap" {
  assert {
    condition = try(alltrue([
      for p in [
        [aws_subnet.public.cidr_block, aws_subnet.private[0].cidr_block],
        [aws_subnet.public.cidr_block, aws_subnet.private[1].cidr_block],
        [aws_subnet.private[0].cidr_block, aws_subnet.private[1].cidr_block],
        ] : (
        cidrsubnet("${cidrhost(p[0], 0)}/${split("/", p[1])[1]}", 0, 0) != cidrsubnet(p[1], 0, 0)
        && cidrsubnet("${cidrhost(p[1], 0)}/${split("/", p[0])[1]}", 0, 0) != cidrsubnet(p[0], 0, 0)
      )
    ]), false)
    error_message = "세 서브넷 CIDR은 서로 겹치면 안 된다. 지금 값: 공개 ${jsonencode(aws_subnet.public.cidr_block)}, 비공개 ${jsonencode([for s in aws_subnet.private : s.cidr_block])}"
  }
}

# 보안 그룹 7개 중 하나라도 다른 VPC에 놓이거나 이름 규칙이 어긋나는 것을 막는다 — 다른 VPC의 그룹은 규칙이 서로를 참조하지 못하고, 이름은 콘솔·로그에서 그룹을 알아보는 유일한 단서다.
# 이름은 "<name>-<역할>"이고 역할의 밑줄은 하이픈이 된다(pipeline_worker → pipeline-worker).
run "security_groups_are_in_the_vpc_with_contract_names" {
  assert {
    condition = alltrue([
      for sg in [aws_security_group.alb, aws_security_group.backend, aws_security_group.pipeline_worker, aws_security_group.ai_engine, aws_security_group.neo4j, aws_security_group.rds, aws_security_group.mq] :
      sg.vpc_id == aws_vpc.main.id
    ])
    error_message = "보안 그룹 7개는 모두 aws_vpc.main에 속해야 한다. VPC id: ${aws_vpc.main.id}, 지금 vpc_id: ${jsonencode({ alb = aws_security_group.alb.vpc_id, backend = aws_security_group.backend.vpc_id, pipeline_worker = aws_security_group.pipeline_worker.vpc_id, ai_engine = aws_security_group.ai_engine.vpc_id, neo4j = aws_security_group.neo4j.vpc_id, rds = aws_security_group.rds.vpc_id, mq = aws_security_group.mq.vpc_id })}"
  }

  assert {
    condition = (
      aws_security_group.alb.name == "whycode-test-alb"
      && aws_security_group.backend.name == "whycode-test-backend"
      && aws_security_group.pipeline_worker.name == "whycode-test-pipeline-worker"
      && aws_security_group.ai_engine.name == "whycode-test-ai-engine"
      && aws_security_group.neo4j.name == "whycode-test-neo4j"
      && aws_security_group.rds.name == "whycode-test-rds"
      && aws_security_group.mq.name == "whycode-test-mq"
    )
    error_message = "보안 그룹 이름은 \"<name>-<역할>\"(밑줄은 하이픈)이어야 한다. 지금 값: ${jsonencode({ alb = aws_security_group.alb.name, backend = aws_security_group.backend.name, pipeline_worker = aws_security_group.pipeline_worker.name, ai_engine = aws_security_group.ai_engine.name, neo4j = aws_security_group.neo4j.name, rds = aws_security_group.rds.name, mq = aws_security_group.mq.name })}"
  }
}

# 보안 그룹에 인라인 ingress·egress가 끼어드는 것을 막는다 — 규칙은 별도 규칙 리소스 두 개에만 있어야 하고, 인라인과 섞으면 apply마다 서로의 규칙을 지운다.
# ingress·egress는 계산되는 집합이라 가짜 provider 아래에서 인라인이 없으면 빈 집합이다(null이어도 없는 것으로 본다).
run "security_groups_have_no_inline_rules" {
  assert {
    condition = alltrue([
      for sg in [aws_security_group.alb, aws_security_group.backend, aws_security_group.pipeline_worker, aws_security_group.ai_engine, aws_security_group.neo4j, aws_security_group.rds, aws_security_group.mq] :
      try(length(sg.ingress), 0) == 0
    ])
    error_message = "보안 그룹에 인라인 ingress가 있으면 안 된다. 지금 값: ${jsonencode({ alb = aws_security_group.alb.ingress, backend = aws_security_group.backend.ingress, pipeline_worker = aws_security_group.pipeline_worker.ingress, ai_engine = aws_security_group.ai_engine.ingress, neo4j = aws_security_group.neo4j.ingress, rds = aws_security_group.rds.ingress, mq = aws_security_group.mq.ingress })}"
  }

  assert {
    condition = alltrue([
      for sg in [aws_security_group.alb, aws_security_group.backend, aws_security_group.pipeline_worker, aws_security_group.ai_engine, aws_security_group.neo4j, aws_security_group.rds, aws_security_group.mq] :
      try(length(sg.egress), 0) == 0
    ])
    error_message = "보안 그룹에 인라인 egress가 있으면 안 된다. 지금 값: ${jsonencode({ alb = aws_security_group.alb.egress, backend = aws_security_group.backend.egress, pipeline_worker = aws_security_group.pipeline_worker.egress, ai_engine = aws_security_group.ai_engine.egress, neo4j = aws_security_group.neo4j.egress, rds = aws_security_group.rds.egress, mq = aws_security_group.mq.egress })}"
  }
}

# 인바운드 규칙이 표(11개)에서 벗어나는 것을 막는다 — 하나라도 더 열리면 의도하지 않은 서비스가 DB·브로커·그래프에 닿고, 하나가 빠지면 호출이 막힌다.
# 개수가 11이고 아래 11개가 각각 있으면, 서로 다른 11개 규칙이 표와 정확히 일치한다(표의 항목은 서로 겹치지 않는다).
# 항목 표기는 "대상 SG <- 출처 SG : 포트"다.
run "ingress_rules_are_exactly_the_eleven_security_group_references" {
  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.this) == 11
    error_message = "인바운드 규칙은 정확히 11개여야 한다. 지금 ${length(aws_vpc_security_group_ingress_rule.this)}개: ${jsonencode({ for k, r in aws_vpc_security_group_ingress_rule.this : k => { target = r.security_group_id, source = r.referenced_security_group_id, cidr_ipv4 = r.cidr_ipv4, cidr_ipv6 = r.cidr_ipv6, prefix_list = r.prefix_list_id, from = r.from_port, to = r.to_port, protocol = r.ip_protocol } })}, 보안 그룹 id: ${jsonencode({ alb = aws_security_group.alb.id, backend = aws_security_group.backend.id, pipeline_worker = aws_security_group.pipeline_worker.id, ai_engine = aws_security_group.ai_engine.id, neo4j = aws_security_group.neo4j.id, rds = aws_security_group.rds.id, mq = aws_security_group.mq.id })}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.referenced_security_group_id == aws_security_group.alb.id
      && r.from_port == 8080
      && r.to_port == 8080
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'backend <- alb : 8080/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.referenced_security_group_id == aws_security_group.pipeline_worker.id
      && r.from_port == 8080
      && r.to_port == 8080
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'backend <- pipeline_worker : 8080/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.referenced_security_group_id == aws_security_group.alb.id
      && r.from_port == 8081
      && r.to_port == 8081
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'pipeline_worker <- alb : 8081/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.referenced_security_group_id == aws_security_group.backend.id
      && r.from_port == 8081
      && r.to_port == 8081
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'pipeline_worker <- backend : 8081/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.ai_engine.id
      && r.referenced_security_group_id == aws_security_group.backend.id
      && r.from_port == 8000
      && r.to_port == 8000
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'ai_engine <- backend : 8000/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.neo4j.id
      && r.referenced_security_group_id == aws_security_group.ai_engine.id
      && r.from_port == 7687
      && r.to_port == 7687
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'neo4j <- ai_engine : 7687/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.rds.id
      && r.referenced_security_group_id == aws_security_group.backend.id
      && r.from_port == 5432
      && r.to_port == 5432
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'rds <- backend : 5432/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.rds.id
      && r.referenced_security_group_id == aws_security_group.pipeline_worker.id
      && r.from_port == 5432
      && r.to_port == 5432
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'rds <- pipeline_worker : 5432/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.mq.id
      && r.referenced_security_group_id == aws_security_group.pipeline_worker.id
      && r.from_port == 5671
      && r.to_port == 5671
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'mq <- pipeline_worker : 5671/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.mq.id
      && r.referenced_security_group_id == aws_security_group.ai_engine.id
      && r.from_port == 5671
      && r.to_port == 5671
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'mq <- ai_engine : 5671/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      r.security_group_id == aws_security_group.mq.id
      && r.referenced_security_group_id == aws_security_group.ai_engine.id
      && r.from_port == 15671
      && r.to_port == 15671
      && r.ip_protocol == "tcp"
    ])
    error_message = "인바운드 규칙 'mq <- ai_engine : 15671/tcp'가 없다. 지금 인바운드 규칙 수: ${length(aws_vpc_security_group_ingress_rule.this)}"
  }
}

# 인바운드가 주소 대역·접두사 목록으로 열리거나 ALB 보안 그룹이 인바운드를 갖는 것을 막는다 — 인바운드는 보안 그룹 참조로만 열어야 인터넷이나 같은 VPC의 다른 자원이 끼어들지 못한다.
# ALB는 내부 ALB라 인바운드가 0개다. CloudFront가 만드는 보안 그룹은 T2가 붙인다.
run "ingress_is_reference_only_and_alb_has_no_ingress" {
  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.this : r.cidr_ipv4 == null])
    error_message = "인바운드 규칙에 cidr_ipv4가 있으면 안 된다(0.0.0.0/0 포함 — 보안 그룹 참조만 허용한다). 지금 값: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.cidr_ipv4 if r.cidr_ipv4 != null])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.this : r.cidr_ipv6 == null])
    error_message = "인바운드 규칙에 cidr_ipv6가 있으면 안 된다(::/0 포함). 지금 값: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.cidr_ipv6 if r.cidr_ipv6 != null])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.this : r.prefix_list_id == null])
    error_message = "인바운드 규칙에 prefix_list_id가 있으면 안 된다(접두사 목록 인바운드는 이 모듈이 열지 않는다). 지금 값: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.prefix_list_id if r.prefix_list_id != null])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.this : r.referenced_security_group_id != null])
    error_message = "모든 인바운드 규칙은 referenced_security_group_id(출처 보안 그룹)가 있어야 한다. 출처가 없는 규칙의 대상 SG id: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.security_group_id if r.referenced_security_group_id == null])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_ingress_rule.this : r.ip_protocol == "tcp"])
    error_message = "모든 인바운드 규칙은 tcp여야 한다. 지금 값: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.ip_protocol])}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_ingress_rule.this : r if r.security_group_id == aws_security_group.alb.id]) == 0
    error_message = "ALB 보안 그룹이 대상인 인바운드 규칙은 없어야 한다(CloudFront 쪽 보안 그룹은 T2가 붙인다). 지금 개수: ${length([for r in aws_vpc_security_group_ingress_rule.this : r if r.security_group_id == aws_security_group.alb.id])}"
  }
}

# 아웃바운드 규칙이 표(15개)에서 벗어나는 것을 막는다 — 보안 그룹은 기본 아웃바운드(전부 허용)를 지우므로 짝이 빠지면 그 호출이 막히고, 하나 더 있으면 나가면 안 되는 길이 열린다.
# 15개 = 인바운드 11개와 짝인 보안 그룹 참조 11개 + 바깥 443 4개. 항목 표기는 "출발 SG -> 목적지 : 포트"다.
run "egress_rules_are_exactly_the_fifteen_expected" {
  assert {
    condition     = length(aws_vpc_security_group_egress_rule.this) == 15
    error_message = "아웃바운드 규칙은 정확히 15개(SG 참조 11 + 바깥 443 4)여야 한다. 지금 ${length(aws_vpc_security_group_egress_rule.this)}개: ${jsonencode({ for k, r in aws_vpc_security_group_egress_rule.this : k => { source = r.security_group_id, target = r.referenced_security_group_id, cidr_ipv4 = r.cidr_ipv4, cidr_ipv6 = r.cidr_ipv6, prefix_list = r.prefix_list_id, from = r.from_port, to = r.to_port, protocol = r.ip_protocol } })}, 보안 그룹 id: ${jsonencode({ alb = aws_security_group.alb.id, backend = aws_security_group.backend.id, pipeline_worker = aws_security_group.pipeline_worker.id, ai_engine = aws_security_group.ai_engine.id, neo4j = aws_security_group.neo4j.id, rds = aws_security_group.rds.id, mq = aws_security_group.mq.id })}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.referenced_security_group_id != null]) == 11
    error_message = "보안 그룹을 참조하는 아웃바운드 규칙은 정확히 11개(인바운드와 짝)여야 한다. 지금 개수: ${length([for r in aws_vpc_security_group_egress_rule.this : r if r.referenced_security_group_id != null])}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.cidr_ipv4 != null]) == 4
    error_message = "cidr_ipv4 아웃바운드 규칙은 정확히 4개(backend·pipeline_worker·ai_engine·neo4j의 443)여야 한다. 지금 개수: ${length([for r in aws_vpc_security_group_egress_rule.this : r if r.cidr_ipv4 != null])}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.alb.id
      && r.referenced_security_group_id == aws_security_group.backend.id
      && r.from_port == 8080
      && r.to_port == 8080
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'alb -> backend : 8080/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.referenced_security_group_id == aws_security_group.backend.id
      && r.from_port == 8080
      && r.to_port == 8080
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'pipeline_worker -> backend : 8080/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.alb.id
      && r.referenced_security_group_id == aws_security_group.pipeline_worker.id
      && r.from_port == 8081
      && r.to_port == 8081
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'alb -> pipeline_worker : 8081/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.referenced_security_group_id == aws_security_group.pipeline_worker.id
      && r.from_port == 8081
      && r.to_port == 8081
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'backend -> pipeline_worker : 8081/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.referenced_security_group_id == aws_security_group.ai_engine.id
      && r.from_port == 8000
      && r.to_port == 8000
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'backend -> ai_engine : 8000/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.ai_engine.id
      && r.referenced_security_group_id == aws_security_group.neo4j.id
      && r.from_port == 7687
      && r.to_port == 7687
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'ai_engine -> neo4j : 7687/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.referenced_security_group_id == aws_security_group.rds.id
      && r.from_port == 5432
      && r.to_port == 5432
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'backend -> rds : 5432/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.referenced_security_group_id == aws_security_group.rds.id
      && r.from_port == 5432
      && r.to_port == 5432
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'pipeline_worker -> rds : 5432/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.referenced_security_group_id == aws_security_group.mq.id
      && r.from_port == 5671
      && r.to_port == 5671
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'pipeline_worker -> mq : 5671/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.ai_engine.id
      && r.referenced_security_group_id == aws_security_group.mq.id
      && r.from_port == 5671
      && r.to_port == 5671
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'ai_engine -> mq : 5671/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.ai_engine.id
      && r.referenced_security_group_id == aws_security_group.mq.id
      && r.from_port == 15671
      && r.to_port == 15671
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'ai_engine -> mq : 15671/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.backend.id
      && r.cidr_ipv4 == "0.0.0.0/0"
      && r.referenced_security_group_id == null
      && r.from_port == 443
      && r.to_port == 443
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'backend -> 0.0.0.0/0 : 443/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.pipeline_worker.id
      && r.cidr_ipv4 == "0.0.0.0/0"
      && r.referenced_security_group_id == null
      && r.from_port == 443
      && r.to_port == 443
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'pipeline_worker -> 0.0.0.0/0 : 443/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.ai_engine.id
      && r.cidr_ipv4 == "0.0.0.0/0"
      && r.referenced_security_group_id == null
      && r.from_port == 443
      && r.to_port == 443
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'ai_engine -> 0.0.0.0/0 : 443/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }

  assert {
    condition = anytrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.security_group_id == aws_security_group.neo4j.id
      && r.cidr_ipv4 == "0.0.0.0/0"
      && r.referenced_security_group_id == null
      && r.from_port == 443
      && r.to_port == 443
      && r.ip_protocol == "tcp"
    ])
    error_message = "아웃바운드 규칙 'neo4j -> 0.0.0.0/0 : 443/tcp'가 없다. 지금 아웃바운드 규칙 수: ${length(aws_vpc_security_group_egress_rule.this)}"
  }
}

# 바깥으로 나가는 길이 443 이외로 열리거나, 네 서비스(backend·pipeline_worker·ai_engine·neo4j) 밖에서 열리는 것을 막는다 — 바깥 API·AWS 엔드포인트용 443만 필요하고, 그 밖의 포트·대역은 유출 경로가 된다.
# DNS(53) 규칙은 필요 없다: 보안 그룹은 VPC DNS 질의를 막지 못한다. 포트 범위가 53을 품는 규칙(0~65535 등)도 같은 이유로 금지한다.
run "egress_to_the_internet_is_https_only_for_four_services" {
  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_egress_rule.this :
      r.cidr_ipv4 == null || (
        r.cidr_ipv4 == "0.0.0.0/0"
        && r.ip_protocol == "tcp"
        && r.from_port == 443
        && r.to_port == 443
        && contains([aws_security_group.backend.id, aws_security_group.pipeline_worker.id, aws_security_group.ai_engine.id, aws_security_group.neo4j.id], r.security_group_id)
      )
    ])
    error_message = "cidr_ipv4가 있는 아웃바운드 규칙은 전부 0.0.0.0/0 · tcp · 443:443이고, 출발 SG는 backend·pipeline_worker·ai_engine·neo4j뿐이어야 한다. 지금 규칙(출발 SG id, cidr, 포트, 프로토콜): ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : [r.security_group_id, r.cidr_ipv4, r.from_port, r.to_port, r.ip_protocol] if r.cidr_ipv4 != null])}, 허용 출발 SG id: ${jsonencode([aws_security_group.backend.id, aws_security_group.pipeline_worker.id, aws_security_group.ai_engine.id, aws_security_group.neo4j.id])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_egress_rule.this : r.cidr_ipv6 == null && r.prefix_list_id == null])
    error_message = "아웃바운드 규칙에 cidr_ipv6·prefix_list_id가 있으면 안 된다. 지금 값: ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : [r.cidr_ipv6, r.prefix_list_id] if r.cidr_ipv6 != null || r.prefix_list_id != null])}"
  }

  assert {
    condition     = alltrue([for r in aws_vpc_security_group_egress_rule.this : r.ip_protocol == "tcp"])
    error_message = "모든 아웃바운드 규칙은 tcp여야 한다(전체 프로토콜 -1·udp 금지). 지금 값: ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : r.ip_protocol])}"
  }

  assert {
    # from_port·to_port가 없으면(전체 포트) 53을 품는 것으로 본다.
    condition     = !anytrue([for r in aws_vpc_security_group_egress_rule.this : try(r.from_port <= 53 && 53 <= r.to_port, true)])
    error_message = "포트 53(DNS)을 품는 아웃바운드 규칙이 있으면 안 된다(SG는 VPC DNS 질의를 막지 못해 필요 없다). 지금 포트 범위: ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : [r.from_port, r.to_port]])}"
  }
}

# ALB·RDS·MQ가 인터넷으로 나가는 길을 얻거나 RDS·MQ가 아웃바운드를 갖는 것을 막는다 — ALB는 backend·pipeline_worker에만 넘기고, 데이터 저장소(RDS)와 브로커(MQ)는 받기만 하고 스스로 연결을 열지 않는다.
run "alb_rds_and_mq_cannot_reach_the_internet" {
  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.alb.id && r.cidr_ipv4 != null]) == 0
    error_message = "ALB 보안 그룹에는 cidr_ipv4 아웃바운드가 없어야 한다(SG 참조만). 지금 값: ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : [r.cidr_ipv4, r.from_port, r.to_port] if r.security_group_id == aws_security_group.alb.id && r.cidr_ipv4 != null])}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.rds.id]) == 0
    error_message = "RDS 보안 그룹에는 아웃바운드가 하나도 없어야 한다. 지금 개수: ${length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.rds.id])}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.mq.id]) == 0
    error_message = "MQ 보안 그룹에는 아웃바운드가 하나도 없어야 한다. 지금 개수: ${length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.mq.id])}"
  }

  assert {
    condition     = length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.alb.id && r.referenced_security_group_id != null]) == 2
    error_message = "ALB 보안 그룹의 아웃바운드는 backend(8080)·pipeline_worker(8081) 둘뿐이어야 한다. 지금 개수: ${length([for r in aws_vpc_security_group_egress_rule.this : r if r.security_group_id == aws_security_group.alb.id])}"
  }
}

# 인바운드와 아웃바운드가 서로 짝이 아닌 것을 막는다 — 보안 그룹은 기본 아웃바운드를 지우므로, 출처 쪽 아웃바운드가 없으면 대상 쪽 인바운드가 열려 있어도 연결이 안 되고, 반대면 쓰이지 않는 규칙이 남는다.
run "every_ingress_rule_has_its_egress_pair_and_back" {
  assert {
    condition = alltrue([
      for i in aws_vpc_security_group_ingress_rule.this : anytrue([
        for e in aws_vpc_security_group_egress_rule.this :
        e.security_group_id == i.referenced_security_group_id
        && e.referenced_security_group_id == i.security_group_id
        && e.from_port == i.from_port
        && e.to_port == i.to_port
        && e.ip_protocol == i.ip_protocol
      ])
    ])
    error_message = "인바운드 규칙마다 같은 포트의 아웃바운드 짝(출처 SG -> 대상 SG)이 있어야 한다. 인바운드 ${length(aws_vpc_security_group_ingress_rule.this)}개, SG 참조 아웃바운드 ${length([for e in aws_vpc_security_group_egress_rule.this : e if e.referenced_security_group_id != null])}개"
  }

  assert {
    condition = alltrue([
      for e in aws_vpc_security_group_egress_rule.this :
      e.referenced_security_group_id == null || anytrue([
        for i in aws_vpc_security_group_ingress_rule.this :
        i.security_group_id == e.referenced_security_group_id
        && i.referenced_security_group_id == e.security_group_id
        && i.from_port == e.from_port
        && i.to_port == e.to_port
        && i.ip_protocol == e.ip_protocol
      ])
    ])
    error_message = "SG 참조 아웃바운드 규칙마다 같은 포트의 인바운드 짝(대상 SG <- 출발 SG)이 있어야 한다. 인바운드 ${length(aws_vpc_security_group_ingress_rule.this)}개, SG 참조 아웃바운드 ${length([for e in aws_vpc_security_group_egress_rule.this : e if e.referenced_security_group_id != null])}개"
  }
}

# 보안 그룹·규칙 설명에 EC2 API가 거부하는 문자(한글 등)나 빈 문자열이 들어가는 것을 막는다 — 가짜 provider는 통과시키고 실제 apply에서야 InvalidParameterValue로 실패한다.
# 허용 문자: 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$* (255자 이하).
run "rule_descriptions_use_only_characters_ec2_accepts" {
  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_ingress_rule.this :
      try(can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", r.description)) && length(r.description) <= 255 && trimspace(r.description) != "", false)
    ])
    error_message = "모든 인바운드 규칙의 description은 비어 있지 않고 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$*만 써야 한다(255자 이하). 지금 값: ${jsonencode([for r in aws_vpc_security_group_ingress_rule.this : r.description])}"
  }

  assert {
    condition = alltrue([
      for r in aws_vpc_security_group_egress_rule.this :
      try(can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", r.description)) && length(r.description) <= 255 && trimspace(r.description) != "", false)
    ])
    error_message = "모든 아웃바운드 규칙의 description은 비어 있지 않고 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$*만 써야 한다(255자 이하). 지금 값: ${jsonencode([for r in aws_vpc_security_group_egress_rule.this : r.description])}"
  }

  assert {
    condition = alltrue([
      for sg in [aws_security_group.alb, aws_security_group.backend, aws_security_group.pipeline_worker, aws_security_group.ai_engine, aws_security_group.neo4j, aws_security_group.rds, aws_security_group.mq] :
      try(can(regex("^[A-Za-z0-9 ._:/()#,@\\[\\]+=;{}!$*&-]+$", sg.description)) && length(sg.description) <= 255 && trimspace(sg.description) != "", false)
    ])
    error_message = "모든 보안 그룹의 description은 비어 있지 않고 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$*만 써야 한다(255자 이하). 지금 값: ${jsonencode({ alb = aws_security_group.alb.description, backend = aws_security_group.backend.description, pipeline_worker = aws_security_group.pipeline_worker.description, ai_engine = aws_security_group.ai_engine.description, neo4j = aws_security_group.neo4j.description, rds = aws_security_group.rds.description, mq = aws_security_group.mq.description })}"
  }
}

# 공개 라우트 테이블의 기본 경로가 인터넷 게이트웨이가 아닌 곳으로 가거나, 인라인 라우트가 끼어드는 것을 막는다 — 공개 서브넷의 NAT가 인터넷에 닿으려면 0.0.0.0/0이 IGW로 가야 한다.
# 라우트는 인라인이 아니라 별도 aws_route 리소스다. 둘을 섞으면 apply마다 라우트가 지워졌다 생긴다.
run "public_route_table_sends_the_default_route_to_the_internet_gateway" {
  assert {
    condition     = aws_internet_gateway.main.vpc_id == aws_vpc.main.id
    error_message = "인터넷 게이트웨이가 aws_vpc.main에 붙어 있지 않다. 지금 vpc_id: ${jsonencode(aws_internet_gateway.main.vpc_id)}, VPC id: ${aws_vpc.main.id}"
  }

  assert {
    condition     = aws_route_table.public.vpc_id == aws_vpc.main.id
    error_message = "공개 라우트 테이블이 aws_vpc.main에 속해 있지 않다. 지금 vpc_id: ${jsonencode(aws_route_table.public.vpc_id)}, VPC id: ${aws_vpc.main.id}"
  }

  assert {
    condition     = aws_route.public_internet.route_table_id == aws_route_table.public.id
    error_message = "공개 기본 경로가 공개 라우트 테이블에 걸려 있지 않다. 지금 route_table_id: ${jsonencode(aws_route.public_internet.route_table_id)}, 공개 테이블 id: ${aws_route_table.public.id}, 비공개 테이블 id: ${aws_route_table.private.id}"
  }

  assert {
    condition     = aws_route.public_internet.destination_cidr_block == "0.0.0.0/0"
    error_message = "공개 기본 경로의 destination_cidr_block은 0.0.0.0/0이어야 한다. 지금 값: ${jsonencode(aws_route.public_internet.destination_cidr_block)}"
  }

  assert {
    condition     = aws_route.public_internet.gateway_id == aws_internet_gateway.main.id
    error_message = "공개 기본 경로는 인터넷 게이트웨이(gateway_id = aws_internet_gateway.main.id)로 가야 한다. 지금 gateway_id: ${jsonencode(aws_route.public_internet.gateway_id)}, IGW id: ${aws_internet_gateway.main.id}, nat_gateway_id: ${jsonencode(aws_route.public_internet.nat_gateway_id)}"
  }

  assert {
    condition     = length(aws_route_table.public.route) == 0
    error_message = "공개 라우트 테이블에 인라인 route가 있으면 안 된다(별도 aws_route 리소스만 쓴다). 지금 값: ${jsonencode(aws_route_table.public.route)}"
  }
}

# 서브넷이 반대쪽 라우트 테이블에 연결되는 것을 막는다 — 비공개 서브넷이 공개 테이블에 붙으면 IGW로 나가는 길이 열리고, 공개 서브넷이 비공개 테이블에 붙으면 NAT가 인터넷에 닿지 못한다.
run "subnets_are_associated_with_the_matching_route_table" {
  assert {
    condition     = aws_route_table_association.public.subnet_id == aws_subnet.public.id && aws_route_table_association.public.route_table_id == aws_route_table.public.id
    error_message = "공개 서브넷은 공개 라우트 테이블에 연결돼야 한다. 지금 subnet_id: ${jsonencode(aws_route_table_association.public.subnet_id)}, route_table_id: ${jsonencode(aws_route_table_association.public.route_table_id)}, 공개 서브넷 id: ${aws_subnet.public.id}, 공개 테이블 id: ${aws_route_table.public.id}, 비공개 테이블 id: ${aws_route_table.private.id}"
  }

  assert {
    condition     = length(aws_route_table_association.private) == 2
    error_message = "비공개 서브넷 연결은 정확히 2개(count = 2)여야 한다. 지금 개수: ${length(aws_route_table_association.private)}"
  }

  assert {
    condition     = try(alltrue([for i, a in aws_route_table_association.private : a.subnet_id == aws_subnet.private[i].id && a.route_table_id == aws_route_table.private.id]), false)
    error_message = "비공개 서브넷 i는 비공개 라우트 테이블에 연결돼야 한다. 지금 연결(subnet_id, route_table_id): ${jsonencode([for a in aws_route_table_association.private : [a.subnet_id, a.route_table_id]])}, 비공개 서브넷 id: ${jsonencode([for s in aws_subnet.private : s.id])}, 비공개 테이블 id: ${aws_route_table.private.id}, 공개 테이블 id: ${aws_route_table.public.id}"
  }
}

# NAT가 비공개 서브넷에 놓이거나 EIP 없이 만들어지는 것을 막는다 — NAT는 인터넷 게이트웨이로 나가는 공개 서브넷에 있어야 하고, 고정 공인 IP(EIP)가 있어야 바깥 API 쪽에서 출구 주소를 허용 목록에 넣을 수 있다.
# EIP 연결은 계약대로 aws_eip.nat.id를 쓰되, 실제 provider에서 같은 값인 allocation_id도 받아 준다(가짜 provider에서는 둘이 다른 무작위 값이다).
run "nat_gateway_sits_in_the_public_subnet_with_an_elastic_ip" {
  assert {
    condition     = aws_nat_gateway.main.subnet_id == aws_subnet.public.id
    error_message = "NAT 게이트웨이는 공개 서브넷(aws_subnet.public)에 있어야 한다. 지금 subnet_id: ${jsonencode(aws_nat_gateway.main.subnet_id)}, 공개 서브넷 id: ${aws_subnet.public.id}, 비공개 서브넷 id: ${jsonencode([for s in aws_subnet.private : s.id])}"
  }

  assert {
    condition     = aws_nat_gateway.main.allocation_id == aws_eip.nat.id || aws_nat_gateway.main.allocation_id == aws_eip.nat.allocation_id
    error_message = "NAT 게이트웨이는 aws_eip.nat에 묶여 있어야 한다(allocation_id = aws_eip.nat.id). 지금 allocation_id: ${jsonencode(aws_nat_gateway.main.allocation_id)}, EIP id: ${aws_eip.nat.id}, EIP allocation_id: ${aws_eip.nat.allocation_id}"
  }

  assert {
    condition     = aws_eip.nat.domain == "vpc"
    error_message = "NAT용 EIP의 domain은 vpc여야 한다. 지금 값: ${jsonencode(aws_eip.nat.domain)}"
  }
}

# 비공개 라우트 테이블의 기본 경로가 NAT가 아닌 곳(특히 인터넷 게이트웨이)으로 가는 것을 막는다 — IGW로 가면 공인 IP 없는 태스크가 인터넷으로 나가지 못하거나, 공인 IP가 붙은 순간 인터넷에 그대로 노출된다.
run "private_route_table_sends_the_default_route_to_the_nat_gateway" {
  assert {
    condition     = aws_route_table.private.vpc_id == aws_vpc.main.id
    error_message = "비공개 라우트 테이블이 aws_vpc.main에 속해 있지 않다. 지금 vpc_id: ${jsonencode(aws_route_table.private.vpc_id)}, VPC id: ${aws_vpc.main.id}"
  }

  assert {
    condition     = aws_route.private_nat.route_table_id == aws_route_table.private.id
    error_message = "비공개 기본 경로가 비공개 라우트 테이블에 걸려 있지 않다. 지금 route_table_id: ${jsonencode(aws_route.private_nat.route_table_id)}, 비공개 테이블 id: ${aws_route_table.private.id}, 공개 테이블 id: ${aws_route_table.public.id}"
  }

  assert {
    condition     = aws_route.private_nat.destination_cidr_block == "0.0.0.0/0"
    error_message = "비공개 기본 경로의 destination_cidr_block은 0.0.0.0/0이어야 한다. 지금 값: ${jsonencode(aws_route.private_nat.destination_cidr_block)}"
  }

  assert {
    condition     = aws_route.private_nat.nat_gateway_id == aws_nat_gateway.main.id
    error_message = "비공개 기본 경로는 NAT 게이트웨이(nat_gateway_id = aws_nat_gateway.main.id)로 가야 한다. 지금 nat_gateway_id: ${jsonencode(aws_route.private_nat.nat_gateway_id)}, NAT id: ${aws_nat_gateway.main.id}, gateway_id: ${jsonencode(aws_route.private_nat.gateway_id)}"
  }

  assert {
    condition     = aws_route.private_nat.gateway_id == null
    error_message = "비공개 기본 경로가 인터넷 게이트웨이로 직행하면 안 된다(gateway_id가 없어야 한다). 지금 gateway_id: ${jsonencode(aws_route.private_nat.gateway_id)}, IGW id: ${aws_internet_gateway.main.id}"
  }

  assert {
    condition     = length(aws_route_table.private.route) == 0
    error_message = "비공개 라우트 테이블에 인라인 route가 있으면 안 된다(별도 aws_route 리소스만 쓴다). 지금 값: ${jsonencode(aws_route_table.private.route)}"
  }
}

# 출력이 엉뚱한 값을 내보내는 것을 막는다 — 이 출력들은 다음 묶음(진입점·데이터·앱)이 서브넷·보안 그룹·NAT 주소를 받아 쓰는 유일한 통로다.
# 목록·맵은 목록 대 튜플, 맵 대 객체의 형식 차이로 같은 값도 다르게 비교되므로 tolist·tomap으로 맞춰 비교한다.
run "outputs_point_to_network_resources" {
  assert {
    condition     = output.vpc_id == aws_vpc.main.id
    error_message = "vpc_id 출력이 aws_vpc.main.id와 다르다. 지금 값: ${jsonencode(output.vpc_id)}, VPC id: ${aws_vpc.main.id}"
  }

  assert {
    condition     = output.public_subnet_id == aws_subnet.public.id
    error_message = "public_subnet_id 출력이 aws_subnet.public.id와 다르다. 지금 값: ${jsonencode(output.public_subnet_id)}, 공개 서브넷 id: ${aws_subnet.public.id}"
  }

  assert {
    condition     = try(tolist(output.private_subnet_ids) == tolist([for s in aws_subnet.private : s.id]), false)
    error_message = "private_subnet_ids 출력이 비공개 서브넷 id 둘(인덱스 순서)과 다르다. 지금 값: ${jsonencode(output.private_subnet_ids)}, 비공개 서브넷 id: ${jsonencode([for s in aws_subnet.private : s.id])}"
  }

  assert {
    condition     = output.nat_gateway_id == aws_nat_gateway.main.id
    error_message = "nat_gateway_id 출력이 aws_nat_gateway.main.id와 다르다. 지금 값: ${jsonencode(output.nat_gateway_id)}, NAT id: ${aws_nat_gateway.main.id}"
  }

  assert {
    condition     = output.nat_public_ip == aws_eip.nat.public_ip
    error_message = "nat_public_ip 출력이 aws_eip.nat.public_ip와 다르다. 지금 값: ${jsonencode(output.nat_public_ip)}, EIP 주소: ${jsonencode(aws_eip.nat.public_ip)}"
  }

  assert {
    condition     = try(toset(keys(tomap(output.security_group_ids))) == toset(["alb", "backend", "pipeline_worker", "ai_engine", "neo4j", "rds", "mq"]) && length(tomap(output.security_group_ids)) == 7, false)
    error_message = "security_group_ids 출력의 키는 정확히 alb·backend·pipeline_worker·ai_engine·neo4j·rds·mq 7개여야 한다. 지금 값: ${jsonencode(output.security_group_ids)}"
  }

  assert {
    condition = try(
      tomap(output.security_group_ids) == tomap({
        alb             = aws_security_group.alb.id
        backend         = aws_security_group.backend.id
        pipeline_worker = aws_security_group.pipeline_worker.id
        ai_engine       = aws_security_group.ai_engine.id
        neo4j           = aws_security_group.neo4j.id
        rds             = aws_security_group.rds.id
        mq              = aws_security_group.mq.id
      }),
      false
    )
    error_message = "security_group_ids 출력의 각 값이 같은 이름의 aws_security_group.<역할>.id와 같아야 한다. 지금 값: ${jsonencode(output.security_group_ids)}, 실제 id: ${jsonencode({ alb = aws_security_group.alb.id, backend = aws_security_group.backend.id, pipeline_worker = aws_security_group.pipeline_worker.id, ai_engine = aws_security_group.ai_engine.id, neo4j = aws_security_group.neo4j.id, rds = aws_security_group.rds.id, mq = aws_security_group.mq.id })}"
  }
}

# 변수가 값에 반영되지 않고 코드에 박힌 값이 쓰이는 것을 막는다 — 기본값으로만 돌면 VPC 대역·AZ·이름 접두사가 하드코딩돼도 위 run은 통과한다.
# 앞 run들이 만든 상태 위에 변수를 바꿔 다시 적용하므로 맨 뒤쪽에 둔다. 바뀐 자원은 가짜 provider가 지웠다 다시 만들며, 교체를 막는 수명 설정(prevent_destroy)을 두면 이 run이 막힌다.
run "custom_variables_flow_into_vpc_subnets_and_names" {
  variables {
    name               = "whycode-custom"
    vpc_cidr           = "10.50.0.0/16"
    availability_zones = ["ap-northeast-2b", "ap-northeast-2d"]
  }

  assert {
    condition     = aws_vpc.main.cidr_block == "10.50.0.0/16"
    error_message = "vpc_cidr를 바꾸면 VPC cidr_block도 바뀌어야 한다. 지금 값: ${jsonencode(aws_vpc.main.cidr_block)}"
  }

  assert {
    condition     = aws_subnet.public.availability_zone == "ap-northeast-2b" && try(alltrue([for i, s in aws_subnet.private : s.availability_zone == ["ap-northeast-2b", "ap-northeast-2d"][i]]), false)
    error_message = "availability_zones를 바꾸면 서브넷 AZ도 바뀌어야 한다(공개 = 첫 번째, 비공개 i = i번째). 지금 AZ: 공개 ${jsonencode(aws_subnet.public.availability_zone)}, 비공개 ${jsonencode([for s in aws_subnet.private : s.availability_zone])}"
  }

  assert {
    condition = try(alltrue([
      for c in [aws_subnet.public.cidr_block, aws_subnet.private[0].cidr_block, aws_subnet.private[1].cidr_block] : (
        cidrsubnet("${cidrhost(c, 0)}/16", 0, 0) == "10.50.0.0/16"
        && tonumber(split("/", c)[1]) >= 16
      )
    ]), false)
    error_message = "vpc_cidr를 바꾸면 서브넷 CIDR도 새 대역(10.50.0.0/16) 안에 있어야 한다. 지금 값: 공개 ${jsonencode(aws_subnet.public.cidr_block)}, 비공개 ${jsonencode([for s in aws_subnet.private : s.cidr_block])}"
  }

  assert {
    condition     = aws_security_group.backend.name == "whycode-custom-backend" && aws_security_group.pipeline_worker.name == "whycode-custom-pipeline-worker"
    error_message = "name을 바꾸면 보안 그룹 이름 접두사도 바뀌어야 한다. 지금 값: ${jsonencode({ backend = aws_security_group.backend.name, pipeline_worker = aws_security_group.pipeline_worker.name })}"
  }
}

# 가용 영역이 1개일 때 변수 검증이 막지 않는 것을 잡는다 — 비공개 서브넷 둘이 서로 다른 AZ에 놓이는 것이 ALB(AZ 2개 필요)와 한 AZ 장애 대비의 전제다.
run "availability_zones_reject_one_zone" {
  command = plan

  variables {
    availability_zones = ["ap-northeast-2a"]
  }

  expect_failures = [var.availability_zones]
}

# 가용 영역이 3개일 때도 막아야 한다 — 서브넷 배치가 AZ 2개를 전제로 짜여 있어, 셋째 AZ는 조용히 무시되거나 인덱스 오류가 된다.
run "availability_zones_reject_three_zones" {
  command = plan

  variables {
    availability_zones = ["ap-northeast-2a", "ap-northeast-2b", "ap-northeast-2c"]
  }

  expect_failures = [var.availability_zones]
}

# 빈 목록도 막아야 한다 — 길이 검사가 "1 이상"처럼 느슨해지면 서브넷 인덱스 오류가 plan 한복판에서야 드러난다.
run "availability_zones_reject_empty_list" {
  command = plan

  variables {
    availability_zones = []
  }

  expect_failures = [var.availability_zones]
}
