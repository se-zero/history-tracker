variable "region" {
  type        = string
  description = "상태 버킷을 만들 AWS 리전. envs/*/versions.tf의 backend region(ap-northeast-2)과 같아야 한다 — backend 블록은 변수를 못 써서 그쪽에 고정돼 있다."
  default     = "ap-northeast-2"
}
