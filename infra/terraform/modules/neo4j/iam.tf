# 인스턴스가 쓰는 역할(EC2 인스턴스 프로필). 이 모듈의 모든 정책은 aws_iam_policy_document 대신 jsonencode로 쓴다.
# 테스트의 가짜 provider 아래에서는 그 데이터 소스의 json이 무작위 문자열이 돼 정책 내용을 읽어 검사할 수 없다.
resource "aws_iam_role" "neo4j" {
  name = "${local.name_prefix}-neo4j"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "ec2.amazonaws.com" }
        Action    = "sts:AssumeRole"
      },
    ]
  })
}

# 공인 IP도 SSH도 없이 SSM Session Manager로만 접속한다. SSM 에이전트가 필요로 하는 권한이 이 관리형 정책 하나에 있다.
resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.neo4j.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 문은 세 개뿐이고 삭제 권한과 와일드카드 Action은 없다. 인스턴스가 뚫려도 백업을 지우거나 다른 비밀을 읽지 못한다.
# 권한은 이 정책과 위 SSM 정책에서만 주고, 다른 관리형 정책이나 inline_policy 블록은 붙이지 않는다(붙이면 이 정책의 최소 권한을 우회한다).
resource "aws_iam_role_policy" "neo4j" {
  role = aws_iam_role.neo4j.name
  name = "neo4j-instance"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      # 부팅 스크립트가 비밀번호를 읽는다. 자기 비밀 하나만 읽는다.
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.neo4j.arn]
      },
      # 버킷 수준 작업은 버킷 ARN에, 객체 수준 작업은 "/*"가 붙은 ARN에 걸어야 해서 문이 둘로 나뉜다.
      {
        Effect   = "Allow"
        Action   = ["s3:GetBucketLocation", "s3:ListBucket"]
        Resource = [aws_s3_bucket.backup.arn]
      },
      # 덤프를 올리고, 복원할 때 다시 읽는다. 끝나지 않은 멀티파트 업로드를 정리하는 권한은 주지만 객체 삭제는 주지 않는다.
      {
        Effect   = "Allow"
        Action   = ["s3:AbortMultipartUpload", "s3:GetObject", "s3:PutObject"]
        Resource = ["${aws_s3_bucket.backup.arn}/*"]
      },
    ]
  })
}

resource "aws_iam_instance_profile" "neo4j" {
  name = "${local.name_prefix}-neo4j"
  role = aws_iam_role.neo4j.name
}

# ai-engine 컨테이너가 시작될 때 ECS가 실행 역할로 이 비밀의 값을 읽어 환경변수로 넣는다. 그 실행 역할은 platform 모듈이
# 만들었으므로 이 모듈은 역할을 만들지 않고 정책만 덧붙인다. 비밀 하나의 GetSecretValue만 허용하고 와일드카드는 쓰지 않는다.
# 실행 역할의 기본 정책(platform의 base)은 자기 앱 비밀만 읽게 되어 있어 Neo4j 비밀은 여기서 따로 열어 준다.
resource "aws_iam_role_policy" "read_secret" {
  for_each = local.secret_readers

  role = var.execution_role_names[each.key]
  name = "read-neo4j-secret"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = [aws_secretsmanager_secret.neo4j.arn]
      },
    ]
  })
}
