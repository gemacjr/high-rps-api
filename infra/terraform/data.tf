# ---------- DynamoDB: on-demand scales with request rate, no connection pool to exhaust ----------
resource "aws_dynamodb_table" "products" {
  name                        = "${var.project}-products"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "id"
  deletion_protection_enabled = var.dynamodb_deletion_protection

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}

resource "aws_dynamodb_table" "orders" {
  name                        = "${var.project}-orders"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = "id"
  deletion_protection_enabled = var.dynamodb_deletion_protection

  attribute {
    name = "id"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}

# ---------- SQS: async work off the request path, with a dead-letter queue for poison messages ----------
resource "aws_sqs_queue" "order_events_dlq" {
  name                      = "${var.project}-order-events-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "order_events" {
  name                       = "${var.project}-order-events"
  visibility_timeout_seconds = 30
  receive_wait_time_seconds  = 10
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.order_events_dlq.arn
    maxReceiveCount     = 5
  })
}

# ---------- ElastiCache (Valkey): shared L2 cache, Multi-AZ with automatic failover ----------
resource "aws_elasticache_subnet_group" "redis" {
  name       = var.project
  subnet_ids = module.vpc.private_subnets
}

resource "aws_elasticache_replication_group" "redis" {
  replication_group_id       = var.project
  description                = "L2 cache for ${var.project}"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = var.redis_node_type
  num_cache_clusters         = 2
  automatic_failover_enabled = true
  multi_az_enabled           = true
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.redis.name
  security_group_ids         = [aws_security_group.redis.id]
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  parameter_group_name       = "default.valkey8"
}
