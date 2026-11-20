terraform {
  # 1.11부터 S3 상태 잠금(use_lockfile)이 정식 기능이다. DynamoDB 잠금 테이블 없이 상태를 지킨다.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # bucket은 일부러 적지 않는다. 저장소가 공개(public)인데 버킷 이름에 AWS 계정 번호가 들어가기 때문이다.
  # backend 블록 안에서는 변수를 쓸 수 없으니 init 때 bootstrap의 출력으로 넘긴다.
  #   terraform -chdir=infra/terraform/envs/staging init -backend-config="bucket=$(terraform -chdir=infra/terraform/bootstrap output -raw state_bucket_name)"
  backend "s3" {
    key          = "staging/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
