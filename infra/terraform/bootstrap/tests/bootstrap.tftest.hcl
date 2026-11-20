# T0 부트스트랩 루트(Terraform 상태 버킷 + GitHub Actions OIDC provider)의 불변식 테스트.
# 가짜 provider로만 돌아 실제 AWS에는 닿지 않는다. 실행: terraform -chdir=infra/terraform/bootstrap test
#
# 가짜 provider는 계산 속성(ARN·id 등)을 apply 단계에서만 채우므로 모든 run은 기본값 command = apply로 둔다.
# plan으로 바꾸면 계산값에 기대는 assert가 "Unknown condition value" 오류로 끝난다.
# run이 assert 실패가 아니라 오류로 끝나면 뒤 run이 전부 skip되므로, 값이 없을 수 있는 곳은 try()로 감싸 실패로 끝나게 한다.

# 가짜 aws provider. 이 블록이 빠지면 terraform test가 실제 AWS 계정에 리소스를 만든다 — 지우지 않는다.
# 루트에 alias가 붙은 aws provider를 더하면 같은 alias의 mock_provider도 여기에 더한다(빠진 alias는 진짜 provider로 돈다).
mock_provider "aws" {}

# 가짜 provider는 계정 번호도 무작위 8글자로 만들므로, 버킷 이름을 비교할 수 있게 12자리 번호로 고정한다.
override_data {
  target = data.aws_caller_identity.current
  values = {
    account_id = "123456789012"
  }
}

# 계정 번호 없는 버킷 이름(S3 이름은 전 세계 공용이라 다른 계정과 부딪친다)과 force_destroy = true(destroy가 상태 파일째 버킷을 지운다)를 막는다.
run "state_bucket_named_per_account_without_force_destroy" {
  assert {
    condition     = aws_s3_bucket.state.bucket == "whycode-tfstate-123456789012"
    error_message = "상태 버킷 이름은 whycode-tfstate-<계정 번호>여야 한다. 지금 값: ${jsonencode(aws_s3_bucket.state.bucket)}"
  }

  assert {
    # 적지 않으면 가짜 provider 아래에서 null이다(실제 기본값은 false). 그래서 null과 false를 모두 통과시킨다.
    condition     = aws_s3_bucket.state.force_destroy != true
    error_message = "상태 버킷에 force_destroy = true를 두면 안 된다. terraform destroy가 상태 파일이 든 버킷을 비우고 지운다."
  }
}

# 버전 관리가 꺼지는 것을 막는다 — 꺼지면 덮어쓰거나 깨진 상태 파일을 이전 버전으로 되돌릴 수 없다.
run "state_bucket_versioning_enabled" {
  assert {
    condition     = try(one(aws_s3_bucket_versioning.state.versioning_configuration).status, null) == "Enabled"
    error_message = "상태 버킷 버전 관리는 Enabled여야 한다. 지금 값: ${jsonencode(aws_s3_bucket_versioning.state.versioning_configuration)}"
  }

  assert {
    condition     = aws_s3_bucket_versioning.state.bucket == aws_s3_bucket.state.id
    error_message = "버전 관리가 상태 버킷에 연결돼 있지 않다(bucket = aws_s3_bucket.state.id여야 한다). 지금 bucket: ${jsonencode(aws_s3_bucket_versioning.state.bucket)}, 상태 버킷 id: ${aws_s3_bucket.state.id}"
  }
}

# 기본 암호화가 빠지거나 AES256(S3 관리 키)이 아닌 방식으로 바뀌는 것을 막는다 — AES256은 KMS 키 권한 없이 S3 권한만으로 상태를 읽고 쓸 수 있다.
run "state_bucket_encrypted_with_aes256" {
  assert {
    condition     = length(aws_s3_bucket_server_side_encryption_configuration.state.rule) == 1
    error_message = "상태 버킷 암호화 규칙(rule)은 하나여야 한다. 지금 개수: ${length(aws_s3_bucket_server_side_encryption_configuration.state.rule)}"
  }

  assert {
    # rule은 집합(set)이라 인덱스로 못 읽는다. for로 돌며 안쪽 블록(최대 1개)을 one()으로 꺼낸다.
    condition = alltrue([
      for r in aws_s3_bucket_server_side_encryption_configuration.state.rule :
      try(one(r.apply_server_side_encryption_by_default).sse_algorithm, null) == "AES256"
    ])
    error_message = "상태 버킷 기본 암호화는 sse_algorithm = AES256이어야 한다. 지금 값: ${jsonencode(aws_s3_bucket_server_side_encryption_configuration.state.rule)}"
  }

  assert {
    condition     = aws_s3_bucket_server_side_encryption_configuration.state.bucket == aws_s3_bucket.state.id
    error_message = "암호화 설정이 상태 버킷에 연결돼 있지 않다(bucket = aws_s3_bucket.state.id여야 한다). 지금 bucket: ${jsonencode(aws_s3_bucket_server_side_encryption_configuration.state.bucket)}, 상태 버킷 id: ${aws_s3_bucket.state.id}"
  }
}

