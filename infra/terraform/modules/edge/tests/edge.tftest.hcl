# CloudFront 라우팅, S3 원본 제한, CI 권한을 AWS에 접속하지 않고 검사한다.
mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }

  mock_data "aws_security_group" {
    defaults = {
      id = "sg-0fedcba9876543210"
    }
  }
}

override_resource {
  target = aws_cloudfront_function.spa_rewrite
  values = {
    arn = "arn:aws:cloudfront::123456789012:function/whycode-test-spa-rewrite"
  }
}

override_resource {
  target = aws_cloudfront_function.viewer_request
  values = {
    arn = "arn:aws:cloudfront::123456789012:function/whycode-test-viewer-request"
  }
}

override_resource {
  target = aws_cloudfront_distribution.main
  values = {
    arn         = "arn:aws:cloudfront::123456789012:distribution/E1TEST01234567"
    domain_name = "d111111abcdef8.cloudfront.net"
  }
}

override_resource {
  target = aws_s3_bucket.web
  values = {
    arn                         = "arn:aws:s3:::whycode-test-web-123456789012"
    bucket_regional_domain_name = "whycode-test-web-123456789012.s3.ap-northeast-2.amazonaws.com"
  }
}

variables {
  name                  = "whycode-test"
  vpc_id                = "vpc-0123456789abcdef0"
  alb_arn               = "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/whycode-test-alb/0123456789abcdef"
  alb_dns_name          = "internal-whycode-test-alb-123456789.ap-northeast-2.elb.amazonaws.com"
  alb_security_group_id = "sg-0123456789abcdef0"
  ci_deploy_role_name   = "whycode-test-ci-deploy"
}

# 계약 밖 자원이 덧붙는 것을 막는다 — 아래 run들은 이름이 적힌 자원만 볼 수 있어서, 다른 이름으로 덧붙인 자원은 모든 run을 통과한다.
# 예: 공개 차단을 끄는 두 번째 aws_s3_bucket_public_access_block, 다른 버킷 정책, WAF, ACM 인증서.
# 모듈 소스에 선언된 자원 주소를 16개와 직접 대조한다. 소스를 정규식으로 읽는 검사라 이 run만 소스 꼴에 기댄다.
# 자원을 일부러 늘릴 때(T2b 운영 도메인 등)는 이 목록과 해당 검사를 함께 고친다.
run "module_declares_exactly_the_contract_resources" {
  command = plan

  assert {
    condition = toset([
      "aws_s3_bucket.web",
      "aws_s3_bucket_public_access_block.web",
      "aws_s3_bucket_ownership_controls.web",
      "aws_s3_bucket_versioning.web",
      "aws_s3_bucket_server_side_encryption_configuration.web",
      "aws_s3_bucket_lifecycle_configuration.web",
      "aws_s3_bucket_policy.web",
      "aws_cloudfront_origin_access_control.web",
      "aws_cloudfront_function.spa_rewrite",
      "aws_cloudfront_function.viewer_request",
      "aws_cloudfront_cache_policy.static",
      "aws_cloudfront_response_headers_policy.security",
      "aws_cloudfront_vpc_origin.alb",
      "aws_cloudfront_distribution.main",
      "aws_vpc_security_group_ingress_rule.alb_from_cloudfront",
      "aws_iam_role_policy.ci_deploy_web",
    ]) == toset(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]]))
    error_message = "모듈이 선언한 자원은 이 조건의 16개와 정확히 같아야 한다(덧붙은 자원도, 빠진 자원도 안 된다). 지금 선언된 자원: ${jsonencode(sort(flatten([for f in fileset(path.module, "*.tf") : [for m in regexall("(?m)^\\s*resource\\s+\"([a-z0-9_]+)\"\\s+\"([A-Za-z0-9_-]+)\"", file("${path.module}/${f}")) : "${m[0]}.${m[1]}"]])))}"
  }

  assert {
    condition     = length(flatten([for f in fileset(path.module, "*.tf") : regexall("(?m)^\\s*module\\s+\"", file("${path.module}/${f}"))])) == 0
    error_message = "이 모듈은 다른 모듈을 부르면 안 된다(module 블록 금지). 부른 모듈이 만드는 자원은 아래 run들이 볼 수 없다."
  }

  assert {
    condition     = length(fileset(path.module, "*.tf.json")) == 0
    error_message = "자원은 *.tf에만 선언한다. 지금 *.tf.json 파일: ${jsonencode(fileset(path.module, "*.tf.json"))}"
  }
}

