# 이 루트는 backend 블록 없이 로컬 상태를 쓴다. 상태를 담을 버킷을 이 루트가 만들기 때문에,
# 버킷이 생기기 전에는 원격 상태를 둘 곳이 없다.

data "aws_caller_identity" "current" {}

# S3 버킷 이름은 전 세계에서 하나뿐이라 계정 번호를 붙여 다른 계정과 부딪치지 않게 한다.
# force_destroy는 켜지 않는다. 켜면 destroy가 상태 파일이 든 버킷을 비우고 지운다.
resource "aws_s3_bucket" "state" {
  bucket = "whycode-tfstate-${data.aws_caller_identity.current.account_id}"
}

# 덮어쓰거나 깨진 상태 파일을 이전 버전으로 되돌릴 수 있게 한다.
resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

# AES256(S3 관리 키)은 KMS 키 권한 없이 S3 권한만으로 상태를 읽고 쓸 수 있다.
resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 상태 파일에는 비밀값이 평문으로 남을 수 있어, ACL·정책 실수 하나로 공개되지 않게 막아 둔다.
resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# 평문 HTTP 요청을 거부해 상태 파일이 암호화되지 않은 채 네트워크를 오가지 않게 한다.
# 정책은 aws_iam_policy_document 대신 jsonencode로 쓴다 — 테스트의 가짜 provider 아래에서는
# 그 데이터 소스의 json이 무작위 문자열이 돼 정책 내용을 검사할 수 없다.
resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.state.arn,
          "${aws_s3_bucket.state.arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      },
    ]
  })

  # 버킷 하위 설정을 동시에 바꾸면 S3가 충돌 오류를 내므로, 공개 차단이 끝난 뒤에 정책을 건다.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

# GitHub Actions가 비밀 키 없이 AWS에 로그인하게 하는 OIDC provider. 계정당 하나라
# staging·prod가 같이 쓴다. thumbprint_list는 두지 않는다 — AWS가 GitHub 인증서를
# 자체 신뢰 CA 목록으로 검증한다.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}
