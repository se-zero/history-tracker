# GitHub Actions가 비밀 키 없이 OIDC 토큰으로 빌리는 CI 역할 둘의 신뢰 정책. 허용되는 것은 github_repository의
# github_environment 환경에서 도는 워크플로뿐이다. 두 역할이 같은 정의를 쓰게 해서 조건이 서로 어긋나지 않게 한다.
#
# sub는 StringEquals로 정확히 고정한다. StringLike나 와일드카드(repo:owner/repo:*)로 넓히면 같은 저장소의
# 다른 브랜치·환경 워크플로까지 역할을 빌린다. aud도 걸어, 다른 용도로 발급된 토큰은 받지 않는다.
locals {
  ci_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Federated = var.github_oidc_provider_arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
            "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:environment:${var.github_environment}"
          }
        }
      },
    ]
  })
}

# 이미지를 ECR에 올리는 역할.
resource "aws_iam_role" "ci_push" {
  name               = "${local.name_prefix}-ci-push"
  assume_role_policy = local.ci_assume_role_policy
}

# 배포하는 역할. 이 모듈에서는 권한을 붙이지 않는다 — 배포 대상(ECS 서비스·정적 자산)이 아직 이 모듈에 없어서
# 권한을 좁힐 자원이 정해지지 않았다. 다음 묶음이 ci_deploy_role_name 출력을 받아 정책을 붙인다.
resource "aws_iam_role" "ci_deploy" {
  name               = "${local.name_prefix}-ci-deploy"
  assume_role_policy = local.ci_assume_role_policy
}

# 토큰 발급은 특정 자원을 지정할 수 없는 작업이라 "*"이고, 레이어를 올리고 이미지를 등록하는 다섯 작업은
# 세 저장소로만 한정한다. 저장소를 지우거나 정책을 바꾸는 권한은 없다.
resource "aws_iam_role_policy" "ci_push" {
  role = aws_iam_role.ci_push.id
  name = "ecr-push"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = ["*"]
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:PutImage",
        ]
        Resource = [for r in aws_ecr_repository.service : r.arn]
      },
    ]
  })
}