run "static_origin_is_private" {
  assert {
    condition = (
      aws_s3_bucket.web.bucket == "whycode-test-web-123456789012"
      && aws_s3_bucket.web.force_destroy
      && aws_s3_bucket_public_access_block.web.block_public_acls
      && aws_s3_bucket_public_access_block.web.block_public_policy
      && aws_s3_bucket_public_access_block.web.ignore_public_acls
      && aws_s3_bucket_public_access_block.web.restrict_public_buckets
      && one(aws_s3_bucket_ownership_controls.web.rule).object_ownership == "BucketOwnerEnforced"
      && one(aws_s3_bucket_versioning.web.versioning_configuration).status == "Enabled"
    )
    error_message = "웹 버킷은 공개 접근과 ACL을 차단하고 버전 관리를 켜야 하며, destroy가 이전 버전까지 지우도록 force_destroy가 켜져 있어야 한다(웹 산출물은 CI가 다시 올린다)."
  }

  assert {
    condition = (
      aws_cloudfront_origin_access_control.web.origin_access_control_origin_type == "s3"
      && aws_cloudfront_origin_access_control.web.signing_behavior == "always"
      && aws_cloudfront_origin_access_control.web.signing_protocol == "sigv4"
      && [for s in jsondecode(aws_s3_bucket_policy.web.policy).Statement : jsonencode(s) if s.Effect == "Allow"] == [jsonencode({
        Sid       = "AllowCloudFrontRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.web.arn}/*"
        Condition = { StringEquals = { "AWS:SourceArn" = aws_cloudfront_distribution.main.arn } }
      })]
    )
    error_message = "비공개 S3 객체 읽기는 이 CloudFront 배포에서만 SigV4로 허용해야 한다."
  }
}

run "origins_use_the_default_domain_and_separate_static_and_dynamic_traffic" {
  assert {
    condition = (
      aws_cloudfront_function.spa_rewrite.publish
      && aws_cloudfront_function.spa_rewrite.runtime == "cloudfront-js-2.0"
      && aws_cloudfront_function.spa_rewrite.code == file("${path.module}/functions/spa-rewrite.js")
      && aws_cloudfront_function.viewer_request.publish
      && aws_cloudfront_function.viewer_request.runtime == "cloudfront-js-2.0"
      && aws_cloudfront_function.viewer_request.code == file("${path.module}/functions/viewer-request.js")
    )
    error_message = "두 CloudFront 함수는 각자 검증한 소스 파일로 JS 2.0에서 게시되어야 한다."
  }

  assert {
    condition = (
      one(aws_cloudfront_distribution.main.viewer_certificate).cloudfront_default_certificate
      && try(length(aws_cloudfront_distribution.main.aliases), 0) == 0
      && toset([for o in aws_cloudfront_distribution.main.origin : o.origin_id]) == toset(["web", "alb"])
      && one([for o in aws_cloudfront_distribution.main.origin : o.domain_name if o.origin_id == "web"]) == aws_s3_bucket.web.bucket_regional_domain_name
      && one([for o in aws_cloudfront_distribution.main.origin : o.origin_access_control_id if o.origin_id == "web"]) == aws_cloudfront_origin_access_control.web.id
      && one([for o in aws_cloudfront_distribution.main.origin : o.domain_name if o.origin_id == "alb"]) == var.alb_dns_name
      && one(flatten([for o in aws_cloudfront_distribution.main.origin : [for c in o.vpc_origin_config : c.vpc_origin_id] if o.origin_id == "alb"])) == aws_cloudfront_vpc_origin.alb.id
    )
    error_message = "스테이징은 CloudFront 기본 도메인에서 비공개 S3와 내부 ALB를 원본으로 써야 한다."
  }

  assert {
    condition = (
      [for b in aws_cloudfront_distribution.main.ordered_cache_behavior : b.path_pattern] == ["/mcp/", "/mcp", "/api/*", "/oauth2/*", "/.well-known/*"]
      && alltrue([
        for b in aws_cloudfront_distribution.main.ordered_cache_behavior :
        b.target_origin_id == "alb"
        && b.viewer_protocol_policy == "https-only"
        && b.cache_policy_id == "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
        && b.origin_request_policy_id == "216adef6-5c7f-47e4-b989-5492eafa07d3"
        && toset(b.allowed_methods) == toset(["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"])
        && one(b.function_association).event_type == "viewer-request"
        && one(b.function_association).function_arn == aws_cloudfront_function.viewer_request.arn
        && b.response_headers_policy_id == aws_cloudfront_response_headers_policy.security.id
      ])
    )
    error_message = "동적 경로는 HTTPS를 강제하고 전체 요청을 ALB에 전달해야 한다. 캐시는 끄고 요청 함수와 보안 헤더 정책을 적용해야 한다."
  }

  assert {
    condition = alltrue([
      for b in aws_cloudfront_distribution.main.default_cache_behavior :
      b.target_origin_id == "web"
      && b.viewer_protocol_policy == "redirect-to-https"
      && toset(b.allowed_methods) == toset(["GET", "HEAD"])
      && b.cache_policy_id == aws_cloudfront_cache_policy.static.id
      && one(b.function_association).event_type == "viewer-request"
      && one(b.function_association).function_arn == aws_cloudfront_function.spa_rewrite.arn
      && b.response_headers_policy_id == aws_cloudfront_response_headers_policy.security.id
    ])
    error_message = "정적 경로는 HTTPS로 전환하고 S3 캐시, SPA 요청 함수와 보안 헤더 정책을 적용해야 한다."
  }

  assert {
    condition = (
      toset([for e in aws_cloudfront_distribution.main.custom_error_response : e.error_code]) == toset([400, 403, 404, 405, 414, 416, 500, 501, 502, 503, 504])
      && alltrue([
        for e in aws_cloudfront_distribution.main.custom_error_response :
        e.error_caching_min_ttl == 0 && (e.response_code == null || e.response_code == 0) && (e.response_page_path == null || e.response_page_path == "")
      ])
    )
    error_message = "원본 오류의 최소 캐시 TTL은 0이고 상태나 오류 페이지를 바꾸지 않아야 한다."
  }
}

