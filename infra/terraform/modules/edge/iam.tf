# 스테이징 버킷만 읽고 쓸 수 있으며 파일 삭제는 허용하지 않는다.
resource "aws_iam_role_policy" "ci_deploy_web" {
  role = var.ci_deploy_role_name
  name = "web-deploy"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.web.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = ["${aws_s3_bucket.web.arn}/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
        Resource = [aws_cloudfront_distribution.main.arn]
      },
    ]
  })
}
