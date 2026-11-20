# 서비스마다 저장소를 따로 둔다. 실행 역할은 자기 저장소만 당기고(iam_execution.tf),
# CI가 이미지를 올리는 권한도 이 저장소들의 ARN으로만 한정한다(iam_ci.tf).
resource "aws_ecr_repository" "service" {
  for_each = local.services

  name = "${local.name_prefix}/${each.key}"

  # 태그를 불변으로 둔다. 가변이면 같은 태그에 다른 이미지를 덮어쓸 수 있어서, 배포한 이미지가 무엇인지
  # 태그만으로는 믿을 수 없고 이전 태그로 되돌릴 때도 그 태그가 같은 이미지라는 보장이 없다.
  image_tag_mutability = "IMMUTABLE"

  # 이미지가 든 저장소는 force_delete 없이는 destroy가 실패한다. 환경을 통째로 지웠다 다시 만들 수 있어야 해서 켠다.
  force_delete = true

  # 푸시할 때마다 취약점 검사 결과가 남게 한다. 꺼 두면 따로 스캔을 돌리지 않는 한
  # 알려진 취약점이 든 이미지가 눈에 띄지 않고 배포된다.
  image_scanning_configuration {
    scan_on_push = true
  }

  # ECR 관리 키(AES256)를 쓴다. KMS 키를 쓰면 키 정책과 역할 권한을 따로 맞춰야 하는데,
  # 이 모듈의 정책에는 kms 권한이 없다.
  encryption_configuration {
    encryption_type = "AES256"
  }
}

# 배포할 때마다 이미지가 하나씩 늘기만 해서 개수 상한이 없으면 저장 요금이 계속 는다.
# 태그가 있든 없든 센다(tagStatus any) — 태그 없는 이미지도 저장 요금이 나간다.
resource "aws_ecr_lifecycle_policy" "service" {
  for_each = local.services

  repository = aws_ecr_repository.service[each.key].name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire images beyond the newest ${var.ecr_max_image_count}"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_max_image_count
        }
        action = {
          type = "expire"
        }
      },
    ]
  })
}