# 공개 차단 네 항목 중 하나라도 꺼지는 것을 막는다 — 꺼지면 ACL·버킷 정책 실수 하나로 상태 파일(비밀값이 평문으로 남을 수 있다)이 공개될 수 있다.
run "state_bucket_public_access_blocked" {
  assert {
    condition     = aws_s3_bucket_public_access_block.state.block_public_acls == true
    error_message = "block_public_acls가 true가 아니다. 지금 값: ${jsonencode(aws_s3_bucket_public_access_block.state.block_public_acls)}"
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.state.block_public_policy == true
    error_message = "block_public_policy가 true가 아니다. 지금 값: ${jsonencode(aws_s3_bucket_public_access_block.state.block_public_policy)}"
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.state.ignore_public_acls == true
    error_message = "ignore_public_acls가 true가 아니다. 지금 값: ${jsonencode(aws_s3_bucket_public_access_block.state.ignore_public_acls)}"
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.state.restrict_public_buckets == true
    error_message = "restrict_public_buckets가 true가 아니다. 지금 값: ${jsonencode(aws_s3_bucket_public_access_block.state.restrict_public_buckets)}"
  }

  assert {
    condition     = aws_s3_bucket_public_access_block.state.bucket == aws_s3_bucket.state.id
    error_message = "공개 차단이 상태 버킷에 연결돼 있지 않다(bucket = aws_s3_bucket.state.id여야 한다). 지금 bucket: ${jsonencode(aws_s3_bucket_public_access_block.state.bucket)}, 상태 버킷 id: ${aws_s3_bucket.state.id}"
  }
}

# TLS가 아닌(평문 HTTP) 요청을 거부하는 문이 빠지거나 느슨해지는 것을 막는다 — 빠지면 상태 파일이 암호화되지 않은 채 네트워크를 오갈 수 있다.
run "state_bucket_policy_denies_insecure_transport" {
  assert {
    # 정책은 jsonencode()로 쓴다는 계약을 전제로 읽는다. aws_iam_policy_document의 json은 가짜 provider 아래에서 무작위 문자열이라,
    # 여기 오기 전에 provider 검증("policy" contains an invalid JSON)에 걸려 첫 run부터 오류가 나고 나머지 run은 skip된다.
    # 문은 순서가 아니라 내용으로 고른다. Statement·Action·Resource는 값 하나든 목록이든 flatten으로 목록으로 맞춘다.
    condition = anytrue([
      for s in flatten([try(jsondecode(aws_s3_bucket_policy.state.policy).Statement, [])]) : (
        try(s.Effect, null) == "Deny"
        && try(s.Principal, null) == "*"
        && contains(try(flatten([s.Action]), []), "s3:*")
        && contains(try(flatten([s.Resource]), []), aws_s3_bucket.state.arn)
        && contains(try(flatten([s.Resource]), []), "${aws_s3_bucket.state.arn}/*")
        && try([for v in flatten([s.Condition.Bool["aws:SecureTransport"]]) : tostring(v)], []) == ["false"]
      )
    ])
    error_message = "TLS가 아닌 요청을 거부하는 문이 없다. Effect = Deny, Principal = *, Action에 s3:*, Resource에 버킷 ARN과 <버킷 ARN>/*, Condition.Bool의 aws:SecureTransport = false를 모두 갖춘 문이 있어야 한다. 지금 버킷 ARN(가짜 provider가 만든 값): ${aws_s3_bucket.state.arn}, 지금 정책: ${aws_s3_bucket_policy.state.policy}"
  }

  assert {
    condition     = aws_s3_bucket_policy.state.bucket == aws_s3_bucket.state.id
    error_message = "버킷 정책이 상태 버킷에 연결돼 있지 않다(bucket = aws_s3_bucket.state.id여야 한다). 지금 bucket: ${jsonencode(aws_s3_bucket_policy.state.bucket)}, 상태 버킷 id: ${aws_s3_bucket.state.id}"
  }
}

# OIDC provider 주소가 틀리거나 허용 audience가 sts.amazonaws.com 하나에서 늘어나는 것을 막는다 — 주소가 틀리면 GitHub Actions가 로그인하지 못하고, audience가 늘면 다른 용도로 발급된 토큰도 받아들인다.
run "github_oidc_provider_trusts_only_sts_audience" {
  assert {
    condition     = aws_iam_openid_connect_provider.github.url == "https://token.actions.githubusercontent.com"
    error_message = "OIDC provider url은 https://token.actions.githubusercontent.com이어야 한다. 지금 값: ${jsonencode(aws_iam_openid_connect_provider.github.url)}"
  }

  assert {
    # client_id_list는 집합(set)이라 목록 리터럴과 바로 비교하면 늘 false다. 둘 다 집합으로 맞춰 비교한다.
    condition     = toset(aws_iam_openid_connect_provider.github.client_id_list) == toset(["sts.amazonaws.com"])
    error_message = "OIDC provider client_id_list는 정확히 [\"sts.amazonaws.com\"]이어야 한다. 지금 값: ${jsonencode(aws_iam_openid_connect_provider.github.client_id_list)}"
  }
}

# 출력이 엉뚱한 값을 내보내는 것을 막는다 — 두 출력은 다음 단계(상태 backend 설정, 배포 역할의 신뢰 정책)로 넘길 값이다.
run "outputs_point_to_bootstrap_resources" {
  assert {
    condition     = output.state_bucket_name == aws_s3_bucket.state.bucket
    error_message = "state_bucket_name 출력이 상태 버킷 이름(aws_s3_bucket.state.bucket)과 다르다. 지금 값: ${jsonencode(output.state_bucket_name)}"
  }

  assert {
    condition     = output.github_oidc_provider_arn == aws_iam_openid_connect_provider.github.arn
    error_message = "github_oidc_provider_arn 출력이 OIDC provider ARN(aws_iam_openid_connect_provider.github.arn)과 다르다. 지금 값: ${jsonencode(output.github_oidc_provider_arn)}"
  }
}
