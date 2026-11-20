variable "name" {
  type        = string
  description = "자원 이름 접두사. Name 태그와 보안 그룹 이름이 \"<name>-<역할>\" 꼴로 이 값에서 만들어진다(예: whycode-staging). 같은 계정·리전에 환경을 여러 개 두면 환경마다 달라야 한다."
}

variable "vpc_cidr" {
  type        = string
  description = "VPC 대역. 서브넷을 cidrsubnet(vpc_cidr, 8, 번호)로 나누므로 /16이면 서브넷이 /24가 된다. 공개 서브넷은 0번, 비공개 서브넷은 10번부터 쓴다."
  default     = "10.20.0.0/16"
}

variable "availability_zones" {
  type        = list(string)
  description = "서브넷을 놓을 가용 영역 2개. 첫 번째 AZ에 공개 서브넷과 NAT가 놓이고, 비공개 서브넷은 목록 순서대로 AZ마다 하나씩 놓인다."
  default     = ["ap-northeast-2a", "ap-northeast-2c"]

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "availability_zones는 정확히 2개여야 한다. 내부 ALB가 서로 다른 AZ의 서브넷 2개를 요구하고, 비공개 서브넷이 AZ마다 하나씩 놓이기 때문이다."
  }
}
