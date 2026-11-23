output "web_bucket_name" {
  description = "정적 웹 산출물을 올리는 비공개 S3 버킷 이름"
  value       = aws_s3_bucket.web.bucket
}

output "cloudfront_distribution_id" {
  description = "정적 웹 캐시 무효화에 사용할 CloudFront 배포 ID"
  value       = aws_cloudfront_distribution.main.id
}

output "cloudfront_domain_name" {
  description = "스테이징의 공개 CloudFront 기본 도메인"
  value       = aws_cloudfront_distribution.main.domain_name
}
