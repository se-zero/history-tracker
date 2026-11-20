output "vpc_id" {
  description = "VPC id. 이후 묶음(진입점·데이터·앱)이 자원을 놓을 VPC를 가리킬 때 쓴다."
  value       = aws_vpc.main.id
}

output "public_subnet_id" {
  description = "공개 서브넷 id. NAT 게이트웨이만 놓여 있으니 다른 자원을 여기에 두지 않는다."
  value       = aws_subnet.public.id
}

output "private_subnet_ids" {
  description = "비공개 서브넷 id 목록(var.availability_zones 순서). 내부 ALB·ECS 태스크·RDS·MQ·Neo4j EC2가 이 서브넷에 놓인다."
  value       = aws_subnet.private[*].id
}

output "nat_gateway_id" {
  description = "NAT 게이트웨이 id. NAT 지표와 비용을 확인할 때 쓴다."
  value       = aws_nat_gateway.main.id
}

output "nat_public_ip" {
  description = "NAT의 고정 공인 IP. 바깥 서비스가 우리 출구 주소를 허용 목록에 넣을 때 쓴다."
  value       = aws_eip.nat.public_ip
}

output "security_group_ids" {
  description = "보안 그룹 id 맵. 키는 alb·backend·pipeline_worker·ai_engine·neo4j·rds·mq."
  value       = local.security_group_ids
}
