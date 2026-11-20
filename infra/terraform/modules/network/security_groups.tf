# 보안 그룹이 서비스 간 허용 경로의 전부다. 규칙은 서비스 간 11개 경로(인바운드·아웃바운드 짝)와 바깥 443 4개뿐이다.
# 인바운드는 전부 보안 그룹 참조라서 주소 대역(0.0.0.0/0 포함)으로는 아무것도 열리지 않는다.
#
# - 보안 그룹을 만들면 Terraform이 AWS가 붙여 준 기본 아웃바운드(전부 허용)를 지운다. 그래서 인바운드를 연 경로마다
#   출발 쪽 아웃바운드 짝이 있어야 실제로 통신이 되고, 아래 경로 표(local.service_paths) 하나에서 두 방향을 함께 만든다.
# - 규칙은 인라인 ingress·egress가 아니라 별도 규칙 리소스로만 둔다. 둘을 섞으면 apply마다 서로의 규칙을 지운다.
# - name·description을 바꾸면 보안 그룹이 교체된다(ForceNew). 다른 자원이 참조하는 그룹이라 교체가 번거로우니 문구를 함부로 고치지 않는다.
# - description(규칙 포함)은 EC2 API가 영문·숫자·공백과 ._-:/()#,@[]+=&;{}!$*만 받아서 영어로 쓴다. 한글을 넣으면 apply가 실패한다.

# ALB 인바운드는 이 모듈에 없다. CloudFront가 VPC 원본을 만든 뒤에야 AWS가 서비스 관리 보안 그룹을 만들고, ALB가 받을
# 출처는 그 그룹이라서 진입점 묶음(T2)이 그때 인바운드를 붙인다. 그 전까지 ALB는 아무도 받지 않고, 내부 ALB라 인터넷에도
# 열려 있지 않다. 주소 대역으로 미리 열어 두지 않는다.
resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal ALB"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-alb"
  }
}

resource "aws_security_group" "backend" {
  name        = "${var.name}-backend"
  description = "backend Fargate service"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-backend"
  }
}

resource "aws_security_group" "pipeline_worker" {
  name        = "${var.name}-pipeline-worker"
  description = "pipeline-worker Fargate service"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-pipeline-worker"
  }
}

resource "aws_security_group" "ai_engine" {
  name        = "${var.name}-ai-engine"
  description = "ai-engine Fargate service"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-ai-engine"
  }
}

resource "aws_security_group" "neo4j" {
  name        = "${var.name}-neo4j"
  description = "Neo4j EC2 instance"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-neo4j"
  }
}

resource "aws_security_group" "rds" {
  name        = "${var.name}-rds"
  description = "RDS PostgreSQL"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-rds"
  }
}

resource "aws_security_group" "mq" {
  name        = "${var.name}-mq"
  description = "Amazon MQ RabbitMQ broker"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${var.name}-mq"
  }
}

