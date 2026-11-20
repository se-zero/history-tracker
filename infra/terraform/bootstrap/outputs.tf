output "state_bucket_name" {
  description = "Terraform 상태 버킷 이름. 환경 루트를 init할 때 -backend-config=\"bucket=...\"로 넘긴다."
  value       = aws_s3_bucket.state.bucket
}

output "github_oidc_provider_arn" {
  description = "GitHub Actions OIDC provider ARN. 배포 역할의 신뢰 정책이 이 값을 가리킨다."
  value       = aws_iam_openid_connect_provider.github.arn
}
