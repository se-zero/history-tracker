variable "name" {
  type        = string
  description = "스테이징 리소스 이름 접두사"
}

variable "vpc_id" {
  type        = string
  description = "CloudFront가 서비스 관리 보안 그룹을 만드는 VPC ID"
}

variable "alb_arn" {
  type        = string
  description = "VPC 원본으로 등록할 내부 ALB ARN"
}

variable "alb_dns_name" {
  type        = string
  description = "내부 ALB의 DNS 이름"
}

variable "alb_security_group_id" {
  type        = string
  description = "CloudFront VPC 원본의 HTTP 접근을 허용할 ALB 보안 그룹 ID"
}

variable "ci_deploy_role_name" {
  type        = string
  description = "정적 파일 배포 권한을 붙일 CI 역할 이름"
}

variable "alb_listener_port" {
  type        = number
  description = "내부 ALB HTTP 리스너 포트"
  default     = 80

  validation {
    condition     = var.alb_listener_port >= 1 && var.alb_listener_port <= 65535 && floor(var.alb_listener_port) == var.alb_listener_port
    error_message = "alb_listener_port는 1~65535 사이의 정수여야 합니다."
  }
}

variable "origin_read_timeout_seconds" {
  type        = number
  description = "CloudFront가 내부 ALB 응답을 기다리는 시간(초)"
  default     = 120

  validation {
    condition     = var.origin_read_timeout_seconds >= 1 && var.origin_read_timeout_seconds <= 120 && floor(var.origin_read_timeout_seconds) == var.origin_read_timeout_seconds
    error_message = "origin_read_timeout_seconds는 1~120 사이의 정수여야 합니다."
  }
}
