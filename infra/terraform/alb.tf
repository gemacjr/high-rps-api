resource "aws_lb" "app" {
  name                       = var.project
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = module.vpc.public_subnets
  idle_timeout               = 60 # the app's Tomcat keep-alive-timeout (65s) is deliberately longer
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "app" {
  name        = var.project
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = module.vpc.vpc_id

  # Sends each request to the target with the fewest in-flight requests: a task in a GC pause or
  # warming up gets less traffic instead of an equal round-robin share.
  load_balancing_algorithm_type = "least_outstanding_requests"
  deregistration_delay          = 30

  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 10
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

locals {
  https = var.certificate_arn != null
}

# Public surface is /api/** and health only; /actuator/prometheus etc. are not routed.
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.app.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = local.https ? "redirect" : "fixed-response"

    dynamic "redirect" {
      for_each = local.https ? [1] : []
      content {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }

    dynamic "fixed_response" {
      for_each = local.https ? [] : [1]
      content {
        content_type = "application/json"
        message_body = "{\"status\":404}"
        status_code  = "404"
      }
    }
  }
}

resource "aws_lb_listener" "https" {
  count             = local.https ? 1 : 0
  load_balancer_arn = aws_lb.app.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"status\":404}"
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = local.https ? aws_lb_listener.https[0].arn : aws_lb_listener.http.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  condition {
    path_pattern {
      values = ["/api/*", "/actuator/health/*"]
    }
  }
}
