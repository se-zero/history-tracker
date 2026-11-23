resource "aws_lb" "main" {
  name                       = "${var.name}-alb"
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [var.security_group_id]
  subnets                    = var.subnet_ids
  idle_timeout               = var.idle_timeout_seconds
  drop_invalid_header_fields = true
  preserve_host_header       = true
  # 클라이언트 IP는 CloudFront가 넣는 전용 헤더에서만 읽는다.
  xff_header_processing_mode = "remove"
  enable_deletion_protection = false
}

resource "aws_lb_target_group" "backend" {
  name                 = "${var.name}-backend"
  vpc_id               = var.vpc_id
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  deregistration_delay = tostring(var.deregistration_delay_seconds)

  health_check {
    enabled             = true
    path                = var.health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_target_group" "pipeline_worker" {
  name                 = "${var.name}-worker"
  vpc_id               = var.vpc_id
  port                 = 8081
  protocol             = "HTTP"
  target_type          = "ip"
  deregistration_delay = tostring(var.deregistration_delay_seconds)

  health_check {
    enabled             = true
    path                = var.health_check_path
    port                = "traffic-port"
    protocol            = "HTTP"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}
