# high-rps-api — Spring Boot built to scale toward 1M requests/second

A Spring Boot 4 / Java 25 API that applies the ideas from
[*Can Spring Boot Really Handle 1 Million Requests Per Second?*](https://medium.com/javarevisited/can-spring-boot-really-handle-1-million-requests-per-second-db5a7bb25e9d).
It runs locally on **LocalStack** (DynamoDB + SQS) + Valkey, and deploys to **AWS** (ECS Fargate on Graviton,
ALB, DynamoDB, SQS, ElastiCache Valkey) with Terraform.

The article's thesis is that **1M RPS is an architecture problem, not a framework setting**: no single JVM does it;
many small, stateless, well-behaved instances behind a load balancer do, as long as nothing behind them falls over first.
This project is that architecture in a small, realistic shape.

```
                 ┌──────────────┐  Cache-Control + ETag → CDN/browser can absorb repeats (L0)
  clients ──────▶│     ALB      │  least-outstanding-requests, only /api/* and health routed
                 └──────┬───────┘
        ┌───────────────┼────────────────┐   ECS Fargate, arm64, 2..N tasks,
        ▼               ▼                ▼   autoscaled on requests/target + CPU
   ┌─────────┐     ┌─────────┐      ┌─────────┐
   │  task   │ ... │  task   │ ...  │  task   │  stateless · virtual threads · L1 Caffeine
   └──┬───┬──┘     └─────────┘      └─────────┘
      │   │ L2 miss            publish OrderPlaced
      │   ▼                          ▼
      │ ┌───────────────┐    ┌──────────────┐   consumer (virtual threads, batch delete,
      │ │ ElastiCache   │    │ SQS + DLQ    │──▶ idempotent PENDING→CONFIRMED)
      │ │ Valkey (L2)   │    └──────────────┘
      ▼ └───────────────┘
   ┌───────────────────────┐
   │ DynamoDB (on-demand)  │  via VPC gateway endpoint (no NAT on the hot path)
   └───────────────────────┘
```

## Article → implementation

| Article says | Where it lives here |
|---|---|
| Scale horizontally; do the math (1M ÷ RPS-per-instance) | `infra/terraform/ecs.tf`: target tracking on `ALBRequestCountPerTarget` + CPU; `max_tasks` / `target_requests_per_task` variables |
| Stateless instances, state in shared stores | No sessions; all state in DynamoDB / Valkey / SQS |
| Layered caching: CDN → local → Redis → DB | `ProductController` (ETag + `Cache-Control: public`), `ProductService` (Caffeine L1 → Redis L2 → DynamoDB) |
| "The easiest query to scale is the one you never execute" | Single-flight `AsyncLoadingCache` (one load per hot key per instance) + negative caching of 404s |
| Connection pools multiply with instances (50 × 200 = 10,000) | DynamoDB is HTTP (no DB connection limit); SDK pools bounded per client (`app.aws.max-connections`); Lettuce multiplexes one Redis connection per instance |
| Threads ≠ throughput; WebFlux only helps if nothing blocks | Virtual threads (`spring.threads.virtual.enabled`) with plain blocking code; JDK 25 removes `synchronized` pinning |
| Move non-critical work off the request path (Kafka) | `POST /api/orders` → 202; SQS consumer does the slow work; DLQ for poison messages |
| Smaller payloads | Lean response records, `non_null` JSON, gzip ≥ 1KB |
| Don't copy JVM flags; measure, then tune | Dockerfile sets only container-sanity flags; override via `JAVA_TOOL_OPTIONS` after load testing |
| Watch p95/p99, not averages | Percentile histograms + SLO buckets on `http.server.requests`; CloudWatch p99 alarm; k6 thresholds on p99 |
| Ramp load 10K → 50K → … → 1M and find the first bottleneck | `loadtest/k6-ramp.js` (same ratios, scaled by `TARGET_RPS`, open-model arrival rate) |

Beyond the article, the design also covers:
- **Failure behaviour.** If Redis goes down, reads fall through to DynamoDB, and Lettuce fails fast with `REJECT_COMMANDS`. When AWS calls fail, the API returns 503 with `Retry-After`. Redis is excluded from readiness, so a cache outage doesn't pull every task out of the ALB.
- **No retry storms.** When refreshing a cached product fails, the last good value keeps being served (`app.cache.local-max-stale`, 5m). A failed load is remembered for `app.cache.failure-ttl` (1s), so each instance makes at most one DynamoDB attempt per product per second, however much traffic arrives. Both are tracked by the `products.load{result=failed|failed_fast|stale_served}` metric.
- **Idempotency.** An `Idempotency-Key` header plus a DynamoDB conditional put means client or SDK retries never create duplicate orders. The same key also heals the write-then-publish dual-write problem.
- **Safe rollouts.** Graceful shutdown (app 25s, ECS 30s, ALB deregistration 30s). Tomcat's keep-alive (65s) is longer than the ALB idle timeout (60s), which avoids sporadic 502s. ECS deploys have a circuit breaker with automatic rollback.
- **Fast scale-out.** The JDK 25 AOT cache is built into the image, so startup is about 0.7s instead of 1.7s.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/products/{id}` | Cached; ETag / 304; `Cache-Control: public, max-age=30` |
| PUT | `/api/products/{id}` | Upsert; bumps version, invalidates L2 + local L1 |
| POST | `/api/orders` | 202 Accepted (200 on idempotent replay); send `Idempotency-Key` |
| GET | `/api/orders/{id}` | `PENDING` → `CONFIRMED` once the async consumer runs |
| GET | `/actuator/health/{liveness,readiness}`, `/actuator/prometheus` | Prometheus is **not** routed by the ALB |

Errors are RFC 9457 `application/problem+json`.

## Run locally (LocalStack)

Requires Docker and JDK 25 (Gradle toolchains will use it).

```bash
make up          # LocalStack :4600 (DynamoDB+SQS, tables/queues via localstack/init) + Valkey :6395
make run         # app on http://localhost:8099, seeds products p-1..p-1000
curl -i localhost:8099/api/products/p-42
curl -XPOST localhost:8099/api/orders -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-00000001' \
     -d '{"customerId":"c1","items":[{"productId":"p-1","quantity":3}]}'

make app-up      # alternatively: everything in containers (app limited to 2 CPU / 1 GB)
make test        # unit + Testcontainers integration tests against real LocalStack & Valkey
make down
```

Host ports are shifted (4600 / 6395 / 8099) to coexist with other local stacks. The pinned `localstack/localstack:4.14`
runs DynamoDB/SQS without an auth token; newer images read `LOCALSTACK_AUTH_TOKEN` from your environment.

## Load test

```bash
make loadtest TARGET_RPS=4000                                  # against make run / app-up
docker run --rm -i -e BASE_URL=https://<alb> -e TARGET_RPS=200000 grafana/k6 run - < loadtest/k6-ramp.js
```

Results from one run on a laptop (app container capped at 2 CPU/1 GB, ramp to 4000 RPS, 95% reads with hot-key skew):

| | p50 | p95 | p99 | errors |
|---|---|---|---|---|
| GET product | 0.21 ms | 2.3 ms | 45 ms | 0 |
| POST order | 11 ms | 283 ms | 2.36 s | 0 |

The first bottleneck was **LocalStack itself**. It emulates DynamoDB and SQS in one Python process, so writes queued up behind it, and with the production timeouts (500 ms per attempt) they turned into fast 503s. That's why the `local` profile relaxes those timeouts. Reads were unaffected because they almost never left the cache.

Treat laptop numbers as a check that the app behaves correctly, not as a benchmark. For real capacity planning, run the ramp against AWS and fill in `max_tasks` and `target_requests_per_task` from the RPS a single task sustains at your p99 target.

## Deploy to AWS

Requires the AWS CLI (authenticated), Terraform ≥ 1.9, and Docker buildx.

```bash
cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars   # edit as needed
make deploy      # creates ECR, builds + pushes linux/arm64 image, applies the rest, prints the API URL
```

`scripts/deploy-aws.sh` shows the plan and waits for your confirmation before every `terraform apply`.
Creates: VPC (3 AZ, NAT, DynamoDB/S3 gateway endpoints), ALB, ECS cluster/service (Container Insights),
ECR, 2 DynamoDB tables (on-demand, PITR, deletion protection), SQS queue + DLQ, ElastiCache Valkey
(Multi-AZ, TLS), least-privilege IAM, CloudWatch alarms (p99 latency, 5xx %, DLQ depth).
Note: NAT gateways, ElastiCache and the ALB bill hourly, so run `make destroy` when you're done experimenting
(set `dynamodb_deletion_protection=false` first).

**Getting to 1M RPS on AWS:**
- Request an ECS task quota increase.
- Pre-warm the ALB, or contact AWS for a Load Balancer Capacity Unit reservation.
- Put CloudFront in front so cacheable GETs never reach the ALB.
- Move to `cache.r7g.*` nodes with cluster mode enabled (sharded).
- Split the SQS consumer into its own ECS service (`ORDERS_CONSUMER_ENABLED=false` on the API) so API and worker scale independently.

## Deliberate trade-offs / next steps

- **L1 staleness:** after a write, other instances may serve the old product for up to `app.cache.local-ttl` (10s).
  If that's too long, add Redis pub/sub invalidation.
- **Dual write:** handled by client retries with the idempotency key. A fully automatic outbox would use DynamoDB Streams → EventBridge Pipes → SQS.
- **Circuit breaking:** Redis fails fast already. Add Resilience4j (or Spring Framework 7's `@ConcurrencyLimit`) around DynamoDB if you need to shed load before timeouts trigger.
- **Auth / rate limiting:** out of scope; put AWS WAF rate-based rules on the ALB/CloudFront.

## Cache expiry jitter

Redis TTLs get random ±15% jitter (`app.cache.redis-ttl-jitter`, so the default 5 min becomes 4m15s–5m45s). Without it, products
loaded together, for example after a deploy, a cache flush or the seeder, would all expire in the same second. Every
instance would then miss Redis at once and send a synchronized stampede to DynamoDB. Set the value to `0` to disable jitter.
If you need more than this, the next steps up are longer TTLs for hot keys, or probabilistic early refresh (XFetch).
