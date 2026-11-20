# 비밀의 "껍데기"(이름·설명)만 만든다. 값(aws_secretsmanager_secret_version)은 Terraform으로 넣지 않는다 —
# 넣으면 값이 코드와 상태 파일에 평문으로 남는다. 값은 사람이 CLI로 넣는다.
#
# 비밀은 서비스별이 아니라 "읽는 서비스 조합"별로 나눈다(var.app_secrets의 readers).
# 읽기 권한(GetSecretValue)은 비밀 단위라서 비밀 안의 JSON 키 일부만 골라 읽게 할 수 없기 때문이다.
# 둘 이상이 같은 값을 읽어야 하면 그 조합만 읽는 공용 비밀에 두고, 한 서비스만 읽을 값은 그 서비스 전용 비밀에 둔다.
# 그래야 서비스별 실행 역할이 자기 비밀만 읽는다.
resource "aws_secretsmanager_secret" "app" {
  for_each = var.app_secrets

  name        = "${var.project}/${var.environment}/${each.key}"
  description = each.value.description

  # 기본 0: 지우면 바로 사라진다. 7~30일을 두면 삭제 예약 상태의 비밀은 같은 이름으로 다시 만들 수 없어서,
  # 환경을 지웠다 다시 만들 때 "삭제 예약된 이름" 오류로 막힌다.
  recovery_window_in_days = var.secret_recovery_window_days

  # kms_key_id·replica는 쓰지 않는다. 고객 관리형 키를 쓰면 실행 역할에 kms:Decrypt가 있어야 하는데 정책에 없어서
  # 컨테이너가 비밀을 읽지 못하고, 복제는 다른 리전에 사본(과 요금)을 더 만든다.
}
