data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "execution" {
  name_prefix        = "${var.project}-exec-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Least privilege: exactly the calls the app makes, on exactly these resources.
data "aws_iam_policy_document" "task" {
  statement {
    sid       = "DynamoDb"
    actions   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem"]
    resources = [aws_dynamodb_table.products.arn, aws_dynamodb_table.orders.arn]
  }

  statement {
    sid = "Sqs"
    actions = [
      "sqs:GetQueueUrl", "sqs:SendMessage", "sqs:ReceiveMessage",
      "sqs:DeleteMessage", "sqs:ChangeMessageVisibility",
    ]
    resources = [aws_sqs_queue.order_events.arn]
  }
}

resource "aws_iam_role" "task" {
  name_prefix        = "${var.project}-task-"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
}

resource "aws_iam_role_policy" "task" {
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}