run "alb_receives_http_from_the_vpc_origin_and_the_original_https_scheme" {
  assert {
    condition = (
      one(aws_cloudfront_vpc_origin.alb.vpc_origin_endpoint_config).arn == var.alb_arn
      && one(aws_cloudfront_vpc_origin.alb.vpc_origin_endpoint_config).origin_protocol_policy == "http-only"
      && one(aws_cloudfront_vpc_origin.alb.vpc_origin_endpoint_config).http_port == 80
      && data.aws_security_group.cloudfront_vpc_origin.name == "CloudFront-VPCOrigins-Service-SG"
      && data.aws_security_group.cloudfront_vpc_origin.vpc_id == var.vpc_id
      && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.security_group_id == var.alb_security_group_id
      && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.referenced_security_group_id == data.aws_security_group.cloudfront_vpc_origin.id
      && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.ip_protocol == "tcp"
      && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.from_port == 80
      && aws_vpc_security_group_ingress_rule.alb_from_cloudfront.to_port == 80
    )
    error_message = "ALB HTTP 포트는 같은 VPC의 CloudFront 관리 보안 그룹에서만 열어야 한다."
  }

  assert {
    condition = (
      [for o in aws_cloudfront_distribution.main.origin : try(length(o.custom_header), 0) if o.origin_id == "web"] == [0]
      && flatten([for o in aws_cloudfront_distribution.main.origin : [for h in o.custom_header : "${h.name}: ${h.value}"] if o.origin_id == "alb"]) == ["Forwarded: proto=https"]
      && flatten([for o in aws_cloudfront_distribution.main.origin : [for c in o.vpc_origin_config : c.origin_read_timeout] if o.origin_id == "alb"]) == [120]
    )
    error_message = "VPC 원본에만 브라우저 구간의 HTTPS 정보를 붙이고 기본 응답 제한으로 120초를 써야 한다."
  }
}

run "ci_role_can_update_only_its_web_assets_and_cache" {
  assert {
    condition = (
      aws_iam_role_policy.ci_deploy_web.role == var.ci_deploy_role_name
      && length(jsondecode(aws_iam_role_policy.ci_deploy_web.policy).Statement) == 3
      && toset(flatten([
        for s in jsondecode(aws_iam_role_policy.ci_deploy_web.policy).Statement : [
          for a in s.Action : "${s.Effect}:${a}:${one(s.Resource)}"
        ]
        ])) == toset([
        "Allow:s3:ListBucket:${aws_s3_bucket.web.arn}",
        "Allow:s3:GetObject:${aws_s3_bucket.web.arn}/*",
        "Allow:s3:PutObject:${aws_s3_bucket.web.arn}/*",
        "Allow:cloudfront:CreateInvalidation:${aws_cloudfront_distribution.main.arn}",
        "Allow:cloudfront:GetInvalidation:${aws_cloudfront_distribution.main.arn}",
      ])
    )
    error_message = "CI 웹 배포 역할은 이 S3 버킷 읽기·쓰기와 이 CloudFront 배포 캐시 무효화 권한만 가져야 한다."
  }
}

