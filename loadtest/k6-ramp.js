// Progressive load test from the article: 10K -> 50K -> 100K -> 250K -> 500K -> 1M RPS,
// expressed as fractions of TARGET_RPS so the same script runs on a laptop or against AWS.
// At each step, watch which component saturates first (app CPU, Redis, DynamoDB throttles, ALB).
//
// Uses an *open* model (ramping-arrival-rate): new requests keep arriving at the target rate even if the
// server slows down. Closed models (fixed VUs) politely wait and hide tail latency.
//
//   docker run --rm -i --network host -e BASE_URL=http://localhost:8099 -e TARGET_RPS=2000 grafana/k6 run - < loadtest/k6-ramp.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8099';
const TARGET = Number(__ENV.TARGET_RPS || 2000);
const HOLD = __ENV.HOLD || '20s';
const PRODUCTS = Number(__ENV.PRODUCTS || 1000);
const WRITE_SHARE = Number(__ENV.WRITE_SHARE || 0.05);

const STEPS = [0.01, 0.05, 0.10, 0.25, 0.50, 1.0];

function stages(share) {
  return STEPS.flatMap((s) => {
    const rate = Math.max(1, Math.round(TARGET * s * share));
    return [{ target: rate, duration: '5s' }, { target: rate, duration: HOLD }];
  });
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    reads: {
      executor: 'ramping-arrival-rate',
      exec: 'readProduct',
      timeUnit: '1s',
      startRate: 1,
      preAllocatedVUs: 100,
      maxVUs: 2000,
      stages: stages(1 - WRITE_SHARE),
    },
    writes: {
      executor: 'ramping-arrival-rate',
      exec: 'placeOrder',
      timeUnit: '1s',
      startRate: 1,
      preAllocatedVUs: 20,
      maxVUs: 500,
      stages: stages(WRITE_SHARE),
    },
  },
  // At 1M RPS, p99 = 1% = 10,000 requests *every second*: gate on tail latency, not the average.
  thresholds: {
    http_req_failed: ['rate<0.001'],
    'http_req_duration{scenario:reads}': ['p(95)<50', 'p(99)<150'],
    'http_req_duration{scenario:writes}': ['p(95)<150', 'p(99)<400'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

// Real traffic is skewed: a few products are hot. A cubic skew makes low ids far more popular,
// which is exactly the case the L1 cache and single-flight loading are built for.
function productId() {
  return `p-${Math.floor(PRODUCTS * Math.pow(Math.random(), 3)) + 1}`;
}

export function readProduct() {
  const res = http.get(`${BASE}/api/products/${productId()}`, { tags: { name: 'GET /api/products/{id}' } });
  check(res, { 'read 200': (r) => r.status === 200 });
}

export function placeOrder() {
  const body = JSON.stringify({
    customerId: `c-${__VU}`,
    items: [{ productId: productId(), quantity: 1 + Math.floor(Math.random() * 3) }],
  });
  const res = http.post(`${BASE}/api/orders`, body, {
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': `k6-${__VU}-${__ITER}-${Date.now()}` },
    tags: { name: 'POST /api/orders' },
  });
  check(res, { 'write 202': (r) => r.status === 202 });
}
