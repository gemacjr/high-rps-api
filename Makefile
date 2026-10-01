.PHONY: up down run test image app-up loadtest tf-validate deploy destroy

up:            ## Start LocalStack (DynamoDB+SQS) and Valkey
	docker compose up -d --wait

down:
	docker compose --profile app down -v

run: up        ## Run the app on the host against LocalStack (http://localhost:8099)
	./gradlew bootRun --args='--spring.profiles.active=local'

test:          ## Unit + Testcontainers integration tests (needs Docker)
	./gradlew test

image:
	docker build -t high-rps-api .

app-up:        ## Run everything, including the app, in containers
	docker compose --profile app up -d --build --wait

TARGET_RPS ?= 2000
BASE_URL   ?= http://localhost:8099
loadtest:      ## Progressive ramp to TARGET_RPS (open model)
	docker run --rm -i --network host -e BASE_URL=$(BASE_URL) -e TARGET_RPS=$(TARGET_RPS) grafana/k6 run - < loadtest/k6-ramp.js

tf-validate:
	terraform -chdir=infra/terraform init -backend=false -input=false >/dev/null && terraform -chdir=infra/terraform validate

deploy:        ## Build, push to ECR and deploy to ECS Fargate
	./scripts/deploy-aws.sh

destroy:
	terraform -chdir=infra/terraform destroy
