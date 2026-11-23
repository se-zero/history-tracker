locals {
  not_found_body = jsonencode({ error = "not_found" })

  # 번호가 낮은 규칙이 먼저 적용된다. 내부 경로와 미등록 웹훅을 일반 API보다 먼저 차단한다.
  # AWS는 한 경로 조건에 최대 세 값을 받으므로 내부 경로와 backend 경로를 각각 두 규칙에 둔다.
  listener_rules = {
    internal_paths = {
      priority = 10
      paths    = ["/api/v1/internal/*", "/api/v1/collect/*", "/api/v1/raw/*"]
    }
    mcp_trailing_slash = {
      priority = 11
      paths    = ["/mcp/"]
      body     = jsonencode({ error = "not_found", message = "The MCP endpoint is /mcp (no trailing slash)." })
    }
    internal_roots = {
      priority = 12
      paths    = ["/api/v1/internal", "/api/v1/collect", "/api/v1/raw"]
    }
    github_webhook = {
      priority         = 20
      paths            = ["/api/v1/webhook/github"]
      method           = "POST"
      target_group_arn = aws_lb_target_group.pipeline_worker.arn
    }
    other_webhooks = {
      priority = 21
      paths    = ["/api/v1/webhook", "/api/v1/webhook/*"]
    }
    backend_api = {
      priority         = 30
      paths            = ["/api/*", "/oauth2/*", "/.well-known/*"]
      target_group_arn = aws_lb_target_group.backend.arn
    }
    backend_mcp = {
      priority         = 31
      paths            = ["/mcp"]
      target_group_arn = aws_lb_target_group.backend.arn
    }
  }
}

# 스테이징은 CloudFront에서 TLS를 종료하고 VPC 안에서만 HTTP를 사용한다.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      status_code  = "404"
      content_type = "application/json"
      message_body = local.not_found_body
    }
  }
}

resource "aws_lb_listener_rule" "this" {
  for_each = local.listener_rules

  listener_arn = aws_lb_listener.http.arn
  priority     = each.value.priority

  condition {
    path_pattern {
      values = each.value.paths
    }
  }

  dynamic "condition" {
    for_each = try([each.value.method], [])
    content {
      http_request_method {
        values = [condition.value]
      }
    }
  }

  action {
    type             = contains(keys(each.value), "target_group_arn") ? "forward" : "fixed-response"
    target_group_arn = try(each.value.target_group_arn, null)

    dynamic "fixed_response" {
      for_each = contains(keys(each.value), "target_group_arn") ? [] : [try(each.value.body, local.not_found_body)]
      content {
        status_code  = "404"
        content_type = "application/json"
        message_body = fixed_response.value
      }
    }
  }
}
