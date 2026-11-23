# T2 내부 ALB 모듈(ALB 1·대상 그룹 2·HTTP 리스너 1·경로 규칙 7)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/modules/alb test
#
# 이 모듈의 규칙이 "어떤 경로가 어느 서비스로 가고 어떤 경로가 막히는가"의 전부다(이 ALB는 CloudFront가 VPC 원본으로만 부른다).
# 그래서 검사의 중심은 두 가지다.
#   1. 막아야 할 경로가 열리지 않는다 — pipeline-worker의 인증 없는 /api/v1/raw·/api/v1/collect, backend 내부 경로 /api/v1/internal,
#      github 밖의 웹훅, 끝 슬래시가 붙은 /mcp/. 규칙에 안 걸린 요청은 기본 동작이 JSON 404로 끝낸다.
#   2. 허용 경로는 올바른 서비스로만 간다 — worker로 가는 길은 POST /api/v1/webhook/github 하나뿐이고, backend는 4개 경로만 받는다.
# 규칙은 번호가 낮은 것부터 평가된다. 그래서 글자가 맞아도 번호 순서가 깨지면 길이 열린다(backend 규칙이 차단 규칙보다 앞서면
# /api/v1/raw/*가 backend로 간다). 규칙 내용을 하나씩 대조하는 run과 별개로, 규칙을 ALB처럼 평가해 요청 표를 돌려 보는 run(라우팅 모의)이 순서까지 본다.
#
# 읽는 법
# - 가짜 provider는 계산 속성(arn·id 등)을 apply 단계에서만 채운다. 그래서 run은 기본값 command = apply로 두고, 소스 검사·변수 검증 run만 plan이다.
# - 가짜 provider가 ARN 형식을 검사하는 인자(load_balancer_arn·listener_arn·target_group_arn)에 무작위 8글자를 넣으면 apply가 "is an invalid ARN"으로
#   끝나 뒤 run이 전부 skip된다. 그래서 아래 override_resource가 자원마다 형식을 갖춘 서로 다른 ARN을 준다. 서로 달라야 하는 이유는 두 대상 그룹의 ARN이
#   같으면 규칙이 어느 그룹으로 가는지 구분할 수 없기 때문이다(mock_resource의 defaults는 같은 타입 전부에 같은 값을 줘서 쓰지 못한다).
# - 테스트 파일에는 locals를 쓸 수 없다. 규칙은 for_each 키 이름이 아니라 내용(경로 집합)으로 찾고, 같은 조건식을 run마다 인라인으로 적는다.
#   path_pattern의 values는 문자열 집합이라 toset으로 맞춰 비교하고, condition 블록을 하나에 몰든 나누든 flatten이 합친다.
# - run이 assert 실패가 아니라 오류로 끝나면 뒤 run이 전부 skip된다. 값이 없을 수 있는 곳은 try()로 감싸 실패로 끝나게 하고,
#   오류로 끝나는 변수 검증 run(expect_failures)은 맨 뒤에 둔다.
# - 가짜 provider도 provider 자체의 인자 검사(이름의 문자·32자 길이, 본문 1024자 등)는 plan에서 한다. 그런 값이 틀리면 첫 run(plan)이 오류로 끝나고
#   뒤 run이 전부 skip된다. provider가 모르는 것(본문이 JSON인가, 하이픈으로 시작·끝나는 이름)은 아래 run이 직접 검사한다.
# - 모듈 소스에 선언된 자원 주소를 계약의 5개와 대조하는 첫 run만 정규식으로 소스를 읽는다(그 run만 소스 꼴에 기댄다).

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
mock_provider "aws" {}

override_resource {
  target = aws_lb.main
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/whycode-test-alb/50dc6c495c0c9188"
  }
}

override_resource {
  target = aws_lb_target_group.backend
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/whycode-test-backend/6d0ecf831eec9f09"
  }
}

override_resource {
  target = aws_lb_target_group.pipeline_worker
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:targetgroup/whycode-test-worker/73e2d6bc24d8a067"
  }
}

override_resource {
  target = aws_lb_listener.http
  values = {
    arn = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:listener/app/whycode-test-alb/50dc6c495c0c9188/f2f7dc8efc522ab2"
  }
}

# 필수 변수. idle_timeout_seconds·deregistration_delay_seconds·health_check_path는 기본값으로 돌리고, 바꿔 넣어 보는 run은 따로 있다.
variables {
  name              = "whycode-test"
  vpc_id            = "vpc-0123456789abcdef0"
  security_group_id = "sg-0123456789abcdef0"
  subnet_ids        = ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1"]
}

# ─────────────────────────────────────────────────────────────────────────────
# 0. 계약 밖 자원과 전제
# ─────────────────────────────────────────────────────────────────────────────

# 계약에 없는 자원이 덧붙는 것을 막는다 — 아래 run들은 계약에 적힌 주소의 자원만 볼 수 있어서, HTTPS 리스너·두 번째 리스너·WAF·다른 이름의 규칙을
# 몰래 끼워 넣어 길을 열어도 통과해 버린다. 모듈 소스에 선언된 자원 주소를 계약의 5개와 직접 대조한다.
# 소스를 정규식으로 읽으므로 자원은 파일에 resource "타입" "이름" 꼴로 쓴다. 자원을 일부러 늘릴 때는 이 목록과 해당 검사를 함께 고친다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_lb.main",
      "aws_lb_target_group.backend",
      "aws_lb_target_group.pipeline_worker",
      "aws_lb_listener.http",
      "aws_lb_listener_rule.this",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 5개와 정확히 같아야 한다(덧붙은 자원도, 빠진 자원도 안 된다). HTTPS 리스너·두 번째 리스너·WAF·추가 규칙은 특히 안 된다. 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
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

# ALB·대상 그룹·리스너가 count·for_each로 늘어나는 것을 막는다 — 늘어나면 aws_lb.main.arn 같은 계약 주소가 깨지고, 규칙이 어느 인스턴스를 가리키는지 모호해진다.
# 단일 인스턴스는 tolist·tomap으로 바뀌지 않고, count는 tolist로, for_each는 tomap으로 바뀐다. 이 방식이면 늘어나도 참조 오류가 아니라 이 검사의 실패로 끝난다.
run "load_balancer_target_groups_and_listener_are_single_instances" {
  assert {
    condition = alltrue([
      for x in [aws_lb.main, aws_lb_target_group.backend, aws_lb_target_group.pipeline_worker, aws_lb_listener.http] :
      !can(tolist(x)) && !can(tomap(x))
    ])
    error_message = "aws_lb.main·aws_lb_target_group.backend·aws_lb_target_group.pipeline_worker·aws_lb_listener.http는 각각 하나뿐이어야 한다(count·for_each 금지). count 형태인 것: ${jsonencode({ lb = can(tolist(aws_lb.main)), backend = can(tolist(aws_lb_target_group.backend)), pipeline_worker = can(tolist(aws_lb_target_group.pipeline_worker)), listener = can(tolist(aws_lb_listener.http)) })}"
  }
}

