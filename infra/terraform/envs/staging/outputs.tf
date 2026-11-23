output "ecr_repository_urls" {
  description = "서비스별 ECR 저장소 주소. 첫 이미지 푸시와 배포 워크플로가 쓴다."
  value       = module.platform.ecr_repository_urls
}

output "ci_push_role_arn" {
  description = "이미지 푸시용 GitHub Actions 역할 ARN. GitHub 환경(staging)의 변수로 등록한다."
  value       = module.platform.ci_push_role_arn
}

output "ci_deploy_role_arn" {
  description = "배포용 GitHub Actions 역할 ARN. GitHub 환경(staging)의 변수로 등록한다."
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

output "web_bucket_name" {
  description = "웹 빌드 결과를 올릴 비공개 S3 버킷 이름. GitHub Actions가 정적 파일을 동기화하는 데 쓴다."
  value       = module.edge.web_bucket_name
}

output "cloudfront_distribution_id" {
  description = "웹 배포 뒤 캐시 무효화를 요청할 CloudFront 배포 ID."
  value       = module.edge.cloudfront_distribution_id
}

output "cloudfront_domain_name" {
  description = "CloudFront가 발급한 스테이징 도메인. https://를 붙여 앱 공개 주소와 테스트 OAuth 콜백을 설정한다."
  value       = module.edge.cloudfront_domain_name
}
