// Carga controlada con k6 (ver perf/README.md para preparar el entorno):
//   k6 run -e BASE_URL=http://localhost:8080 perf/reserve.js
// Objetivo p95: consulta < 200 ms, reserva < 400 ms. Tamano: 20 VUs x 60 s (15 consultas + 5 comandos).
//
// Los umbrales de latencia se calculan SOLO sobre respuestas exitosas (200 consulta, 201 reserva):
// un 429 o un 409 se resuelve en ~1 ms y, si se mezcla, el p95 deja de medir el caso critico.
// Por eso la prueba se corre con el limite de tasa elevado y un SKU de carga con stock suficiente.
import http from 'k6/http';
import { check } from 'k6';
import { Rate } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const SKU = __ENV.PERF_SKU || 'SKU-PERF';
const LOCATION = __ENV.PERF_LOCATION || 'BOD-UIO';

const throttled = new Rate('throttled');      // proporcion de 429 (debe ser ~0 con el limite elevado)
const soldOut = new Rate('sold_out');         // proporcion de 409 (stock de carga agotado => repetir el seed)

export const options = {
  scenarios: {
    queries: { executor: 'constant-vus', vus: 15, duration: '60s', exec: 'query' },
    commands: { executor: 'constant-vus', vus: 5, duration: '60s', exec: 'reserve' },
  },
  thresholds: {
    'http_req_duration{scenario:queries,status:200}': ['p(95)<200'],
    'http_req_duration{scenario:commands,status:201}': ['p(95)<400'],
    http_req_failed: ['rate<0.01'],
    throttled: ['rate<0.01'],
    sold_out: ['rate<0.01'],
  },
};
// 409 y 429 son respuestas de negocio esperadas (no cuentan como error HTTP), pero se miden aparte.
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 429));

export function query() {
  const r = http.get(`${BASE}/api/v1/inventory?sku=${SKU}&size=10`,
    { headers: { 'X-Api-Key': __ENV.OPS_KEY || 'dev-ops-key' } });
  throttled.add(r.status === 429);
  check(r, { 'consulta 200': (x) => x.status === 200 });
}

export function reserve() {
  const r = http.post(`${BASE}/api/v1/reservations`,
    JSON.stringify({ sku: SKU, location: LOCATION, quantity: 1, destination: 'PERF' }),
    { headers: { 'X-Api-Key': __ENV.FULFILLMENT_KEY || 'dev-fulfillment-key', 'Idempotency-Key': uuidv4(), 'Content-Type': 'application/json' } });
  throttled.add(r.status === 429);
  soldOut.add(r.status === 409);
  check(r, { 'reserva 201': (x) => x.status === 201 });
}
