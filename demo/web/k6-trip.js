import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: 3,
  duration: '8s',
  thresholds: { http_req_failed: ['rate==0'], checks: ['rate==1'] },
};

export default function () {
  const response = http.get('http://127.0.0.1:' + __ENV.DS03_TRIP_PORT + __ENV.DS03_TRIP_PATH, {
    headers: { Authorization: __ENV.DS03_TRIP_AUTHORIZATION },
    timeout: '2s',
  });
  check(response, { 'Trip details return HTTP 200': res => res.status === 200 });
  sleep(0.2);
}

export function handleSummary(data) {
  const metrics = data.metrics;
  return { stdout: JSON.stringify({
    requests: metrics.http_reqs?.values?.count,
    requestsPerSecond: metrics.http_reqs?.values?.rate,
    p95Milliseconds: metrics.http_req_duration?.values?.['p(95)'],
    errorRate: metrics.http_req_failed?.values?.rate,
    checkRate: metrics.checks?.values?.rate,
    virtualUsers: 3,
    durationSeconds: 8,
  }) + '\n' };
}
