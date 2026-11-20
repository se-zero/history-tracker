# 로그 그룹은 미리 만든다. 실행 역할에 logs:CreateLogGroup을 주지 않아서(iam_execution.tf)
# 컨테이너가 첫 기동 때 스스로 만들 수 없다.
resource "aws_cloudwatch_log_group" "service" {
  for_each = local.services

  name = "/${var.project}/${var.environment}/${each.key}"

  # 보존 기간을 비워 두면 로그가 영원히 쌓여 저장 요금이 끝없이 는다.
  retention_in_days = var.log_retention_days

  # skip_destroy·deletion_protection_enabled·kms_key_id는 일부러 쓰지 않는다. 환경을 지웠다 다시 만들 수 있어야 하는데,
  # skip_destroy는 destroy 뒤에도 그룹을 남겨 같은 이름으로 다시 만들 때 "이미 있다"로 실패하게 하고,
  # deletion_protection_enabled는 destroy 자체를 막는다. kms_key_id는 CloudWatch Logs가 키를 쓸 수 있게
  # 키 정책을 따로 열어야 해서 기본 키로 둔다.
}
