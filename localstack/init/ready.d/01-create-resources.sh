#!/usr/bin/env bash
# Runs inside the LocalStack container once it is ready. Mirrors infra/terraform/{dynamodb,sqs}.tf.
set -euo pipefail

awslocal dynamodb create-table --table-name products \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

awslocal dynamodb create-table --table-name orders \
  --attribute-definitions AttributeName=id,AttributeType=S \
  --key-schema AttributeName=id,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

DLQ_URL=$(awslocal sqs create-queue --queue-name order-events-dlq --query QueueUrl --output text)
DLQ_ARN=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" --attribute-names QueueArn --query Attributes.QueueArn --output text)

awslocal sqs create-queue --queue-name order-events --attributes "{
  \"VisibilityTimeout\": \"30\",
  \"ReceiveMessageWaitTimeSeconds\": \"10\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${DLQ_ARN}\\\",\\\"maxReceiveCount\\\":\\\"5\\\"}\"
}" >/dev/null

echo "high-rps: DynamoDB tables and SQS queues created"