# 가짜 provider(와 override_resource)가 서로 다른 자원에 같은 ARN을 주면 아래의 "규칙이 어느 대상 그룹으로 가는가" 비교가 아무것도 가리지 못한다. 그 전제를 먼저 확인한다.
# arn_suffix·dns_name은 출력 비교(서로 바꿔 쓴 출력)가 값으로 구분되게 하려는 전제다.
run "mock_arns_are_well_formed_and_distinct" {
  assert {
    condition = one([
      for ids in [[aws_lb.main.arn, aws_lb_target_group.backend.arn, aws_lb_target_group.pipeline_worker.arn, aws_lb_listener.http.arn]] :
      length(toset(ids)) == 4 && alltrue([for a in ids : startswith(a, "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:")])
    ])
    error_message = "ARN 넷(ALB·대상 그룹 둘·리스너)은 형식을 갖추고 서로 달라야 이 파일의 비교가 의미 있다. ALB: ${aws_lb.main.arn}, backend: ${aws_lb_target_group.backend.arn}, worker: ${aws_lb_target_group.pipeline_worker.arn}, 리스너: ${aws_lb_listener.http.arn}"
  }

  assert {
    condition     = length(toset([aws_lb.main.arn_suffix, aws_lb.main.dns_name, aws_lb.main.arn])) == 3
    error_message = "ALB의 arn_suffix·dns_name·arn이 서로 달라야 출력 비교가 값을 가려낸다. arn_suffix: ${aws_lb.main.arn_suffix}, dns_name: ${aws_lb.main.dns_name}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. ALB
# ─────────────────────────────────────────────────────────────────────────────

# ALB가 인터넷에 열리거나(internal = false) 종류·이름이 바뀌는 것을 막는다 — 이 ALB는 CloudFront가 VPC 원본으로만 부르는 문이라,
# 공개되면 CloudFront를 건너뛰고 인증 없는 경로 차단 규칙만 믿고 인터넷에 서게 된다. 이름은 "<name>-alb"로 콘솔·로그에서 알아보는 단서다.
run "alb_is_an_internal_application_load_balancer_with_the_contract_name" {
  assert {
    condition     = aws_lb.main.internal == true
    error_message = "ALB의 internal은 true여야 한다(인터넷에 열리면 안 된다). 지금 값: ${jsonencode(aws_lb.main.internal)}"
  }

  assert {
    condition     = aws_lb.main.load_balancer_type == "application"
    error_message = "ALB의 load_balancer_type은 application이어야 한다(경로 규칙은 L7에서만 쓴다). 지금 값: ${jsonencode(aws_lb.main.load_balancer_type)}"
  }

  assert {
    condition     = aws_lb.main.name == "whycode-test-alb"
    error_message = "ALB 이름은 \"<name>-alb\"여야 한다. 지금 값: ${jsonencode(aws_lb.main.name)}"
  }
}

# ALB가 변수로 받은 보안 그룹·서브넷에 붙지 않는 것을 막는다 — 보안 그룹이 빠지면 VPC 기본 그룹이 붙어 CloudFront·ECS 사이 허용 경로가 어긋나고,
# 서브넷이 하나뿐이거나 다른 서브넷이면 AZ 2개 조건을 못 채우거나 다른 네트워크에 놓인다.
run "alb_uses_the_given_security_group_and_two_subnets" {
  assert {
    condition     = toset(aws_lb.main.security_groups) == toset([var.security_group_id])
    error_message = "ALB의 security_groups는 변수 security_group_id 하나여야 한다. 지금 값: ${jsonencode(aws_lb.main.security_groups)}, 변수: ${var.security_group_id}"
  }

  assert {
    condition     = toset(aws_lb.main.subnets) == toset(var.subnet_ids) && length(aws_lb.main.subnets) == 2
    error_message = "ALB의 subnets는 변수 subnet_ids 둘과 같아야 한다. 지금 값: ${jsonencode(aws_lb.main.subnets)}, 변수: ${jsonencode(var.subnet_ids)}"
  }
}

# ALB 보호 설정이 꺼지거나 idle 타임아웃이 짧아지는 것을 막는다.
# drop_invalid_header_fields가 꺼지면 형식이 틀린 헤더가 그대로 backend에 닿고, preserve_host_header가 꺼지면 ALB가 Host 헤더를 손봐서 넘길 수 있어
# backend가 요청의 Host로 만드는 주소(OAuth 발급자·리다이렉트)가 공개 도메인과 어긋날 수 있다. 삭제 방지가 켜지면 1주 실험 뒤 terraform destroy가 막힌다.
# idle 130초는 backend가 ai-engine 응답을 최대 120초 기다리기 때문이다 — 그보다 짧으면 ALB가 먼저 연결을 끊어 504가 난다.
run "alb_hardening_flags_and_default_idle_timeout" {
  assert {
    condition     = aws_lb.main.drop_invalid_header_fields == true
    error_message = "ALB의 drop_invalid_header_fields는 true여야 한다. 지금 값: ${jsonencode(aws_lb.main.drop_invalid_header_fields)}"
  }

  assert {
    condition     = aws_lb.main.preserve_host_header == true
    error_message = "ALB의 preserve_host_header는 true여야 한다. 지금 값: ${jsonencode(aws_lb.main.preserve_host_header)}"
  }

  assert {
    condition     = aws_lb.main.xff_header_processing_mode == "remove"
    error_message = "ALB는 클라이언트가 보낸 X-Forwarded-For를 제거해야 한다."
  }

  assert {
    condition     = aws_lb.main.enable_deletion_protection == false
    error_message = "ALB의 enable_deletion_protection은 false여야 한다(1주 실험 뒤 destroy가 막힌다). 지금 값: ${jsonencode(aws_lb.main.enable_deletion_protection)}"
  }

  assert {
    condition     = aws_lb.main.idle_timeout == 130
    error_message = "idle_timeout_seconds를 주지 않으면 ALB idle_timeout은 기본값 130이어야 한다(질의 최대 120초보다 길게). 지금 값: ${jsonencode(aws_lb.main.idle_timeout)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 2. 대상 그룹
# ─────────────────────────────────────────────────────────────────────────────

# 대상 그룹의 이름·포트·종류가 어긋나는 것을 막는다 — 포트가 뒤바뀌면 backend 규칙이 worker 앱으로, worker 규칙이 backend 앱으로 가고(둘 다 200을 줄 수도 있어 눈에 안 띈다),
# target_type이 ip가 아니면 Fargate 태스크를 등록하지 못한다. 두 그룹의 포트가 같으면 한쪽 서비스가 헬스 체크를 통과하지 못한다.
run "target_groups_have_contract_names_ports_and_ip_targets" {
  assert {
    condition     = aws_lb_target_group.backend.name == "whycode-test-backend" && aws_lb_target_group.pipeline_worker.name == "whycode-test-worker"
    error_message = "대상 그룹 이름은 \"<name>-backend\"·\"<name>-worker\"여야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.name, pipeline_worker = aws_lb_target_group.pipeline_worker.name })}"
  }

  assert {
    condition     = aws_lb_target_group.backend.port == 8080 && aws_lb_target_group.pipeline_worker.port == 8081
    error_message = "backend 대상 그룹은 8080, pipeline-worker 대상 그룹은 8081이어야 한다(서로 달라야 한다). 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.port, pipeline_worker = aws_lb_target_group.pipeline_worker.port })}"
  }

  assert {
    condition = alltrue([
      for g in [aws_lb_target_group.backend, aws_lb_target_group.pipeline_worker] :
      g.protocol == "HTTP" && g.target_type == "ip" && g.vpc_id == var.vpc_id
    ])
    error_message = "두 대상 그룹은 protocol HTTP·target_type ip이고 변수 vpc_id의 VPC에 있어야 한다. 지금 값: ${jsonencode({ backend = { protocol = aws_lb_target_group.backend.protocol, target_type = aws_lb_target_group.backend.target_type, vpc_id = aws_lb_target_group.backend.vpc_id }, pipeline_worker = { protocol = aws_lb_target_group.pipeline_worker.protocol, target_type = aws_lb_target_group.pipeline_worker.target_type, vpc_id = aws_lb_target_group.pipeline_worker.vpc_id } })}, 변수: ${var.vpc_id}"
  }
}

# 헬스 체크가 어긋나는 것을 막는다 — 경로·매처가 틀리면 멀쩡한 태스크가 unhealthy로 빠져 ALB가 503을 내고, 꺼지면 죽은 태스크로도 요청이 간다.
# interval이 timeout보다 길어야 AWS가 받아 준다. 등록 해제 지연(30초)은 배포 때 진행 중 요청을 마칠 시간이다(기본 300초면 롤링 배포가 5분씩 늘어진다).
run "target_group_health_checks_follow_the_contract" {
  assert {
    condition = try(alltrue([
      for g in [aws_lb_target_group.backend, aws_lb_target_group.pipeline_worker] : (
        length(g.health_check) == 1
        && g.health_check[0].enabled == true
        && g.health_check[0].path == "/actuator/health/readiness"
        && g.health_check[0].port == "traffic-port"
        && g.health_check[0].protocol == "HTTP"
        && g.health_check[0].matcher == "200"
        && g.health_check[0].interval == 15
        && g.health_check[0].timeout == 5
        && g.health_check[0].healthy_threshold == 2
        && g.health_check[0].unhealthy_threshold == 3
      )
    ]), false)
    error_message = "두 대상 그룹의 health_check는 enabled true·path /actuator/health/readiness·port traffic-port·protocol HTTP·matcher 200·interval 15·timeout 5·healthy 2·unhealthy 3이어야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.health_check, pipeline_worker = aws_lb_target_group.pipeline_worker.health_check })}"
  }

  assert {
    condition = try(alltrue([
      for g in [aws_lb_target_group.backend, aws_lb_target_group.pipeline_worker] : g.health_check[0].interval > g.health_check[0].timeout
    ]), false)
    error_message = "헬스 체크 interval은 timeout보다 커야 한다(AWS 제한). 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.health_check, pipeline_worker = aws_lb_target_group.pipeline_worker.health_check })}"
  }

  assert {
    condition     = aws_lb_target_group.backend.deregistration_delay == "30" && aws_lb_target_group.pipeline_worker.deregistration_delay == "30"
    error_message = "deregistration_delay_seconds를 주지 않으면 두 대상 그룹의 deregistration_delay는 문자열 \"30\"이어야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.deregistration_delay, pipeline_worker = aws_lb_target_group.pipeline_worker.deregistration_delay })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 3. 리스너와 이름·본문
# ─────────────────────────────────────────────────────────────────────────────

# 리스너의 기본 동작이 서비스로 넘기는 것을 막는다 — 기본 동작이 backend로 forward하면 규칙에 안 걸린 모든 요청(/actuator, /swagger 등 열지 않은 경로)이 backend로 가고,
# 404가 아닌 상태를 내면 막힌 경로가 막힌 줄 모르고 지나간다. 규칙에 안 걸린 요청은 전부 JSON 404다. 리스너는 HTTP 80 하나이고 ALB에 붙는다(TLS는 CloudFront 쪽에서 끝난다).
run "listener_is_http_80_and_answers_unmatched_requests_with_json_404" {
  assert {
    condition     = aws_lb_listener.http.load_balancer_arn == aws_lb.main.arn
    error_message = "리스너는 aws_lb.main에 붙어야 한다. 지금 load_balancer_arn: ${jsonencode(aws_lb_listener.http.load_balancer_arn)}, ALB ARN: ${aws_lb.main.arn}"
  }

  assert {
    condition     = aws_lb_listener.http.port == 80 && aws_lb_listener.http.protocol == "HTTP"
    error_message = "리스너는 HTTP 80이어야 한다. 지금 값: ${jsonencode({ port = aws_lb_listener.http.port, protocol = aws_lb_listener.http.protocol })}"
  }

  assert {
    condition = try(
      length(aws_lb_listener.http.default_action) == 1
      && aws_lb_listener.http.default_action[0].type == "fixed-response"
      && length(aws_lb_listener.http.default_action[0].fixed_response) == 1
      && aws_lb_listener.http.default_action[0].fixed_response[0].status_code == "404"
      && aws_lb_listener.http.default_action[0].fixed_response[0].content_type == "application/json"
      && aws_lb_listener.http.default_action[0].fixed_response[0].message_body == "{\"error\":\"not_found\"}",
      false
    )
    error_message = "리스너의 기본 동작은 하나이고 fixed-response(404, application/json, {\"error\":\"not_found\"})여야 한다. 지금 값: ${jsonencode(aws_lb_listener.http.default_action)}"
  }

  assert {
    condition = try(
      aws_lb_listener.http.default_action[0].type != "forward"
      && length(aws_lb_listener.http.default_action[0].forward) == 0
      && aws_lb_listener.http.default_action[0].target_group_arn == null,
      false
    )
    error_message = "리스너의 기본 동작은 어느 대상 그룹으로도 넘기면 안 된다(forward 금지). 지금 값: ${jsonencode(aws_lb_listener.http.default_action)}, backend 그룹: ${aws_lb_target_group.backend.arn}, worker 그룹: ${aws_lb_target_group.pipeline_worker.arn}"
  }
}

# AWS가 거부하는 이름·본문을 막는다 — 가짜 provider는 걸러 주지 못해 실제 apply에서야 실패한다.
# ALB·대상 그룹 이름은 32자 이하의 영문·숫자·하이픈이고 하이픈으로 시작하거나 끝날 수 없다. 고정 응답 본문은 1024바이트 이하여야 하고,
# JSON이어야 클라이언트가 오류를 파싱한다(ASCII만 쓰면 글자 수가 바이트 수와 같다).
run "names_and_fixed_response_bodies_are_accepted_by_aws" {
  assert {
    condition = alltrue([
      for n in [aws_lb.main.name, aws_lb_target_group.backend.name, aws_lb_target_group.pipeline_worker.name] :
      can(regex("^[A-Za-z0-9-]{1,32}$", n)) && !startswith(n, "-") && !endswith(n, "-")
    ])
    error_message = "ALB·대상 그룹 이름은 영문·숫자·하이픈 1~32자여야 하고 하이픈으로 시작·끝나면 안 된다. 지금 값: ${jsonencode([aws_lb.main.name, aws_lb_target_group.backend.name, aws_lb_target_group.pipeline_worker.name])}"
  }

  assert {
    condition = try(alltrue([
      for b in concat(
        [for d in aws_lb_listener.http.default_action : d.fixed_response[0].message_body],
        [for r in aws_lb_listener_rule.this : r.action[0].fixed_response[0].message_body if r.action[0].type == "fixed-response"],
      ) : can(jsondecode(b)) && length(b) <= 1024 && can(regex("^[ -~]+$", b))
    ]), false)
    error_message = "고정 응답 본문은 유효한 JSON이고 1024바이트 이하의 ASCII여야 한다. 지금 본문: ${jsonencode(concat([for d in aws_lb_listener.http.default_action : try(d.fixed_response[0].message_body, null)], [for r in aws_lb_listener_rule.this : try(r.action[0].fixed_response[0].message_body, null)]))}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 4. 경로 규칙
# ─────────────────────────────────────────────────────────────────────────────

# 규칙이 7개에서 늘거나 줄거나, 다른 리스너에 붙거나, 우선순위가 계약과 다르거나, 경로·메서드 밖의 조건이 섞이는 것을 막는다.
# 우선순위: 차단 10~12 → github 웹훅 허용 20 → 나머지 웹훅 차단 21 → backend 30~31.
# (차단이 backend보다 앞서야 하고, github 허용이 나머지 웹훅 차단보다 앞서야 하며, 그 차단도 backend보다 앞서야 한다.)
# 호스트·헤더·쿼리·IP 조건이 섞이면 규칙이 좁아져, 차단 규칙은 조건을 피한 요청이 backend 규칙으로 빠진다. 규칙은 키 이름이 아니라 경로 집합으로 찾는다.
run "rules_on_the_http_listener_have_contract_priorities_and_respect_aws_limits" {
  assert {
    condition     = try(length([for r in aws_lb_listener_rule.this : r]) == 7, false)
    error_message = "리스너 규칙은 정확히 7개여야 한다. 지금 우선순위: ${jsonencode(try([for r in aws_lb_listener_rule.this : r.priority], "규칙이 목록·맵이 아니다"))}"
  }

  assert {
    condition = try({
      for r in aws_lb_listener_rule.this : join(" ", sort(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]))) => r.priority
      } == {
      "/api/v1/collect/* /api/v1/internal/* /api/v1/raw/*" = 10
      "/mcp/"                                              = 11
      "/api/v1/collect /api/v1/internal /api/v1/raw"       = 12
      "/api/v1/webhook/github"                             = 20
      "/api/v1/webhook /api/v1/webhook/*"                  = 21
      "/.well-known/* /api/* /oauth2/*"                    = 30
      "/mcp"                                               = 31
    }, false)
    error_message = "규칙 우선순위는 내부 하위 경로 10, /mcp/ 11, 내부 루트 12, github 웹훅 허용 20, 다른 웹훅 차단 21, backend API 30, MCP 31이어야 한다. 지금 값: ${jsonencode(try({ for r in aws_lb_listener_rule.this : join(" ", sort(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]))) => r.priority }, "규칙이 목록·맵이 아니다"))}"
  }

  assert {
    condition = try(alltrue([
      for r in aws_lb_listener_rule.this : (
        r.listener_arn == aws_lb_listener.http.arn
        && length(r.action) == 1
        && alltrue([for c in r.condition : length(c.host_header) + length(c.http_header) + length(c.query_string) + length(c.source_ip) == 0])
      )
    ]), false)
    error_message = "모든 규칙은 aws_lb_listener.http에 붙고, 동작이 하나이며, 경로·메서드 조건만 가져야 한다. 지금 리스너: ${jsonencode([for r in aws_lb_listener_rule.this : r.listener_arn])}, 기대: ${aws_lb_listener.http.arn}, 동작 수: ${jsonencode([for r in aws_lb_listener_rule.this : length(r.action)])}, 조건: ${jsonencode([for r in aws_lb_listener_rule.this : r.condition])}"
  }

  assert {
    condition = alltrue([
      for r in aws_lb_listener_rule.this : (
        length(flatten([for c in r.condition : c.path_pattern])) == 1
        && alltrue([for c in r.condition : alltrue([for p in c.path_pattern : contains([1, 2, 3], try(length(p.values), 0)) && try(length(p.regex_values), 0) == 0])])
      )
    ])
    error_message = "AWS ALB 규칙에는 경로 조건 하나와 그 조건의 값 최대 세 개만 둘 수 있다."
  }
}

# 인증 없는 내부 경로 하나가 차단에서 빠지거나 차단 규칙이 서비스로 넘기거나 상태가 바뀌는 것을 막는다 — /api/v1/raw/*·/api/v1/collect/*는 인증 없는 pipeline-worker 경로,
# /api/v1/internal/*는 서비스 간 호출용 backend 경로다. 메서드 조건이 없어야 GET·POST 어느 쪽으로도 못 들어온다(조건이 있으면 나머지 메서드가 backend 규칙으로 빠진다).
run "internal_paths_are_blocked_with_a_json_404" {
  assert {
    condition = length([
      for r in aws_lb_listener_rule.this : r
      if contains([
        "/api/v1/collect/* /api/v1/internal/* /api/v1/raw/*",
        "/api/v1/collect /api/v1/internal /api/v1/raw",
      ], join(" ", sort(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]))))
      && length(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == 0
      && r.action[0].type == "fixed-response"
      && r.action[0].target_group_arn == null
      && try(r.action[0].fixed_response[0].status_code, "") == "404"
      && try(r.action[0].fixed_response[0].content_type, "") == "application/json"
      && try(r.action[0].fixed_response[0].message_body, "") == "{\"error\":\"not_found\"}"
    ]) == 2
    error_message = "내부 경로 세 가지는 루트와 하위 경로 모두 메서드와 무관하게 같은 JSON 404를 응답해야 한다."
  }
}

# /mcp/(끝 슬래시)가 backend로 넘어가거나 안내 없이 끝나는 것을 막는다 — MCP 엔드포인트는 /mcp뿐이라 /mcp/는 404로 끝내야 하고, 에이전트가 원인을 알 수 있게 바른 주소를 JSON으로 알려 준다.
# backend로 넘기면 인가 서버의 OAuth resource(끝 슬래시 없는 주소)와 어긋난다. 경로는 정확히 /mcp/ 하나다(와일드카드 없음).
run "mcp_trailing_slash_gets_a_json_404_with_the_correct_address" {
  assert {
    condition = length([
      for r in aws_lb_listener_rule.this : r
      if toset(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]])) == toset(["/mcp/"])
      && length(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == 0
      && r.action[0].type == "fixed-response"
      && r.action[0].target_group_arn == null
      && try(r.action[0].fixed_response[0].status_code, "") == "404"
      && try(r.action[0].fixed_response[0].content_type, "") == "application/json"
      && try(r.action[0].fixed_response[0].message_body, "") == "{\"error\":\"not_found\",\"message\":\"The MCP endpoint is /mcp (no trailing slash).\"}"
    ]) == 1
    error_message = "경로가 /mcp/ 하나뿐이고 fixed-response(404, application/json, {\"error\":\"not_found\",\"message\":\"The MCP endpoint is /mcp (no trailing slash).\"})인 규칙이 정확히 하나 있어야 한다. 지금 규칙들: ${jsonencode([for r in aws_lb_listener_rule.this : { priority = r.priority, paths = flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]), methods = flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]]), type = r.action[0].type, target = r.action[0].target_group_arn, fixed = r.action[0].fixed_response }])}"
  }
}

# GitHub 웹훅 허용이 넓어지거나 엉뚱한 곳으로 가는 것을 막는다 — pipeline-worker에서 밖에 여는 것은 POST /api/v1/webhook/github 하나뿐이다.
# 와일드카드가 붙으면(/api/v1/webhook/*) 다른 웹훅 경로가 worker로 가고, 메서드 조건이 빠지거나 POST가 아니면 GET 같은 요청이 worker에 닿으며, 대상이 backend면 웹훅이 엉뚱한 앱으로 간다.
run "github_webhook_is_post_only_exact_path_to_the_worker" {
  assert {
    condition = length([
      for r in aws_lb_listener_rule.this : r
      if toset(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]])) == toset(["/api/v1/webhook/github"])
      && toset(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == toset(["POST"])
      && r.action[0].type == "forward"
      && r.action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn
      && length(r.action[0].fixed_response) == 0
      && length(r.action[0].forward) == 0
    ]) == 1
    error_message = "경로가 정확히 /api/v1/webhook/github(와일드카드 없음) 하나이고 메서드가 POST뿐이며 target_group_arn으로 pipeline-worker 대상 그룹(${aws_lb_target_group.pipeline_worker.arn}) 하나에만 forward하는 규칙(가중치 forward 블록 없음)이 정확히 하나 있어야 한다. 지금 규칙들: ${jsonencode([for r in aws_lb_listener_rule.this : { priority = r.priority, paths = flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]), methods = flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]]), type = r.action[0].type, target = r.action[0].target_group_arn }])}"
  }
}

# github 밖의 웹훅이 열리는 것을 막는다 — 이 규칙이 없으면 /api/v1/webhook/slack 같은 경로와 GET /api/v1/webhook/github가 backend 규칙(/api/*)으로 빠진다.
# worker가 다른 웹훅을 받으려면 따로 열기로 결정해야 하고, 그때까지는 JSON 404다.
run "other_webhook_paths_are_blocked_with_a_json_404" {
  assert {
    condition = length([
      for r in aws_lb_listener_rule.this : r
      if toset(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]])) == toset(["/api/v1/webhook", "/api/v1/webhook/*"])
      && length(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == 0
      && r.action[0].type == "fixed-response"
      && r.action[0].target_group_arn == null
      && try(r.action[0].fixed_response[0].status_code, "") == "404"
      && try(r.action[0].fixed_response[0].content_type, "") == "application/json"
      && try(r.action[0].fixed_response[0].message_body, "") == "{\"error\":\"not_found\"}"
    ]) == 1
    error_message = "웹훅 루트와 허용 규칙에 걸리지 않은 하위 경로는 같은 JSON 404를 응답해야 한다."
  }
}

