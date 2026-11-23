output "alb_arn" {
  description = "CloudFront VPC 원본에 등록할 내부 ALB ARN"
  value       = aws_lb.main.arn
}

output "alb_arn_suffix" {
  description = "CloudWatch ALB 지표를 조회할 ARN 접미사"
  value       = aws_lb.main.arn_suffix
}

output "alb_dns_name" {
  description = "CloudFront VPC 원본으로 쓸 내부 ALB DNS 이름"
  value       = aws_lb.main.dns_name
}

output "listener_arn" {
  description = "스테이징 ALB의 HTTP 리스너 ARN"
  value       = aws_lb_listener.http.arn
}

output "listener_port" {
  description = "스테이징 ALB의 HTTP 리스너 포트"
  value       = aws_lb_listener.http.port
}

output "target_group_arns" {
  description = "ECS 서비스가 등록할 backend 및 pipeline-worker 대상 그룹 ARN"
  value = {
    backend         = aws_lb_target_group.backend.arn
    pipeline_worker = aws_lb_target_group.pipeline_worker.arn
  }

  # ECS 서비스가 생성될 때 대상 그룹은 이미 ALB 리스너에 연결되어 있어야 한다.
  depends_on = [aws_lb_listener_rule.this]
}
