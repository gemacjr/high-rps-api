variable "region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "high-rps-api"
}

variable "image_tag" {
  description = "Tag of the image in ECR to deploy (set by scripts/deploy-aws.sh)."
  type        = string
  default     = "bootstrap"
}

variable "vpc_cidr" {
  type    = string
  default = "10.40.0.0/16"
}

variable "single_nat_gateway" {
  description = "true = cheaper (one NAT), false = one NAT per AZ (no cross-AZ single point of failure)."
  type        = bool
  default     = true
}

# --- compute sizing: measure with loadtest/k6-ramp.js and adjust ---
variable "task_cpu" {
  description = "Fargate CPU units. Keep >= 2048: with < 2 vCPUs the JVM silently falls back to SerialGC."
  type        = number
  default     = 2048
}

variable "task_memory" {
  type    = number
  default = 4096
}

variable "min_tasks" {
  description = "Never below 2 (one per AZ) so a single task/AZ failure is not an outage."
  type        = number
  default     = 2
}

variable "max_tasks" {
  description = "1M RPS / (measured RPS per task). E.g. 20k RPS per task -> 50 tasks, plus headroom."
  type        = number
  default     = 20
}

variable "target_requests_per_task" {
  description = "Autoscaling target: ALB requests per target per minute. Derive from load-test results (~60-70% of max)."
  type        = number
  default     = 300000
}

variable "cpu_target_percent" {
  type    = number
  default = 60
}

# --- data ---
variable "redis_node_type" {
  description = "Use memory-optimised nodes (cache.r7g.*) for production traffic."
  type        = string
  default     = "cache.t4g.small"
}

variable "dynamodb_deletion_protection" {
  type    = bool
  default = true
}

# --- edge ---
variable "certificate_arn" {
  description = "ACM certificate ARN. When set, the ALB serves HTTPS on 443 and redirects 80 -> 443."
  type        = string
  default     = null
}

variable "alarm_topic_arn" {
  description = "Optional SNS topic ARN that receives CloudWatch alarm notifications."
  type        = string
  default     = null
}
