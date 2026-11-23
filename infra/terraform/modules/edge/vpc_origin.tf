resource "aws_cloudfront_vpc_origin" "alb" {
  vpc_origin_endpoint_config {
    name                   = "${var.name}-alb"
    arn                    = var.alb_arn
    http_port              = var.alb_listener_port
    https_port             = 443
    origin_protocol_policy = "http-only"

    # http-only에서도 provider가 요구하는 필드다.
    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }
}

# AWS가 VPC 원본에 만드는 그룹은 배포가 끝난 뒤에 조회할 수 있다. 같은 이름의 다른 VPC 그룹은 제외한다.
data "aws_security_group" "cloudfront_vpc_origin" {
  name   = "CloudFront-VPCOrigins-Service-SG"
  vpc_id = var.vpc_id

  depends_on = [aws_cloudfront_distribution.main]
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id            = var.alb_security_group_id
  referenced_security_group_id = data.aws_security_group.cloudfront_vpc_origin.id
  ip_protocol                  = "tcp"
  from_port                    = var.alb_listener_port
  to_port                      = var.alb_listener_port
  description                  = "HTTP from the CloudFront VPC origin security group"
}
