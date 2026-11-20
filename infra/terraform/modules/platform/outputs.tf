output "ecr_repository_urls" {
  description = "서비스별 ECR 저장소 URL(키: backend·pipeline-worker·ai-engine). CI 푸시와 ECS 작업 정의의 이미지 주소가 가리킨다."
  value       = { for k, r in aws_ecr_repository.service : k => r.repository_url }
}

output "ecr_repository_arns" {
  description = "서비스별 ECR 저장소 ARN(키: backend·pipeline-worker·ai-engine). 저장소를 가리키는 정책이 쓴다."
  value       = { for k, r in aws_ecr_repository.service : k => r.arn }
}

output "log_group_names" {
  description = "서비스별 로그 그룹 이름(키: backend·pipeline-worker·ai-engine). ECS 작업 정의의 로그 설정이 가리킨다."
  value       = { for k, g in aws_cloudwatch_log_group.service : k => g.name }
}

output "secret_arns" {
  description = "비밀 키별 Secrets Manager ARN(app_secrets의 키). ECS 작업 정의의 secrets가 가리킨다. 값은 이 모듈이 넣지 않는다."
  value       = { for k, s in aws_secretsmanager_secret.app : k => s.arn }
}

output "execution_role_arns" {
  description = "서비스별 ECS 실행 역할 ARN(키: backend·pipeline-worker·ai-engine). ECS 작업 정의의 executionRoleArn이 가리킨다."
  value       = { for k, r in aws_iam_role.execution : k => r.arn }
}

output "execution_role_names" {
  description = "서비스별 ECS 실행 역할 이름(키: backend·pipeline-worker·ai-engine). 다음 묶음이 이 역할에 정책을 붙일 때 쓴다."
  value       = { for k, r in aws_iam_role.execution : k => r.name }
}

output "ci_push_role_arn" {
  description = "이미지를 ECR에 올리는 CI 역할 ARN. GitHub 워크플로의 role-to-assume이 가리킨다."
  value       = aws_iam_role.ci_push.arn
}

output "ci_deploy_role_arn" {
  description = "배포용 CI 역할 ARN. GitHub 워크플로의 role-to-assume이 가리킨다. 이 모듈에서는 권한이 없다."
  value       = aws_iam_role.ci_deploy.arn
}

output "ci_deploy_role_name" {
  description = "배포용 CI 역할 이름. 다음 묶음이 이 역할에 배포 권한 정책을 붙일 때 쓴다."
  value       = aws_iam_role.ci_deploy.name
}

output "sns_topic_arn" {
  description = "알림 SNS 주제 ARN. 경보의 알림 대상이 가리킨다."
  value       = aws_sns_topic.alerts.arn
}
