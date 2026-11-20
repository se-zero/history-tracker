terraform {
  # 1.11부터 S3 상태 잠금(use_lockfile)이 정식 기능이다. DynamoDB 잠금 테이블 없이 상태를 지킨다.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }
}