# backend가 받는 경로가 빠지거나 넓어지는 것을 막는다 — /api/*·/mcp(끝 슬래시 없는 정확한 경로)·/oauth2/*·/.well-known/* 넷뿐이다.
# /oauth2/*·/.well-known/*가 빠지면 MCP 클라이언트가 인가 서버를 찾지 못하고, /mcp가 빠지면 에이전트가 붙지 못한다. /mcp/·/mcp*나 /*처럼 넓은 경로가 들어가면
# SPA 공개 페이지(/mcp/setup)와 열지 않은 경로까지 backend로 간다.
run "backend_receives_exactly_the_four_paths" {
  assert {
    condition = length([
      for r in aws_lb_listener_rule.this : r
      if contains([
        "/.well-known/* /api/* /oauth2/*",
        "/mcp",
      ], join(" ", sort(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]))))
      && length(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == 0
      && r.action[0].type == "forward"
      && r.action[0].target_group_arn == aws_lb_target_group.backend.arn
      && length(r.action[0].fixed_response) == 0
      && length(r.action[0].forward) == 0
    ]) == 2
    error_message = "일반 API 규칙과 /mcp 규칙은 메서드와 무관하게 backend 대상 그룹 하나로만 전달해야 한다."
  }
}

# 규칙 표가 나중에 바뀌어도 지켜야 하는 부정 불변식이다 — 위 run들은 계약 표의 규칙을 하나씩 대조하지만, 여기서는 "어느 규칙이든" 서비스로 가는 길을 센다.
# 1) worker로 가는 규칙은 POST /api/v1/webhook/github 하나뿐이다. 2) backend로 가는 경로의 합집합은 정확히 4개이고 전체 경로(/*)·/api/v1/* 같은 넓은 경로나
# 막아야 할 경로(raw·collect·internal·webhook/*·/mcp/)를 포함하지 않는다. 3) 막아야 할 경로를 가진 규칙은 대상 그룹으로 forward하지 않는다.
# 4) 어느 규칙도 가중치 forward 블록으로 두 번째 대상 그룹에 나눠 보내지 않는다.
run "only_the_github_webhook_reaches_the_worker_and_blocked_paths_never_forward" {
  assert {
    condition = try(
      length([for r in aws_lb_listener_rule.this : r if r.action[0].type == "forward" && r.action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn]) == 1
      && alltrue([
        for r in aws_lb_listener_rule.this :
        toset(flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]])) == toset(["/api/v1/webhook/github"])
        && toset(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == toset(["POST"])
        if r.action[0].type == "forward" && r.action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn
      ]),
      false
    )
    error_message = "pipeline-worker 대상 그룹(${aws_lb_target_group.pipeline_worker.arn})으로 forward하는 규칙은 POST /api/v1/webhook/github 하나뿐이어야 한다. worker로 가는 규칙: ${jsonencode(try([for r in aws_lb_listener_rule.this : { paths = flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]), methods = flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]]) } if r.action[0].type == "forward" && r.action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn], "규칙을 읽을 수 없다"))}"
  }

  assert {
    condition = try(
      toset(flatten([for r in aws_lb_listener_rule.this : flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]) if r.action[0].type == "forward" && r.action[0].target_group_arn == aws_lb_target_group.backend.arn]))
      == toset(["/api/*", "/mcp", "/oauth2/*", "/.well-known/*"]),
      false
    )
    error_message = "backend 대상 그룹(${aws_lb_target_group.backend.arn})으로 가는 경로의 합집합은 정확히 /api/*·/mcp·/oauth2/*·/.well-known/*여야 한다(/*·/api/v1/*·/api/v1/raw/*·/api/v1/webhook/*·/mcp/ 금지). 지금 경로: ${jsonencode(try(flatten([for r in aws_lb_listener_rule.this : flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]) if r.action[0].type == "forward" && r.action[0].target_group_arn == aws_lb_target_group.backend.arn]), "규칙을 읽을 수 없다"))}"
  }

  assert {
    condition = try(alltrue([
      for r in aws_lb_listener_rule.this : (
        r.action[0].type == "fixed-response"
        && r.action[0].target_group_arn == null
        && length(r.action[0].forward) == 0
      )
      if anytrue([for p in flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]) : contains([
        "/api/v1/internal", "/api/v1/internal/*", "/api/v1/collect", "/api/v1/collect/*",
        "/api/v1/raw", "/api/v1/raw/*", "/api/v1/webhook", "/api/v1/webhook/*", "/mcp/",
      ], p)])
    ]), false)
    error_message = "막아야 할 경로(/api/v1/internal/*·/api/v1/collect/*·/api/v1/raw/*·/api/v1/webhook/*·/mcp/)를 가진 규칙은 fixed-response여야 하고 대상 그룹으로 forward하면 안 된다. 지금 값: ${jsonencode(try([for r in aws_lb_listener_rule.this : { paths = flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]), type = r.action[0].type, target = r.action[0].target_group_arn }], "규칙을 읽을 수 없다"))}"
  }

  assert {
    condition     = try(alltrue([for r in aws_lb_listener_rule.this : length(r.action[0].forward) == 0]), false)
    error_message = "규칙은 target_group_arn 하나로만 넘겨야 한다. 가중치 forward 블록은 쓰지 않는다(두 번째 대상 그룹으로 몰래 나눠 보낼 수 있다). 지금 forward 블록 수: ${jsonencode(try([for r in aws_lb_listener_rule.this : length(r.action[0].forward)], "규칙을 읽을 수 없다"))}"
  }
}

