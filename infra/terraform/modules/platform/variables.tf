variable "project" {
  type        = string
  description = "프로젝트 이름. 이름 접두사 \"<project>-<environment>\"(예: whycode-staging)와 로그 그룹·비밀 이름의 앞부분이 된다."
  default     = "whycode"
}

variable "environment" {
  type        = string
  description = "Terraform 환경 이름(staging 또는 prod). 모든 자원 이름에 들어간다. GitHub environment 이름(staging·production)과 다르다."

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment는 staging 또는 prod여야 한다. 이름 접두사가 모든 자원에 박히므로 오타 하나가 환경을 새로 만든다. GitHub 쪽 이름(production)과도 다르다."
  }
}

variable "github_repository" {
  type        = string
  description = "CI 역할을 빌릴 수 있는 GitHub 저장소(<소유자>/<저장소>, 예: se-zero/history-tracker). OIDC 신뢰 정책의 sub 조건에 그대로 들어간다."

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository는 <소유자>/<저장소> 꼴이어야 하고 영문·숫자·점·밑줄·하이픈만 쓴다. 와일드카드(*)나 경로가 더 붙은 값은 신뢰 정책의 sub를 넓히거나 어떤 워크플로와도 일치하지 않게 만든다."
  }
}

variable "github_environment" {
  type        = string
  description = "CI 역할을 빌릴 수 있는 GitHub environment 이름(staging 또는 production). OIDC 신뢰 정책의 sub 조건에 들어간다. Terraform 환경 이름(prod)과 다르다."

  validation {
    condition     = contains(["staging", "production"], var.github_environment)
    error_message = "github_environment는 staging 또는 production이어야 한다. 신뢰 정책의 sub에 들어가는 값이라 오타가 나면 아무 워크플로도 역할을 빌리지 못한다. Terraform 쪽 이름(prod)과도 다르다."
  }
}

variable "github_oidc_provider_arn" {
  type        = string
  description = "GitHub Actions OIDC provider ARN. bootstrap의 github_oidc_provider_arn 출력을 넘긴다. CI 역할 신뢰 정책의 주체가 된다."
}

variable "alert_email" {
  type        = string
  description = "알림을 받을 이메일. SNS 주제 구독과 예산 알림의 수신자다. SNS 구독은 apply 뒤 이 주소로 오는 확인 메일을 눌러야 켜진다."

  validation {
    condition     = can(regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", var.alert_email))
    error_message = "alert_email은 이메일 주소 꼴(이름@도메인.최상위)이어야 한다. 공백이나 @가 둘인 값, 도메인에 점이 없는 값은 구독 확인 메일이 가지 않아 알림이 영영 오지 않는다."
  }
}

variable "monthly_budget_usd" {
  type        = number
  description = "월 예산 한도(USD). 계정 전체 지출 기준이고, 실제 지출이 이 한도의 80%와 100%를 넘으면 alert_email로 메일이 간다."

  validation {
    condition     = var.monthly_budget_usd > 0
    error_message = "monthly_budget_usd는 0보다 커야 한다. 한도 0은 첫 요금부터 경보가 울려 의미 있는 한도가 아니다."
  }
}

variable "log_retention_days" {
  type        = number
  description = "로그 그룹 보존 기간(일). CloudWatch Logs가 받는 값(예: 7, 14, 30)만 쓸 수 있다. 무기한 보존은 저장 요금이 끝없이 늘어서 두지 않는다."
  default     = 14
}

variable "ecr_max_image_count" {
  type        = number
  description = "ECR 저장소마다 남길 이미지 개수. 이 개수를 넘으면 오래된 이미지부터 만료된다."
  default     = 20
}

variable "secret_recovery_window_days" {
  type        = number
  description = "비밀 삭제 유예 기간(일). 0이면 지우는 즉시 사라진다. 환경을 지웠다 같은 이름으로 다시 만들 수 있게 기본은 0이다. 0이 아니면 7~30의 정수여야 한다."
  default     = 0

  validation {
    condition     = var.secret_recovery_window_days == 0 || contains(range(7, 31), var.secret_recovery_window_days)
    error_message = "secret_recovery_window_days는 0 또는 7~30의 정수여야 한다. Secrets Manager는 1~6일과 31일 이상, 소수를 거부한다."
  }
}

variable "app_secrets" {
  type = map(object({
    description = string
    readers     = list(string)
  }))
  description = "앱 비밀의 껍데기 목록. 키는 비밀 이름의 끝부분(<project>/<environment>/<키>)이고, readers는 그 비밀을 읽을 수 있는 서비스(backend·pipeline-worker·ai-engine)다. 서비스별 실행 역할은 자기가 reader인 비밀만 읽는다. description은 영어 ASCII 한 줄이다. 값은 코드에 두지 않고 사람이 CLI로 넣는다."

  default = {
    "backend" = {
      description = "Backend-only application secrets (JSON object, one key per environment variable)"
      readers     = ["backend"]
    }
    "pipeline-worker" = {
      description = "Pipeline-worker-only application secrets (JSON object, one key per environment variable)"
      readers     = ["pipeline-worker"]
    }
    "ai-engine" = {
      description = "AI-engine-only application secrets (JSON object, one key per environment variable)"
      readers     = ["ai-engine"]
    }
    "shared-credentials" = {
      description = "Application secrets shared by backend and pipeline-worker (JSON object, one key per environment variable)"
      readers     = ["backend", "pipeline-worker"]
    }
    "shared-alert" = {
      description = "Application secrets shared by pipeline-worker and ai-engine (JSON object, one key per environment variable)"
      readers     = ["pipeline-worker", "ai-engine"]
    }
    "shared-internal" = {
      description = "Application secrets shared by backend, pipeline-worker and ai-engine (JSON object, one key per environment variable)"
      readers     = ["backend", "pipeline-worker", "ai-engine"]
    }
  }

  validation {
    condition     = alltrue(flatten([for s in var.app_secrets : [for r in s.readers : contains(local.services, r)]]))
    error_message = "app_secrets의 readers에는 backend·pipeline-worker·ai-engine만 쓸 수 있다. 오타가 나면 그 비밀을 읽는 서비스가 조용히 하나도 없게 된다."
  }
}
