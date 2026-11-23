variable "project" {
  type        = string
  description = "프로젝트 이름. 이름 접두사 \"<project>-<environment>\"(예: whycode-staging)와 비밀 이름의 앞부분이 된다."
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

variable "subnet_id" {
  type        = string
  description = "Neo4j 인스턴스를 놓을 비공개 서브넷 id. 데이터 볼륨도 이 서브넷의 AZ에 만든다. 다른 AZ의 서브넷으로 바꾸면 볼륨이 새로 만들어져(데이터 삭제) 그래프가 사라지므로, 한 번 정하면 바꾸지 않는다."
}

variable "security_group_id" {
  type        = string
  description = "Neo4j 인스턴스에 붙일 보안 그룹 id. network 모듈이 만든 것을 받는다(7687 인바운드는 ai-engine에서만 열려 있다). 이 모듈은 보안 그룹을 만들지 않는다."
}

variable "execution_role_names" {
  type        = map(string)
  description = "서비스별 ECS 실행 역할 이름(키: 서비스 이름). platform 모듈의 execution_role_names 출력을 그대로 넘긴다. Neo4j는 ai-engine만 쓰므로 \"ai-engine\" 키의 역할에만 비밀 읽기 정책을 붙인다."
}

variable "instance_type" {
  type        = string
  description = "Neo4j EC2 인스턴스 종류. 기본 t3.large(vCPU 2·RAM 8GiB). heap_size와 pagecache_size에 JVM 여유를 더한 값이 이 호스트의 RAM 안에 들어야 한다."
  default     = "t3.large"
}

variable "root_volume_size_gb" {
  type        = number
  description = "루트 디스크 크기(GiB). OS와 Docker 이미지가 쓰고 그래프 데이터는 여기 쌓이지 않는다. 8 이상이어야 한다."
  default     = 20

  validation {
    condition     = var.root_volume_size_gb >= 8
    error_message = "root_volume_size_gb는 8 이상이어야 한다. Amazon Linux 2023 이미지의 스냅샷이 8GiB라 그보다 작은 루트 디스크로는 인스턴스를 만들 수 없다."
  }
}

variable "data_volume_size_gb" {
  type        = number
  description = "그래프 데이터와 로그가 쌓이는 데이터 볼륨 크기(GiB). 10 이상이다. 늘리는 것은 제자리에서 되지만 줄이면 볼륨이 새로 만들어져(데이터 삭제) 그래프가 사라진다."
  default     = 40

  validation {
    condition     = var.data_volume_size_gb >= 10
    error_message = "data_volume_size_gb는 10 이상이어야 한다. 그래프 데이터와 트랜잭션 로그를 담기에 너무 작다."
  }
}

variable "neo4j_image" {
  type        = string
  description = "Neo4j 컨테이너 이미지. 기본은 docker-compose.yml과 같은 neo4j:5.26-community다. 바꾸면 user-data가 달라져 인스턴스가 교체되고(데이터 볼륨은 남는다) 새 이미지로 같은 데이터를 연다."
  default     = "neo4j:5.26-community"

  validation {
    condition     = length(trimspace(var.neo4j_image)) > 0
    error_message = "neo4j_image는 비어 있으면 안 된다. 컨테이너를 띄울 이미지가 없으면 부팅 스크립트가 docker run에서 멈춘다."
  }
}

variable "heap_size" {
  type        = string
  description = "Neo4j JVM 힙 크기. 숫자 뒤에 M 또는 G를 붙인다(예: 2G, 512M). 시작·최대 힙을 같은 값으로 고정한다. 기본은 docker-compose.prod.yml과 같은 2G다."
  default     = "2G"

  validation {
    condition     = can(regex("^[1-9][0-9]*[MG]$", var.heap_size))
    error_message = "heap_size는 숫자 뒤에 대문자 M 또는 G를 붙인 값이어야 한다(예: 2G, 512M). 단위가 없는 숫자와 소문자 단위(2g)는 받지 않는다. 크기를 한 가지 꼴로만 받아야 user-data의 환경변수가 항상 같은 모양이 된다."
  }
}

variable "pagecache_size" {
  type        = string
  description = "Neo4j 페이지 캐시 크기. 숫자 뒤에 M 또는 G를 붙인다(예: 1G, 512M). 기본은 docker-compose.prod.yml과 같은 1G다."
  default     = "1G"

  validation {
    condition     = can(regex("^[1-9][0-9]*[MG]$", var.pagecache_size))
    error_message = "pagecache_size는 숫자 뒤에 대문자 M 또는 G를 붙인 값이어야 한다(예: 1G, 512M). 단위가 없는 숫자와 소문자 단위(1g)는 받지 않는다. 크기를 한 가지 꼴로만 받아야 user-data의 환경변수가 항상 같은 모양이 된다."
  }
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

variable "password_version" {
  type        = number
  description = "Neo4j 비밀번호의 버전(1 이상의 정수). 값을 올리면 새 비밀번호를 만들어 비밀에 다시 쓴다. 단 Neo4j는 비밀번호를 데이터가 비어 있는 첫 기동에만 읽으므로 이미 만들어진 DB의 비밀번호는 바뀌지 않는다. DB 쪽도 따로 바꿔야 한다."
  default     = 1

  validation {
    condition     = var.password_version >= 1 && floor(var.password_version) == var.password_version
    error_message = "password_version은 1 이상의 정수여야 한다. 이 값이 바뀔 때만 비밀의 값을 다시 쓰는데, 0이나 소수는 버전으로 쓸 수 없다."
  }
}

variable "backup_bucket_force_destroy" {
  type        = bool
  description = "true면 destroy가 백업 버킷의 객체와 이전 버전까지 함께 지운다. 기본 false: 백업은 destroy가 자동으로 지우지 않는다. 지우려면 true로 먼저 apply해 상태에 반영한 뒤 destroy한다."
  default     = false
}

variable "backup_noncurrent_days" {
  type        = number
  description = "백업 객체의 이전 버전을 지우기까지의 일수(1 이상의 정수). 같은 키를 덮어쓰면 이전 버전이 된다. 현재 버전은 지우지 않는다."
  default     = 30

  validation {
    condition     = var.backup_noncurrent_days >= 1 && floor(var.backup_noncurrent_days) == var.backup_noncurrent_days
    error_message = "backup_noncurrent_days는 1 이상의 정수여야 한다. S3 수명 주기는 0일이나 소수를 받지 않는다."
  }
}
