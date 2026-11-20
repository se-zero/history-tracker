# 경보가 알림을 게시할 주제. kms_master_key_id는 두지 않는다 — AWS 관리형 키(alias/aws/sns)로 암호화한 주제에는
# CloudWatch 경보가 게시하지 못해 알림이 조용히 사라진다. 암호화하려면 키 정책에 cloudwatch.amazonaws.com을
# 허용한 고객 관리형 키가 따로 있어야 해서 지금은 암호화하지 않는다.
resource "aws_sns_topic" "alerts" {
  name = "${local.name_prefix}-alerts"
}

# 사람이 읽을 메일이라 email이다(email-json은 JSON 본문이 온다).
# apply만으로는 구독이 켜지지 않는다. apply 뒤 alert_email로 오는 확인 메일의 링크를 눌러야 구독이 확정되고,
# 누르기 전에는 알림이 오지 않는다.
resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# 예산은 계정 전체 지출 기준이다. cost_filter·filter_expression으로 태그를 걸지 않는다 — 비용 할당 태그를
# 청구 콘솔에서 켜지 않으면 태그로 거른 예산은 조용히 0원으로 보여 경보가 울리지 않는다.
resource "aws_budgets_budget" "monthly" {
  name         = "${local.name_prefix}-monthly"
  budget_type  = "COST"
  time_unit    = "MONTHLY"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"

  # 80%는 한도에 닿기 전에 손쓸 시간을 주는 조기 경고이고, 100%는 한도 도달이다.
  # 수신자는 SNS 주제가 아니라 이메일을 직접 쓴다. 예산 서비스가 주제에 게시하려면 주제 정책에서
  # budgets.amazonaws.com의 게시를 따로 열어야 하고, 열지 않으면 알림이 조용히 사라진다.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }
}