# 규칙 순서나 겹침 때문에 길이 열리는 것을 막는다 — 요청 표(메서드 경로 → 도착지)를 번호가 낮은 규칙부터 평가하는 ALB처럼 돌려, 가장 먼저 걸린 규칙(없으면 기본 동작)이 어디로 가는지 직접 본다.
# 도착지는 fixed-response(막힘)·backend·worker다. 경로 패턴의 *는 임의의 문자열이고(정규식으로 바꿔 비교), 메서드 조건이 있는 규칙은 그 메서드일 때만 걸린다.
# 위 run들이 글자를 대조한다면 이 run은 결과를 본다: backend 규칙이 차단 규칙보다 앞서거나, github 허용이 차단 뒤로 가거나, 어느 규칙이 넓어져도 여기서 잡힌다.
# /mcp/setup(SPA 공개 안내 페이지)·/·/actuator는 ALB가 받을 요청이 아니라서 backend로 가면 안 되고 기본 동작 404로 끝나야 한다.
# 어긋나면 terraform이 어느 요청이 어디로 갔는지 차이(Diff)로 보여 준다.
run "routing_sends_each_request_to_the_expected_destination" {
  assert {
    condition = {
      for k in [
        "GET /api/v1/raw/commits",
        "POST /api/v1/raw/commits",
        "POST /api/v1/raw",
        "GET /api/v1/collect/github",
        "POST /api/v1/collect/github",
        "POST /api/v1/collect",
        "GET /api/v1/internal/projects",
        "DELETE /api/v1/internal/projects/1",
        "GET /api/v1/internal",
        "GET /api/v1/webhook/github",
        "PUT /api/v1/webhook/github",
        "POST /api/v1/webhook/github/",
        "POST /api/v1/webhook/slack",
        "POST /api/v1/webhook",
        "GET /mcp/",
        "POST /mcp/",
        "GET /mcp/setup",
        "GET /",
        "GET /actuator/health",
        "POST /api/v1/webhook/github",
        "GET /api/v1/projects",
        "POST /api/v1/auth/refresh",
        "POST /mcp",
        "GET /mcp",
        "GET /oauth2/authorize",
        "POST /oauth2/token",
        "GET /.well-known/oauth-authorization-server",
        "GET /.well-known/oauth-protected-resource",
        ] : k => one([
          for hits in [concat(
            [
              for r in aws_lb_listener_rule.this : {
                priority = r.priority
                to       = r.action[0].type == "forward" ? (r.action[0].target_group_arn == aws_lb_target_group.backend.arn ? "backend" : r.action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn ? "worker" : "other-target") : r.action[0].type
              }
              if anytrue([for v in flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]]) : can(regex("^${replace(replace(v, ".", "\\."), "*", ".*")}$", split(" ", k)[1]))])
              && (length(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])) == 0 || contains(flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]]), split(" ", k)[0]))
            ],
            [{
              priority = 100000
              to       = aws_lb_listener.http.default_action[0].type == "forward" ? (aws_lb_listener.http.default_action[0].target_group_arn == aws_lb_target_group.backend.arn ? "backend" : aws_lb_listener.http.default_action[0].target_group_arn == aws_lb_target_group.pipeline_worker.arn ? "worker" : "other-target") : aws_lb_listener.http.default_action[0].type
            }],
          )] : [for h in hits : h.to if h.priority == min([for x in hits : x.priority]...)][0]
      ])
      } == {
      "GET /api/v1/raw/commits"                     = "fixed-response"
      "POST /api/v1/raw/commits"                    = "fixed-response"
      "POST /api/v1/raw"                            = "fixed-response"
      "GET /api/v1/collect/github"                  = "fixed-response"
      "POST /api/v1/collect/github"                 = "fixed-response"
      "POST /api/v1/collect"                        = "fixed-response"
      "GET /api/v1/internal/projects"               = "fixed-response"
      "DELETE /api/v1/internal/projects/1"          = "fixed-response"
      "GET /api/v1/internal"                        = "fixed-response"
      "GET /api/v1/webhook/github"                  = "fixed-response"
      "PUT /api/v1/webhook/github"                  = "fixed-response"
      "POST /api/v1/webhook/github/"                = "fixed-response"
      "POST /api/v1/webhook/slack"                  = "fixed-response"
      "POST /api/v1/webhook"                        = "fixed-response"
      "GET /mcp/"                                   = "fixed-response"
      "POST /mcp/"                                  = "fixed-response"
      "GET /mcp/setup"                              = "fixed-response"
      "GET /"                                       = "fixed-response"
      "GET /actuator/health"                        = "fixed-response"
      "POST /api/v1/webhook/github"                 = "worker"
      "GET /api/v1/projects"                        = "backend"
      "POST /api/v1/auth/refresh"                   = "backend"
      "POST /mcp"                                   = "backend"
      "GET /mcp"                                    = "backend"
      "GET /oauth2/authorize"                       = "backend"
      "POST /oauth2/token"                          = "backend"
      "GET /.well-known/oauth-authorization-server" = "backend"
      "GET /.well-known/oauth-protected-resource"   = "backend"
    }
    error_message = "요청이 계약 표의 도착지로 가지 않는다(위 Diff가 어긋난 요청을 보여 준다: - 줄이 지금 도착지, + 줄이 기대 도착지). 지금 규칙(우선순위, 경로, 메서드, 동작): ${jsonencode(try(sort([for r in aws_lb_listener_rule.this : format("%05d [%s] [%s] %s", r.priority, join(",", flatten([for c in r.condition : [for p in c.path_pattern : tolist(p.values)]])), join(",", flatten([for c in r.condition : [for m in c.http_request_method : tolist(m.values)]])), r.action[0].type == "forward" ? r.action[0].target_group_arn : r.action[0].type)]), "규칙을 읽을 수 없다"))}, 기본 동작: ${jsonencode(aws_lb_listener.http.default_action[*].type)}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 5. 출력