run "security_headers_match_home_nginx" {
  assert {
    condition = length(aws_cloudfront_response_headers_policy.security.security_headers_config) == 1 && alltrue([
      for c in aws_cloudfront_response_headers_policy.security.security_headers_config :
      one(c.content_security_policy).override
      && one(c.content_security_policy).content_security_policy == regex(
        "(?m)^\\s*add_header Content-Security-Policy \"([^\"]+)\" always;",
        file("${path.module}/../../../../clients/web-dashboard/nginx.conf")
      )[0]
      && one(c.content_type_options).override
      && one(c.frame_options).override
      && one(c.frame_options).frame_option == "DENY"
      && one(c.referrer_policy).override
      && one(c.referrer_policy).referrer_policy == "strict-origin-when-cross-origin"
    ])
    error_message = "CloudFront 보안 헤더는 홈서버 nginx의 값과 같아야 한다."
  }
}

# 웹 버킷의 암호화가 빠지거나 KMS로 바뀌는 것, 오래된 버전이 영원히 쌓이는 것, TLS가 아닌 요청이 허용되는 것을 막는다.
# KMS로 바꾸면 CloudFront OAC가 키 권한까지 있어야 읽을 수 있는데 이 정책에는 그 권한이 없다. 수명 주기가 빠지면 배포마다 버전이 늘어 저장 요금이 계속 는다.
run "static_origin_encrypts_expires_old_versions_and_denies_plain_http" {
  assert {
    condition = (
      aws_s3_bucket_server_side_encryption_configuration.web.bucket == aws_s3_bucket.web.id
      && length(aws_s3_bucket_server_side_encryption_configuration.web.rule) == 1
      && one([for r in aws_s3_bucket_server_side_encryption_configuration.web.rule : one(r.apply_server_side_encryption_by_default).sse_algorithm]) == "AES256"
    )
    error_message = "웹 버킷 기본 암호화는 AES256(S3 관리 키) 하나여야 한다. 지금 값: ${jsonencode(aws_s3_bucket_server_side_encryption_configuration.web.rule)}"
  }

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.web.bucket == aws_s3_bucket.web.id
      && length(aws_s3_bucket_lifecycle_configuration.web.rule) == 2
      && alltrue([for r in aws_s3_bucket_lifecycle_configuration.web.rule : r.status == "Enabled"])
      && one([for r in aws_s3_bucket_lifecycle_configuration.web.rule : one(r.noncurrent_version_expiration).noncurrent_days if r.id == "expire-noncurrent-versions"]) == 30
      && one([for r in aws_s3_bucket_lifecycle_configuration.web.rule : one(r.abort_incomplete_multipart_upload).days_after_initiation if r.id == "abort-incomplete-multipart-uploads"]) == 7
    )
    error_message = "웹 버킷 수명 주기는 이전 버전을 30일 뒤 지우고 끝나지 않은 멀티파트 업로드를 7일 뒤 지우는 두 규칙(둘 다 Enabled)이어야 한다. 지금 값: ${jsonencode(aws_s3_bucket_lifecycle_configuration.web.rule)}"
  }

  assert {
    condition = (
      length(jsondecode(aws_s3_bucket_policy.web.policy).Statement) == 2
      && [for s in jsondecode(aws_s3_bucket_policy.web.policy).Statement : jsonencode(s) if s.Effect == "Deny"] == [jsonencode({
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false", "aws:PrincipalIsAWSService" = "false" } }
      })]
    )
    error_message = "웹 버킷 정책은 허용 문 하나와, TLS가 아닌 요청을 거부하는 문 하나(서비스 주체는 예외)만 있어야 한다. 지금 정책: ${aws_s3_bucket_policy.web.policy}"
  }
}

