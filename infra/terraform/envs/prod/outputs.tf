output "ecr_repository_urls" {
  description = "서비스별 ECR 저장소 주소. 첫 이미지 푸시와 배포 워크플로가 쓴다."
  value       = module.platform.ecr_repository_urls
}

output "ci_push_role_arn" {
  description = "이미지 푸시용 GitHub Actions 역할 ARN. GitHub 환경(production)의 변수로 등록한다."
  value       = module.platform.ci_push_role_arn
}

output "ci_deploy_role_arn" {
  description = "배포용 GitHub Actions 역할 ARN. GitHub 환경(production)의 변수로 등록한다."
  value       = module.platform.ci_deploy_role_arn
}

output "sns_topic_arn" {
  description = "알림 SNS 주제 ARN. 이메일 구독은 apply 뒤 확인 메일의 링크를 눌러야 시작된다."
  value       = module.platform.sns_topic_arn
}

output "nat_public_ip" {
  description = "NAT 게이트웨이의 공인 IP. 서비스가 바깥으로 나갈 때 보이는 주소라 허용 목록에 등록할 때 쓴다."
  value       = module.network.nat_public_ip
}
