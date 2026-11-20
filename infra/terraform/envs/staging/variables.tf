variable "region" {
  type        = string
  description = "리소스를 만들 AWS 리전"
  default     = "ap-northeast-2"
}

variable "github_repository" {
  type        = string
  description = "배포 역할을 빌릴 수 있는 GitHub 저장소(owner/repo). 포크해서 쓰면 바꾼다."
  default     = "se-zero/history-tracker"
}

# 아래 둘은 기본값이 없다. 개인 값이라 레포에 두지 않고, 빠뜨리면 plan이 값을 물어서 멈춘다.
# envs/staging/terraform.auto.tfvars.example을 terraform.auto.tfvars로 복사해 채운다(gitignore 대상).
variable "alert_email" {
  type        = string
  description = "예산·알림 수신 이메일. 개인 정보라 terraform.auto.tfvars(gitignore) 또는 TF_VAR_alert_email로 넣는다."
}

variable "monthly_budget_usd" {
  type        = number
  description = "월 예산(USD). 계정 전체 지출 기준이고 80%·100%에 이메일로 알린다. 계획서 5절 견적에 여유를 둔 값을 넣는다."
}
