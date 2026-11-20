# VPC·서브넷·NAT·라우팅. 보안 그룹은 security_groups.tf에 있다.
# 태그는 Name만 단다. Project·Environment·ManagedBy는 환경 루트 provider의 default_tags가 달아 준다.

# DNS 지원과 호스트 이름을 둘 다 켠다. 꺼지면 VPC 안에서 RDS·MQ 같은 관리형 서비스의 엔드포인트 이름이
# 사설 주소로 풀리지 않는다.
resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "${var.name}-vpc"
  }
}

# 인터넷으로 나가는 길의 끝. 비공개 서브넷은 이 게이트웨이를 직접 쓰지 않고 NAT를 거쳐 나간다.
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.name}-igw"
  }
}

# 공개 서브넷은 NAT 게이트웨이를 놓으려고만 하나 둔다. 내부 ALB·ECS 태스크·RDS·MQ·Neo4j는 전부 비공개
# 서브넷을 쓰고 인터넷 쪽 입구는 CloudFront가 맡으므로, 공개 서브넷을 AZ마다 둘 이유가 없다.
# 공인 IP 자동 배정은 명시적으로 끈다 — 공개 서브넷에서도 인터넷에 직접 닿는 것은 NAT의 EIP 하나여야 한다.
resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.availability_zones[0]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 0)
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name}-public-${var.availability_zones[0]}"
  }
}

# 비공개 서브넷은 AZ마다 하나씩 둔다(내부 ALB가 서로 다른 AZ의 서브넷 2개를 요구한다).
# 번호를 10부터 매겨 0~9번은 공개 서브넷용으로 비워 둔다.
resource "aws_subnet" "private" {
  count = length(var.availability_zones)

  vpc_id                  = aws_vpc.main.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 10 + count.index)
  map_public_ip_on_launch = false

  tags = {
    Name = "${var.name}-private-${var.availability_zones[count.index]}"
  }
}

# 바깥 서비스가 우리 출구 주소를 허용 목록에 넣을 수 있도록 NAT에는 고정 공인 IP를 붙인다.
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = {
    Name = "${var.name}-nat-eip"
  }
}

# NAT는 하나만 둔다. 켜 두는 동안 시간당 요금이 붙는 자원이고 이번 배치는 1주 실험이라, AZ마다 두는 가용성을
# 비용과 맞바꿨다. 대가: NAT가 있는 첫 번째 AZ나 NAT 자체가 장애를 겪으면 두 AZ 모두 외부 호출(OpenAI·소스 API 등)이
# 끊기고, 두 번째 AZ의 외부 호출은 AZ를 건너 NAT로 가므로 교차 AZ 전송료가 붙는다.
# IGW가 VPC에 붙은 뒤에 NAT를 만들어야 해서 순서를 depends_on으로 못 박는다.
resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public.id

  tags = {
    Name = "${var.name}-nat"
  }

  depends_on = [aws_internet_gateway.main]
}

# 공개 라우트 테이블은 NAT가 있는 공개 서브넷이 쓰고, 비공개 라우트 테이블은 NAT가 하나라 두 비공개 서브넷이 같이 쓴다.
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.name}-public-rt"
  }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${var.name}-private-rt"
  }
}

# 기본 경로는 테이블 안의 인라인 route 블록이 아니라 별도 aws_route로 둔다. 한 테이블에 둘을 섞으면 서로의 라우트를
# 덮어써서 apply마다 라우트가 지워졌다 다시 생긴다.
resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route" "private_nat" {
  route_table_id         = aws_route_table.private.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main.id
}

# 연결하지 않은 서브넷은 VPC의 기본(main) 라우트 테이블을 쓰는데, 거기에는 바깥으로 나가는 경로가 없다.
resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  count = length(var.availability_zones)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}
