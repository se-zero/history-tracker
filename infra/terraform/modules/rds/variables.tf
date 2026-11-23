variable "project" {
  type        = string
  description = "프로젝트 이름. 이름 접두사 \"<project>-<environment>\"(예: whycode-staging)와 비밀 이름의 앞부분이 된다."
  default     = "whycode"
}

variable "environment" {
  type        = string
  description = "Terraform 환경 이름(staging 또는 prod). 모든 자원 이름에 들어간다."

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment는 staging 또는 prod여야 한다. 이름 접두사가 모든 자원에 박히므로 오타 하나가 환경을 새로 만든다."
  }
}

variable "subnet_ids" {
  type        = list(string)
  description = "DB 서브넷 그룹에 넣을 비공개 서브넷 ID 둘(서로 다른 AZ). network 모듈의 private_subnet_ids를 그대로 넘긴다. 인스턴스는 Single-AZ라 둘 중 한 곳에만 뜬다."

  validation {
    condition     = length(var.subnet_ids) == 2
    error_message = "subnet_ids는 정확히 2개여야 한다. 하나면 RDS가 서브넷 그룹을 거부하고(서로 다른 AZ의 서브넷이 둘 이상 필요), 셋 이상이면 이 구성이 쓰지 않는 AZ에 인스턴스가 놓일 수 있다."
  }
}

variable "security_group_id" {
  type        = string
  description = "DB 인스턴스에 붙일 보안 그룹 ID. network 모듈의 rds 보안 그룹(5432를 backend·pipeline-worker에서만 받는다)을 넘긴다."
}

variable "execution_role_names" {
  type        = map(string)
  description = "서비스별 ECS 실행 역할 이름(키: backend·pipeline-worker). 이 두 역할에 DB 비밀 읽기 정책이 붙는다. platform 모듈의 execution_role_names 출력을 그대로 넘겨도 되고, 그 안의 다른 키(ai-engine)는 쓰지 않는다."
}

variable "instance_class" {
  type        = string
  description = "DB 인스턴스 종류. 기본값은 AWS 이전 계획서의 1주 운영 기준(db.t4g.small)이다."
  default     = "db.t4g.small"
}

variable "allocated_storage" {
  type        = number
  description = "할당 저장소(GiB). 기본 20은 계획서 기준이자 gp3의 최소값이다."
  default     = 20

  validation {
    condition     = var.allocated_storage >= 20
    error_message = "allocated_storage는 20 이상이어야 한다. gp3의 최소 크기가 20GiB라 그보다 작으면 RDS가 거부한다."
  }
}

variable "engine_version" {
  type        = string
  description = "PostgreSQL 엔진 버전. 메이저만 적으면(예: \"16\") RDS가 그 메이저의 기본 마이너를 고르고 자동 마이너 업그레이드가 이어 받는다. 파라미터 그룹 계열이 postgres16으로 고정이라 16.x만 쓸 수 있다."
  default     = "16"
}

variable "db_name" {
  type        = string
  description = "처음 만들 데이터베이스 이름. backend와 pipeline-worker가 이 DB 하나를 같이 쓴다. 기본값은 현재 Compose의 POSTGRES_DB와 같다."
  default     = "history_tracker"
}

variable "master_username" {
  type        = string
  description = "마스터 사용자 이름. 기본값은 현재 Compose의 POSTGRES_USER와 같다. 마이그레이션 역할과 런타임 역할을 나누는 일은 후속이라 backend와 pipeline-worker가 모두 이 사용자로 접속한다."
  default     = "history_tracker"
}

variable "backup_retention_period" {
  type        = number
  description = "자동 백업 보존 기간(일, 0~35). 0이면 자동 백업과 시점 복구(PITR)가 꺼진다. 계획서의 복구 연습이 시점 복구에 기댄다."
  default     = 7

  validation {
    condition     = var.backup_retention_period >= 0 && var.backup_retention_period <= 35
    error_message = "backup_retention_period는 0~35여야 한다. RDS 자동 백업은 최대 35일까지만 보존하고, 0은 백업을 끄는 값이다."
  }
}

variable "skip_final_snapshot" {
  type        = bool
  description = "true면 DB를 지울 때 최종 스냅샷을 만들지 않는다. 기본 false는 \"<project>-<environment>-postgres-final\" 스냅샷을 남긴다. 이름이 고정이라 같은 이름의 스냅샷이 이미 있으면 다음 삭제가 막히니, 지웠다 다시 만드는 환경은 true로 둔다."
  default     = false
}

variable "deletion_protection" {
  type        = bool
  description = "true면 DB를 지울 수 없다(destroy 포함, 먼저 false로 바꿔 apply해야 한다). 기본 false는 환경을 destroy로 지울 수 있게 하려는 값이고, 운영은 호출부에서 켠다."
  default     = false
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

variable "master_password_version" {
  type        = number
  description = "마스터 비밀번호 버전(1 이상의 정수). 쓰기 전용 인자는 이 값이 바뀔 때만 다시 전송되므로, 비밀번호를 바꿀 때와 DB만 새로 만들어지거나 apply가 중간에 실패해 DB와 비밀이 어긋났을 수 있을 때 이 값을 올린다. DB와 비밀에 같은 새 값이 한 번에 들어간다."
  default     = 1

  validation {
    condition     = var.master_password_version >= 1 && floor(var.master_password_version) == var.master_password_version
    error_message = "master_password_version은 1 이상의 정수여야 한다. 이 값이 바뀌는 것이 비밀번호를 다시 보내는 신호라 1에서 시작해 한 칸씩 올리는 번호로만 쓴다(소수나 0 이하는 몇 번째 비밀번호인지 알아보기 어렵다)."
  }
}
