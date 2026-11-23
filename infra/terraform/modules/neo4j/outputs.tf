output "instance_id" {
  description = "Neo4j EC2 인스턴스 id. SSM Session Manager로 접속할 때 쓴다."
  value       = aws_instance.neo4j.id
}

output "private_ip" {
  description = "Neo4j 인스턴스의 사설 IP. ai-engine의 NEO4J_URI(bolt://<이 주소>:7687)가 가리킨다. 인스턴스가 교체되면 바뀐다."
  value       = aws_instance.neo4j.private_ip
}

output "secret_arn" {
  description = "Neo4j 자격 증명(JSON: username·password)을 담은 Secrets Manager 비밀 ARN. ECS 작업 정의의 secrets가 키(username·password)를 붙여 가리킨다."
  value       = aws_secretsmanager_secret.neo4j.arn
}

output "backup_bucket_name" {
  description = "Neo4j 덤프를 보관하는 비공개 S3 버킷 이름"
  value       = aws_s3_bucket.backup.bucket
}

output "backup_bucket_arn" {
  description = "Neo4j 덤프 백업 버킷 ARN. 이 버킷을 가리키는 정책이 쓴다."
  value       = aws_s3_bucket.backup.arn
}

output "data_volume_id" {
  description = "그래프 데이터가 든 EBS 데이터 볼륨 id. 스냅샷을 뜨거나 새 인스턴스에 붙여 복원할 때 쓴다."
  value       = aws_ebs_volume.data.id
}