# ─────────────────────────────────────────────────────────────────────────────

# 출력이 엉뚱한 값을 내보내는 것을 막는다 — 이 출력들은 다음 묶음(CloudFront·ECS)이 ALB 주소·리스너·대상 그룹을 받아 쓰는 유일한 통로다.
# 대상 그룹 맵의 키가 바뀌거나 값이 서로 바뀌면 서비스가 엉뚱한 그룹에 등록된다. ARN 값이 자원마다 달라서(첫 run이 확인) 바뀐 출력이 값으로 가려진다.
# 목록·맵은 맵 대 객체의 형식 차이로 같은 값도 다르게 비교되므로 tomap으로 맞춰 비교한다.
run "outputs_return_alb_listener_and_target_group_values" {
  assert {
    condition     = output.alb_arn == aws_lb.main.arn
    error_message = "alb_arn 출력이 aws_lb.main.arn과 다르다. 지금 값: ${jsonencode(output.alb_arn)}, ALB ARN: ${aws_lb.main.arn}"
  }

  assert {
    condition     = output.alb_arn_suffix == aws_lb.main.arn_suffix
    error_message = "alb_arn_suffix 출력이 aws_lb.main.arn_suffix와 다르다. 지금 값: ${jsonencode(output.alb_arn_suffix)}, arn_suffix: ${aws_lb.main.arn_suffix}"
  }

  assert {
    condition     = output.alb_dns_name == aws_lb.main.dns_name
    error_message = "alb_dns_name 출력이 aws_lb.main.dns_name과 다르다. 지금 값: ${jsonencode(output.alb_dns_name)}, dns_name: ${aws_lb.main.dns_name}"
  }

  assert {
    condition     = output.listener_arn == aws_lb_listener.http.arn
    error_message = "listener_arn 출력이 aws_lb_listener.http.arn과 다르다. 지금 값: ${jsonencode(output.listener_arn)}, 리스너 ARN: ${aws_lb_listener.http.arn}"
  }

  assert {
    condition     = output.listener_port == aws_lb_listener.http.port && output.listener_port == 80
    error_message = "listener_port 출력은 aws_lb_listener.http.port(80)와 같아야 한다. 지금 값: ${jsonencode(output.listener_port)}, 리스너 포트: ${jsonencode(aws_lb_listener.http.port)}"
  }

  assert {
    condition     = try(toset(keys(tomap(output.target_group_arns))) == toset(["backend", "pipeline_worker"]) && length(tomap(output.target_group_arns)) == 2, false)
    error_message = "target_group_arns 출력의 키는 정확히 backend·pipeline_worker 둘이어야 한다. 지금 값: ${jsonencode(output.target_group_arns)}"
  }

  assert {
    condition = try(
      tomap(output.target_group_arns) == tomap({
        backend         = aws_lb_target_group.backend.arn
        pipeline_worker = aws_lb_target_group.pipeline_worker.arn
      }),
      false
    )
    error_message = "target_group_arns 출력의 값은 키와 같은 이름의 대상 그룹 ARN이어야 한다. 지금 값: ${jsonencode(output.target_group_arns)}, backend: ${aws_lb_target_group.backend.arn}, pipeline_worker: ${aws_lb_target_group.pipeline_worker.arn}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 6. 변수가 값에 반영되는지
# ─────────────────────────────────────────────────────────────────────────────

# 변수가 값에 반영되지 않고 코드에 박힌 값이 쓰이는 것을 막는다 — 기본값으로만 돌면 이름·네트워크·타임아웃·헬스 체크 경로가 하드코딩돼도 위 run은 통과한다.
# 허용 범위의 위쪽 경계(idle 4000·등록 해제 3600)로 돌려 경계값이 검증에 막히지 않는 것도 함께 본다. 앞 run들이 만든 상태 위에 변수를 바꿔 다시 적용하므로 위 run들 뒤에 둔다.
run "custom_variables_flow_into_the_load_balancer_and_target_groups" {
  variables {
    name                         = "whycode-staging"
    vpc_id                       = "vpc-0fedcba9876543210"
    security_group_id            = "sg-0fedcba9876543210"
    subnet_ids                   = ["subnet-0fedcba9876543210", "subnet-0fedcba9876543211"]
    idle_timeout_seconds         = 4000
    deregistration_delay_seconds = 3600
    health_check_path            = "/actuator/health"
  }

  assert {
    condition     = aws_lb.main.name == "whycode-staging-alb" && aws_lb_target_group.backend.name == "whycode-staging-backend" && aws_lb_target_group.pipeline_worker.name == "whycode-staging-worker"
    error_message = "name을 바꾸면 ALB·대상 그룹 이름 접두사도 바뀌어야 한다. 지금 값: ${jsonencode({ alb = aws_lb.main.name, backend = aws_lb_target_group.backend.name, pipeline_worker = aws_lb_target_group.pipeline_worker.name })}"
  }

  assert {
    condition = (
      toset(aws_lb.main.security_groups) == toset(["sg-0fedcba9876543210"])
      && toset(aws_lb.main.subnets) == toset(["subnet-0fedcba9876543210", "subnet-0fedcba9876543211"])
      && aws_lb_target_group.backend.vpc_id == "vpc-0fedcba9876543210"
      && aws_lb_target_group.pipeline_worker.vpc_id == "vpc-0fedcba9876543210"
    )
    error_message = "security_group_id·subnet_ids·vpc_id를 바꾸면 ALB와 대상 그룹에 반영되어야 한다. 지금 값: ${jsonencode({ security_groups = aws_lb.main.security_groups, subnets = aws_lb.main.subnets, backend_vpc = aws_lb_target_group.backend.vpc_id, worker_vpc = aws_lb_target_group.pipeline_worker.vpc_id })}"
  }

  assert {
    condition     = aws_lb.main.idle_timeout == 4000
    error_message = "idle_timeout_seconds를 4000으로 바꾸면 ALB idle_timeout도 4000이어야 한다. 지금 값: ${jsonencode(aws_lb.main.idle_timeout)}"
  }

  assert {
    condition     = aws_lb_target_group.backend.deregistration_delay == "3600" && aws_lb_target_group.pipeline_worker.deregistration_delay == "3600"
    error_message = "deregistration_delay_seconds를 3600으로 바꾸면 두 대상 그룹의 deregistration_delay도 문자열 \"3600\"이어야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.deregistration_delay, pipeline_worker = aws_lb_target_group.pipeline_worker.deregistration_delay })}"
  }

  assert {
    condition = try(alltrue([
      for g in [aws_lb_target_group.backend, aws_lb_target_group.pipeline_worker] : g.health_check[0].path == "/actuator/health"
    ]), false)
    error_message = "health_check_path를 바꾸면 두 대상 그룹의 헬스 체크 경로도 바뀌어야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.health_check, pipeline_worker = aws_lb_target_group.pipeline_worker.health_check })}"
  }
}

# 허용 범위의 아래쪽 경계(idle 1·등록 해제 0)가 검증에 막히는 것을 막는다 — 등록 해제 0은 문자열 "0"이 되어야 하고(tostring), 0을 "없음"으로 취급해 기본값으로 되돌리면 안 된다.
run "lower_bound_timeouts_are_accepted_and_flow_into_resources" {
  variables {
    idle_timeout_seconds         = 1
    deregistration_delay_seconds = 0
  }

  assert {
    condition     = aws_lb.main.idle_timeout == 1
    error_message = "idle_timeout_seconds 1은 허용되고 ALB idle_timeout이 1이어야 한다. 지금 값: ${jsonencode(aws_lb.main.idle_timeout)}"
  }

  assert {
    condition     = aws_lb_target_group.backend.deregistration_delay == "0" && aws_lb_target_group.pipeline_worker.deregistration_delay == "0"
    error_message = "deregistration_delay_seconds 0은 허용되고 두 대상 그룹의 deregistration_delay가 문자열 \"0\"이어야 한다. 지금 값: ${jsonencode({ backend = aws_lb_target_group.backend.deregistration_delay, pipeline_worker = aws_lb_target_group.pipeline_worker.deregistration_delay })}"
  }
}

# ─────────────────────────────────────────────────────────────────────────────
# 7. 변수 검증 (오류로 끝나는 run이라 맨 뒤)
# ─────────────────────────────────────────────────────────────────────────────

# 서브넷이 1개일 때 변수 검증이 막지 않는 것을 잡는다 — 내부 ALB는 서로 다른 AZ의 서브넷 2개를 요구해서 하나면 apply에서야 실패한다.
run "subnet_ids_reject_one_subnet" {
  command = plan

  variables {
    subnet_ids = ["subnet-0123456789abcdef0"]
  }

  expect_failures = [var.subnet_ids]
}

# 서브넷이 3개일 때도 막아야 한다 — 셋째 서브넷이 조용히 ALB에 붙어 계약(AZ 2개)과 달라진다.
run "subnet_ids_reject_three_subnets" {
  command = plan

  variables {
    subnet_ids = ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1", "subnet-0123456789abcdef2"]
  }

  expect_failures = [var.subnet_ids]
}

# idle 타임아웃이 0이면 막는다 — ALB는 1~4000초만 받는다.
run "idle_timeout_rejects_zero" {
  command = plan

  variables {
    idle_timeout_seconds = 0
  }

  expect_failures = [var.idle_timeout_seconds]
}

# idle 타임아웃의 위쪽 경계 바로 밖(4001)을 막는다.
run "idle_timeout_rejects_4001" {
  command = plan

  variables {
    idle_timeout_seconds = 4001
  }

  expect_failures = [var.idle_timeout_seconds]
}

# 등록 해제 지연이 음수면 막는다 — 대상 그룹은 0~3600초만 받는다.
run "deregistration_delay_rejects_negative" {
  command = plan

  variables {
    deregistration_delay_seconds = -1
  }

  expect_failures = [var.deregistration_delay_seconds]
}

# 등록 해제 지연의 위쪽 경계 바로 밖(3601)을 막는다.
run "deregistration_delay_rejects_3601" {
  command = plan

  variables {
    deregistration_delay_seconds = 3601
  }

  expect_failures = [var.deregistration_delay_seconds]
}
