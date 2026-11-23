output "amqp_host" {
  description = "AMQPS 접속 호스트. 브로커 엔드포인트 목록에서 amqps://로 시작하는 주소만 골라 뽑은 값이다. 완성된 접속 문자열은 비밀의 url에 들어 있다."
  value       = local.amqp_host
}

output "amqp_port" {
  description = "AMQPS 접속 포트(숫자, 보통 5671). amqp_host와 같은 엔드포인트에서 뽑는다."
  value       = local.amqp_port
}

output "broker_id" {
  description = "브로커 id(b-로 시작). 브로커를 가리키는 다른 자원이 쓴다."
  value       = aws_mq_broker.this.id
}

output "broker_arn" {
  description = "브로커 ARN. 브로커를 가리키는 정책이 쓴다."
  value       = aws_mq_broker.this.arn
}

output "secret_arn" {
  description = "접속 비밀(JSON: username·password·url)의 Secrets Manager ARN. ECS 작업 정의의 secrets가 키 하나씩(<ARN>:url::) 가리킨다. pipeline-worker와 ai-engine 실행 역할만 읽을 수 있다."
  value       = aws_secretsmanager_secret.mq.arn
}
