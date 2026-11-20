# ECS 실행 역할은 컨테이너를 띄울 때 ECS가 대신 쓰는 역할이다. 이미지를 당기고, 로그를 쓰고, 비밀을 환경변수로 주입한다.
# 컨테이너 안의 앱 코드가 AWS를 부를 때 쓰는 권한(task role)과는 별개이고 이 모듈의 범위가 아니다.
# 서비스마다 역할을 따로 둔다. 한 역할을 나눠 쓰면 서비스별로 읽을 수 있는 비밀을 나눌 수 없다.
#
# 이 모듈의 모든 정책은 aws_iam_policy_document 대신 jsonencode로 쓴다. 테스트의 가짜 provider 아래에서는
# 그 데이터 소스의 json이 무작위 문자열이 돼 정책 내용을 읽어 검사할 수 없다.
resource "aws_iam_role" "execution" {
  for_each = local.services

  name = "${local.name_prefix}-${each.key}-exec"

  # 신뢰 주체 ecs-tasks는 모든 AWS 계정이 함께 쓰는 서비스다. aws:SourceAccount로 이 계정에서 시작된 작업만
  # 역할을 쓰게 좁힌다. 빠지면 다른 계정의 요청이 ecs-tasks를 거쳐 이 역할을 쓸 여지가 남는다(confused deputy).
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = data.aws_caller_identity.current.account_id
          }
        }
      },
    ]
  })
}

# 문은 네 개뿐이고 와일드카드 Action은 없다. 권한은 이 정책 하나에서만 주고, 관리형 정책이나 inline_policy 블록은
# 붙이지 않는다(붙이면 이 정책의 최소 권한을 우회한다).
resource "aws_iam_role_policy" "execution" {
  for_each = local.services

  role = aws_iam_role.execution[each.key].id
  name = "base"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # 토큰 발급은 특정 자원을 지정할 수 없는 작업이라 Resource가 "*"여야 한다. "*"를 쓰는 문은 이것 하나뿐이다.
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = ["*"]
      },
      # 자기 서비스의 이미지만 당긴다.
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:GetDownloadUrlForLayer",
          "ecr:BatchGetImage",
        ]
        Resource = [aws_ecr_repository.service[each.key].arn]
      },
      # 로그 그룹은 미리 만들어 두므로 logs:CreateLogGroup은 주지 않는다. 끝의 ":*"는 그룹 안의 로그 스트림을 가리킨다.
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = ["${aws_cloudwatch_log_group.service[each.key].arn}:*"]
      },
      # readers에 이 서비스가 든 비밀만 읽는다. 비밀 이름을 정책에 적지 않고 app_secrets에서 계산해,
      # 비밀 정의를 바꾸면 읽기 대상도 같이 바뀌게 한다.
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [for k, s in var.app_secrets : aws_secretsmanager_secret.app[k].arn if contains(s.readers, each.key)]
      },
    ]
  })
}
