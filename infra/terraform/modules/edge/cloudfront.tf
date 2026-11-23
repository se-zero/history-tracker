locals {
  dynamic_paths = ["/mcp/", "/mcp", "/api/*", "/oauth2/*", "/.well-known/*"]

  # AWS 관리형 정책. 캐시를 끄고 Authorization·Host·쿠키·쿼리를 포함한 요청을 ALB로 전달한다.
  caching_disabled_policy_id = "4135ea2d-6df8-44a3-9df3-4b5a84be39ad"
  all_viewer_policy_id       = "216adef6-5c7f-47e4-b989-5492eafa07d3"
}

resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/functions/spa-rewrite.js")
}

resource "aws_cloudfront_function" "viewer_request" {
  name    = "${var.name}-viewer-request"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/functions/viewer-request.js")
}

# index.html의 Cache-Control을 존중하고 파일 경로와 압축 방식만 캐시 키로 쓴다.
resource "aws_cloudfront_cache_policy" "static" {
  name        = "${var.name}-static"
  min_ttl     = 0
  default_ttl = 3600
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_gzip   = true
    enable_accept_encoding_brotli = true

    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# 홈서버의 clients/web-dashboard/nginx.conf와 같은 정책으로 유지한다.
resource "aws_cloudfront_response_headers_policy" "security" {
  name = "${var.name}-security-headers"

  security_headers_config {
    content_security_policy {
      content_security_policy = "default-src 'self'; script-src 'self' https://cdn.paddle.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com https://cdn.jsdelivr.net; font-src 'self' https://fonts.gstatic.com https://cdn.jsdelivr.net https://cdn.fontshare.com; img-src 'self' data:; media-src 'self'; connect-src 'self' https://checkout-service.paddle.com https://sandbox-checkout-service.paddle.com; frame-src https://buy.paddle.com https://sandbox-buy.paddle.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; object-src 'none'"
      override                = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
  }
}

resource "aws_cloudfront_distribution" "main" {
  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  default_root_object = "index.html"
  comment             = "${var.name} web and API"

  origin {
    origin_id                = "web"
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  origin {
    origin_id   = "alb"
    domain_name = var.alb_dns_name

    vpc_origin_config {
      vpc_origin_id       = aws_cloudfront_vpc_origin.alb.id
      origin_read_timeout = var.origin_read_timeout_seconds
    }

    # 이 원본에 연결한 동작은 모두 HTTPS-only다. Spring이 브라우저 구간의 HTTPS를 사용해
    # 리다이렉트와 Secure 쿠키를 만들게 한다. ALB의 X-Forwarded-Proto는 VPC 구간의 http를 나타낸다.
    custom_header {
      name  = "Forwarded"
      value = "proto=https"
    }
  }

  default_cache_behavior {
    target_origin_id           = "web"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = aws_cloudfront_cache_policy.static.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.dynamic_paths

    content {
      path_pattern               = ordered_cache_behavior.value
      target_origin_id           = "alb"
      viewer_protocol_policy     = "https-only"
      allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods             = ["GET", "HEAD"]
      cache_policy_id            = local.caching_disabled_policy_id
      origin_request_policy_id   = local.all_viewer_policy_id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.viewer_request.arn
      }
    }
  }

  # 원본 오류의 상태·본문을 유지한다. S3는 TTL을 0으로 설정해도 오류를 최소 1초 캐시한다.
  dynamic "custom_error_response" {
    for_each = toset([400, 403, 404, 405, 414, 416, 500, 501, 502, 503, 504])

    content {
      error_code            = custom_error_response.value
      error_caching_min_ttl = 0
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}
