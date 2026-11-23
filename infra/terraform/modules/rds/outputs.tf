output "address" {
  description = "DB 접속 호스트 이름(포트 없음). backend·pipeline-worker의 접속 주소가 가리킨다."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "DB 접속 포트(PostgreSQL 기본 5432)."
  value       = aws_db_instance.this.port
}

output "db_name" {
  description = "처음 만든 데이터베이스 이름. 접속 주소의 DB 이름 부분이다."
  value       = aws_db_instance.this.db_name
}

output "master_username" {
  description = "마스터 사용자 이름. 비밀번호는 출력하지 않고 secret_arn의 비밀에서만 읽는다."
  value       = aws_db_instance.this.username
}

output "secret_arn" {
  description = "마스터 접속 정보(username·password 키의 JSON)가 든 Secrets Manager 비밀 ARN. ECS 작업 정의의 secrets가 가리킨다."
  value       = aws_secretsmanager_secret.master.arn
}

output "identifier" {
  description = "DB 인스턴스 식별자. 경보의 DBInstanceIdentifier 차원과 스냅샷 조회에 쓴다."
  value       = aws_db_instance.this.identifier
}
