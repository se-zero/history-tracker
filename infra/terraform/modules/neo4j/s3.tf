# Neo4j 덤프를 보관하는 버킷. 보호 장치(공개 차단·소유권 강제·버전 관리·기본 암호화·수명 주기)는 edge 모듈의 웹 버킷과 같다.
# 버킷 이름은 전역 이름이라 계정 번호를 붙여 다른 AWS 계정과 충돌하지 않게 한다.
#
# force_destroy는 변수로 두고 기본은 끈다. 웹 산출물과 달리 백업은 다시 만들 수 없어서, 비어 있지 않은 버킷은 destroy가
# 자동으로 지우지 않아야 한다(destroy가 이 버킷에서 멈추면 사람이 비우고 지운다). 스테이징처럼 지워도 되는 환경만 켠다.
resource "aws_s3_bucket" "backup" {
  bucket        = "${local.name_prefix}-neo4j-backup-${data.aws_caller_identity.current.account_id}"
  force_destroy = var.backup_bucket_force_destroy
}

resource "aws_s3_bucket_public_access_block" "backup" {
  bucket                  = aws_s3_bucket.backup.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backup" {
  bucket = aws_s3_bucket.backup.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 같은 키로 덤프를 다시 올려 덮어써도 이전 덤프가 이전 버전으로 남는다.
resource "aws_s3_bucket_versioning" "backup" {
  bucket = aws_s3_bucket.backup.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 이전 버전은 되돌릴 시간을 둔 뒤 지운다. 현재 버전은 지우지 않는다. 가장 최근 백업이 저절로 사라지면 안 되기 때문이다.
resource "aws_s3_bucket_lifecycle_configuration" "backup" {
  bucket = aws_s3_bucket.backup.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.backup_noncurrent_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.backup]
}

# 정책은 TLS가 아닌 요청을 거부하는 문 하나뿐이다. 허용 문은 두지 않는다 — 인스턴스 역할은 같은 계정이라
# 자기 IAM 정책(iam.tf)만으로 접근하고, 버킷 정책에 허용을 적으면 접근 경로가 둘로 늘어 권한을 따라가기 어려워진다.
resource "aws_s3_bucket_policy" "backup" {
  bucket = aws_s3_bucket.backup.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.backup.arn, "${aws_s3_bucket.backup.arn}/*"]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })

  # 공개 접근 차단과 정책을 동시에 쓰면 S3가 충돌로 거절하는 일이 있어 차례를 둔다.
  depends_on = [aws_s3_bucket_public_access_block.backup]
}
