# 모듈이라 provider 블록은 두지 않는다. 리전과 default_tags(Project·Environment·ManagedBy)는
# 이 모듈을 부르는 환경 루트의 provider가 정한다.
terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }
}