locals {
  # 규칙 for_each의 키는 plan 때 알아야 하는 정적 문자열이어야 한다. 보안 그룹 id는 apply 뒤에야 알 수 있어서
  # 키가 아니라 값(security_group_id)에만 쓰고, 아래 표에는 역할 이름만 적어 이 맵으로 id를 찾는다.
  security_group_ids = {
    alb             = aws_security_group.alb.id
    backend         = aws_security_group.backend.id
    pipeline_worker = aws_security_group.pipeline_worker.id
    ai_engine       = aws_security_group.ai_engine.id
    neo4j           = aws_security_group.neo4j.id
    rds             = aws_security_group.rds.id
    mq              = aws_security_group.mq.id
  }

  # 서비스 간 허용 경로. from은 연결을 거는 쪽, to는 받는 쪽이다. 한 항목이 to의 인바운드(출처 = from)와
  # from의 아웃바운드(목적지 = to)를 같은 포트로 함께 만들어서 두 방향이 어긋나지 않는다.
  service_paths = {
    alb_to_backend = {
      from        = "alb"
      to          = "backend"
      port        = 8080
      description = "ALB to backend API, MCP and OAuth requests"
    }
    pipeline_worker_to_backend = {
      from        = "pipeline_worker"
      to          = "backend"
      port        = 8080
      description = "pipeline-worker to backend internal API"
    }
    alb_to_pipeline_worker = {
      from        = "alb"
      to          = "pipeline_worker"
      port        = 8081
      description = "ALB to pipeline-worker GitHub webhooks"
    }
    backend_to_pipeline_worker = {
      from        = "backend"
      to          = "pipeline_worker"
      port        = 8081
      description = "backend to pipeline-worker internal API"
    }
    backend_to_ai_engine = {
      from        = "backend"
      to          = "ai_engine"
      port        = 8000
      description = "backend to ai-engine query and graph API"
    }
    ai_engine_to_neo4j = {
      from        = "ai_engine"
      to          = "neo4j"
      port        = 7687
      description = "ai-engine to Neo4j Bolt"
    }
    backend_to_rds = {
      from        = "backend"
      to          = "rds"
      port        = 5432
      description = "backend to PostgreSQL"
    }
    pipeline_worker_to_rds = {
      from        = "pipeline_worker"
      to          = "rds"
      port        = 5432
      description = "pipeline-worker to PostgreSQL"
    }
    pipeline_worker_to_mq_amqps = {
      from        = "pipeline_worker"
      to          = "mq"
      port        = 5671
      description = "pipeline-worker to RabbitMQ AMQPS"
    }
    ai_engine_to_mq_amqps = {
      from        = "ai_engine"
      to          = "mq"
      port        = 5671
      description = "ai-engine to RabbitMQ AMQPS"
    }
    ai_engine_to_mq_management = {
      from        = "ai_engine"
      to          = "mq"
      port        = 15671
      description = "ai-engine to RabbitMQ management API for queue discovery"
    }
  }

  # 바깥으로 나가는 443은 이 네 서비스에만 준다. 바깥 API(OpenAI·소스 API 등)와 AWS 서비스(ECR·로그·비밀·SSM)를
  # NAT로 부르는 것은 이 넷뿐이다. ALB는 backend·pipeline_worker에 넘기기만 하고, RDS·MQ는 받기만 해서 바깥으로 나갈 일이 없다.
  # DNS(53) 규칙은 두지 않는다. 보안 그룹은 VPC DNS(Route 53 Resolver) 질의를 막지 못해서 열 필요가 없다.
  internet_https_roles = ["backend", "pipeline_worker", "ai_engine", "neo4j"]

  # 아웃바운드 for_each 입력. 서비스 간 경로와 바깥 443을 한 for_each에 합치려고 두 부분의 값을 같은 속성 집합
  # (from·to·cidr_ipv4·port·description)으로 맞췄다. 안 쓰는 속성은 null이다. 속성 집합이 다르면 for_each 입력 타입 오류가 난다.
  egress_rules = merge(
    {
      for key, path in local.service_paths : key => {
        from        = path.from
        to          = path.to
        cidr_ipv4   = null
        port        = path.port
        description = path.description
      }
    },
    {
      for role in local.internet_https_roles : "${role}_to_internet_https" => {
        from        = role
        to          = null
        cidr_ipv4   = "0.0.0.0/0"
        port        = 443
        description = "Outbound HTTPS to external APIs and AWS services via NAT"
      }
    },
  )
}

resource "aws_vpc_security_group_ingress_rule" "this" {
  for_each = local.service_paths

  security_group_id            = local.security_group_ids[each.value.to]
  referenced_security_group_id = local.security_group_ids[each.value.from]
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = each.value.description
}

resource "aws_vpc_security_group_egress_rule" "this" {
  for_each = local.egress_rules

  security_group_id            = local.security_group_ids[each.value.from]
  referenced_security_group_id = each.value.to == null ? null : local.security_group_ids[each.value.to]
  cidr_ipv4                    = each.value.cidr_ipv4
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = each.value.description
}
