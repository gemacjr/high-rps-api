// Steady read-only load to observe cache-expiry behaviour over time (e.g. Redis TTL jitter).
// Uniform product popularity + a rate >= PRODUCTS/s means every product is requested about once a second,
// so each Redis expiry surfaces as an L2 miss (DynamoDB read) almost immediately.
//
//   docker run --rm -i --network high-rps_default -e BASE_URL=http://app:8080 grafana/k6 run - < loadtest/k6-ttl-expiry.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8099';
const RATE = Number(__ENV.RATE || 1500);
const PRODUCTS = Number(__ENV.PRODUCTS || 1000);

export const options = {
  discardResponseBodies: true,
  scenarios: {
    steady: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.DURATION || '7m',
      preAllocatedVUs: 50,
      maxVUs: 500,
    },
  },
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
  const id = `p-${Math.floor(Math.random() * PRODUCTS) + 1}`;
  const res = http.get(`${BASE}/api/products/${id}`, { tags: { name: 'GET /api/products/{id}' } });
  check(res, { 'read 200': (r) => r.status === 200 });
}
