variable "project" {
  type        = string
  description = "프로젝트 이름. 브로커 이름(<project>-<environment>-rabbitmq)과 비밀 이름(<project>/<environment>/mq)의 앞부분이 된다."
  default     = "whycode"
}

variable "environment" {
  type        = string
  description = "Terraform 환경 이름(staging 또는 prod). 브로커와 비밀 이름에 들어간다. GitHub environment 이름(staging·production)과 다르다."

  validation {
    condition     = contains(["staging", "prod"], var.environment)
    error_message = "environment는 staging 또는 prod여야 한다. 브로커·비밀 이름에 그대로 박히므로 오타 하나가 브로커를 새로 만든다. GitHub 쪽 이름(production)과도 다르다."
  }
}

variable "subnet_id" {
  type        = string
  description = "브로커를 놓을 비공개 서브넷 id. 단일 인스턴스 브로커는 서브넷 하나만 받는다. network 모듈의 private_subnet_ids 중 하나를 넘긴다."
}

variable "security_group_id" {
  type        = string
  description = "브로커에 붙일 보안 그룹 id. network 모듈의 security_group_ids.mq를 넘긴다. 누가 브로커에 접속할 수 있는지는 이 그룹의 규칙이 정한다."
}

variable "execution_role_names" {
  type        = map(string)
  description = "서비스별 ECS 실행 역할 이름(키: pipeline-worker·ai-engine). platform 모듈의 execution_role_names 출력을 넘기면 된다. 접속 비밀 읽기 정책은 이 두 역할에만 붙고, backend 같은 다른 키가 있어도 정책을 만들지 않는다."
}

variable "engine_version" {
  type        = string
  description = "RabbitMQ 엔진 버전(major.minor, 예: 4.3). 패치 버전은 AWS가 자동으로 올린다. 4.2 이상은 mq.m7g 인스턴스에서만 돈다."
  default     = "4.3"

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+$", var.engine_version))
    error_message = "engine_version은 4.3처럼 major.minor여야 한다. 4나 4.3.1 같은 값은 AWS가 받는 형식이 아니다."
  }
}

variable "host_instance_type" {
  type        = string
  description = "브로커 인스턴스 종류. RabbitMQ 4.2·4.3은 mq.m7g 계열에서만 돌아서 mq.m7g.*만 받는다. 스테이징 검증은 medium(실습용 후보), 운영 견적은 large가 시작점이다."
  default     = "mq.m7g.medium"

  validation {
    condition     = startswith(var.host_instance_type, "mq.m7g.")
    error_message = "host_instance_type은 mq.m7g.로 시작해야 한다(예: mq.m7g.medium). RabbitMQ 4.2 이상은 mq.t3·mq.m5 같은 다른 계열에서 돌지 않는다."
  }
}

variable "admin_username" {
  type        = string
  description = "브로커 관리자 사용자 이름. RabbitMQ는 생성 때 관리자 하나만 받고, 이 값이 바뀌면 브로커가 새로 만들어진다. 2~100자의 영문·숫자·점·밑줄·하이픈만 쓰고 guest는 쓸 수 없다."
  default     = "whycode_admin"

  validation {
    condition     = can(regex("^[A-Za-z0-9._-]{2,100}$", var.admin_username))
    error_message = "admin_username은 2~100자의 영문·숫자·점·밑줄·하이픈이어야 한다. 공백이나 ~ 같은 문자가 들어 있거나 길이가 이 범위 밖이면 안 된다."
  }

  validation {
    condition     = lower(var.admin_username) != "guest"
    error_message = "admin_username은 guest(대소문자 무관)일 수 없다. Amazon MQ가 이 이름을 사용자 이름으로 받지 않는다."
  }
}

variable "secret_recovery_window_days" {
  type        = number
  description = "접속 비밀 삭제 유예 기간(일). 0이면 지우는 즉시 사라진다. 환경을 지웠다 같은 이름으로 다시 만들 수 있게 기본은 0이다. 0이 아니면 7~30의 정수여야 한다."
  default     = 0

  validation {
    condition     = var.secret_recovery_window_days == 0 || contains(range(7, 31), var.secret_recovery_window_days)
    error_message = "secret_recovery_window_days는 0 또는 7~30의 정수여야 한다. Secrets Manager는 1~6일과 31일 이상, 소수를 거부한다."
  }
}

variable "secret_version" {
  type        = number
  description = "접속 비밀 값의 버전 번호(1 이상의 정수). 쓰기 전용 인자는 버전이 바뀔 때만 다시 전송되므로, 브로커는 그대로인데 비밀 값만 다시 쓰고 싶으면 올린다. 브로커가 새로 만들어질 때는 브로커 id가 버전에 섞여 자동으로 다시 쓰이니 올릴 필요가 없다."
  default     = 1

  validation {
    condition     = var.secret_version >= 1 && floor(var.secret_version) == var.secret_version
    error_message = "secret_version은 1 이상의 정수여야 한다. 기본값 1에서 하나씩 올리는 번호라 0·음수·소수는 쓰지 않는다."
  }
}
