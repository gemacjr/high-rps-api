#!/usr/bin/env bash
# Build the arm64 image, push it to ECR, and roll the ECS service.
# Requires: aws CLI (authenticated), terraform, docker with buildx.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF="$ROOT/infra/terraform"
REGION="${AWS_REGION:-us-east-1}"
TAG="${TAG:-$(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || date +%Y%m%d%H%M%S)}"

terraform -chdir="$TF" init -input=false

# 1. First run only needs the registry so there is somewhere to push to.
if ! terraform -chdir="$TF" output -raw ecr_repository_url >/dev/null 2>&1; then
  terraform -chdir="$TF" apply -var "region=$REGION" -target=aws_ecr_repository.app
fi
ECR_URL="$(terraform -chdir="$TF" output -raw ecr_repository_url)"

# 2. Build for Graviton (arm64) and push an immutable tag.
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "${ECR_URL%%/*}"
docker buildx build --platform linux/arm64 -t "$ECR_URL:$TAG" --push "$ROOT"

# 3. Everything else; ECS performs a rolling deploy with circuit-breaker rollback.
terraform -chdir="$TF" apply -var "region=$REGION" -var "image_tag=$TAG"

echo "Deployed $TAG -> $(terraform -chdir="$TF" output -raw api_url)"