# 보안 헤더 정책이 nginx에 없는 헤더를 몰래 더하는 것을 막는다. HSTS는 nginx에 없어서 더하지 않는다 — 홈서버 앞의 Cloudflare가 붙여 왔다면
# 운영 전환 뒤 사라질 수 있으니 T2b에서 정한다. CORS·사용자 지정 헤더·헤더 제거도 쓰지 않는다.
run "security_headers_policy_has_only_the_four_nginx_headers" {
  assert {
    condition = (
      length(aws_cloudfront_response_headers_policy.security.security_headers_config) == 1
      && alltrue([
        for c in aws_cloudfront_response_headers_policy.security.security_headers_config :
        length(c.content_security_policy) == 1
        && length(c.content_type_options) == 1
        && length(c.frame_options) == 1
        && length(c.referrer_policy) == 1
        && length(c.strict_transport_security) == 0
        && length(c.xss_protection) == 0
      ])
      && length(aws_cloudfront_response_headers_policy.security.custom_headers_config) == 0
      && length(aws_cloudfront_response_headers_policy.security.cors_config) == 0
      && length(aws_cloudfront_response_headers_policy.security.remove_headers_config) == 0
    )
    error_message = "보안 헤더 정책은 CSP·nosniff·X-Frame-Options·Referrer-Policy 네 개만 가져야 한다(HSTS·XSS 보호·CORS·사용자 지정·제거 헤더 없음)."
  }
}

# 배포가 꺼지거나 IPv6·HTTP/3이 빠지거나 기본 루트 객체가 사라지거나 지역 제한이 걸리는 것을 막는다.
# 기본 루트 객체는 `/`를 index.html로 보내는 데 쓰이고, 압축은 정적 동작에만 켜져 있어야 한다(API 응답은 압축하지 않는다).
run "distribution_is_enabled_with_ipv6_http3_and_an_index_root" {
  assert {
    condition = (
      aws_cloudfront_distribution.main.enabled
      && aws_cloudfront_distribution.main.is_ipv6_enabled
      && aws_cloudfront_distribution.main.http_version == "http2and3"
      && aws_cloudfront_distribution.main.default_root_object == "index.html"
      && one(one(aws_cloudfront_distribution.main.restrictions).geo_restriction).restriction_type == "none"
    )
    error_message = "배포는 켜져 있고 IPv6·HTTP/3을 쓰며 기본 루트 객체가 index.html이고 지역 제한이 없어야 한다."
  }

  assert {
    condition = (
      alltrue([for b in aws_cloudfront_distribution.main.default_cache_behavior : b.compress == true])
      && alltrue([for b in aws_cloudfront_distribution.main.ordered_cache_behavior : b.compress != true])
      && alltrue([for b in concat(tolist(aws_cloudfront_distribution.main.default_cache_behavior), tolist(aws_cloudfront_distribution.main.ordered_cache_behavior)) : toset(b.cached_methods) == toset(["GET", "HEAD"])])
    )
    error_message = "정적 동작만 압축하고 모든 동작의 캐시 대상 메서드는 GET·HEAD여야 한다."
  }
}

run "outputs_return_the_bucket_and_distribution_values" {
  assert {
    condition = (
      output.web_bucket_name == aws_s3_bucket.web.bucket
      && output.cloudfront_distribution_id == aws_cloudfront_distribution.main.id
      && output.cloudfront_domain_name == aws_cloudfront_distribution.main.domain_name
    )
    error_message = "출력은 웹 버킷 이름·배포 ID·배포 도메인 이름과 같아야 한다."
  }
}

# 경계값(포트 65535, 응답 시간 1초)은 받아들여야 한다. 검증이 정상 값을 막는지 본다.
run "port_and_timeout_boundaries_are_accepted" {
  command = plan

  variables {
    alb_listener_port           = 65535
    origin_read_timeout_seconds = 1
  }

  assert {
    condition     = var.alb_listener_port == 65535 && var.origin_read_timeout_seconds == 1
    error_message = "경계값이 검증에서 거절됐다."
  }
}

run "origin_timeout_rejects_values_above_the_cloudfront_limit" {
  command = plan

  variables {
    origin_read_timeout_seconds = 121
  }

  expect_failures = [var.origin_read_timeout_seconds]
}

run "origin_timeout_rejects_zero" {
  command = plan

  variables {
    origin_read_timeout_seconds = 0
  }

  expect_failures = [var.origin_read_timeout_seconds]
}

run "alb_listener_port_rejects_zero" {
  command = plan

  variables {
    alb_listener_port = 0
  }

  expect_failures = [var.alb_listener_port]
}

run "alb_listener_port_rejects_65536" {
  command = plan

  variables {
    alb_listener_port = 65536
  }

  expect_failures = [var.alb_listener_port]
}

run "alb_listener_port_rejects_fractions" {
  command = plan

  variables {
    alb_listener_port = 80.5
  }

  expect_failures = [var.alb_listener_port]
}
