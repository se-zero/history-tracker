variable "name" {
  type        = string
  description = "ALB와 대상 그룹 이름의 공통 접두사"

  validation {
    condition     = can(regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,22}[A-Za-z0-9])?$", var.name)) && !startswith(lower(var.name), "internal-") && lower(var.name) != "internal"
    error_message = "name은 영문·숫자·하이픈 1~24자이며 양 끝이 영문이나 숫자여야 하고, internal 또는 internal-로 시작하는 이름은 쓸 수 없습니다."
  }
}

variable "vpc_id" {
  type        = string
  description = "ALB와 IP 대상 그룹을 둘 VPC"
}

variable "security_group_id" {
  type        = string
  description = "CloudFront VPC 원본에서 오는 트래픽을 받을 ALB 보안 그룹"
}

variable "subnet_ids" {
  type        = set(string)
  description = "가용 영역이 서로 다른 비공개 서브넷 두 개"

  validation {
    condition     = length(var.subnet_ids) == 2
    error_message = "서로 다른 비공개 서브넷 두 개를 지정해야 합니다."
  }
}

variable "idle_timeout_seconds" {
  type        = number
  description = "ALB 연결이 유휴 상태일 수 있는 최대 시간(초)"
  default     = 130

  validation {
    condition     = var.idle_timeout_seconds >= 1 && var.idle_timeout_seconds <= 4000 && floor(var.idle_timeout_seconds) == var.idle_timeout_seconds
    error_message = "idle_timeout_seconds는 1~4000 사이의 정수여야 합니다."
  }
}

variable "deregistration_delay_seconds" {
  type        = number
  description = "등록 해제 대상에서 진행 중인 연결이 끝나기를 기다리는 시간(초)"
  default     = 30

  validation {
    condition     = var.deregistration_delay_seconds >= 0 && var.deregistration_delay_seconds <= 3600 && floor(var.deregistration_delay_seconds) == var.deregistration_delay_seconds
    error_message = "deregistration_delay_seconds는 0~3600 사이의 정수여야 합니다."
  }
}

variable "health_check_path" {
  type        = string
  description = "backend와 pipeline-worker의 준비 상태를 확인할 HTTP 경로"
  default     = "/actuator/health/readiness"
}
