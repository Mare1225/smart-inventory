# Resultados SAT (ejecucion local)

**Fecha:** 2026-11-24 · **Entorno:** `docker compose up -d --build` (app, PostgreSQL 16, RabbitMQ 3.13, Prometheus, Grafana, Jaeger)
con datos semilla V2 · **Rama:** `feat/cierre-deber` · **Ejecutor:** script `scripts/sat.sh` desde un contenedor en la red de compose.
La ejecucion contra **staging** queda registrada por el pipeline (job `dast-sat`) cuando staging este configurado.

| ID | Historia | Esperado | Observado | Resultado | Responsable |
|---|---|---|---|---|---|
| SAT-1 | US-01 | 200 con fisico/reservado/disponible | 200 | PASS | Alexander |
| SAT-2 | Seguridad | 401 sin clave / 403 sin scope | 401 / 403 `problem+json` | PASS | Alexander |
| SAT-3 | US-02 | 201; L-B antes que L-A; L-OLD nunca | 201 | PASS | Alexander |
| SAT-4 | US-02 | 200 + `Idempotent-Replay`, misma reserva | 200, misma `reservationId` | PASS | Alexander |
| SAT-5 | US-02 | 409 `INSUFFICIENT_STOCK` | 409 `INSUFFICIENT_STOCK` | PASS | Alexander |
| SAT-6 | US-04 | Fila SKU-1001 en dashboard en <= 15 s | presente en < 1 s | PASS | Alexander |
| SAT-7 | US-03 | Una sola sugerencia OPEN para SKU-2002 | 1 sugerencia OPEN | PASS | Alexander |
| SAT-8 | US-03 | approve 200, repetido 409 | 200 / 409 `INVALID_TRANSITION` | PASS | Alexander |
| SAT-9 | Disponibilidad | Broker caido: reserva 201 y evento sale al volver | 201 con RabbitMQ detenido; `outbox_pending=1`; readiness 503; al reiniciar `outbox_pending=0` en ~15 s | PASS | Alexander |
| SAT-10 | Rendimiento | p95 consulta < 200 ms, reserva < 400 ms | 24,5 ms / 82,8 ms, 0 % errores | PASS | Alexander |
| SAT-11 | Rate limit | Rafaga > 20 => 429 + Retry-After | 60 GET paralelos: 20 x 200, 40 x 429 con `Retry-After: 1` y `RATE_LIMITED` | PASS | Alexander |

Salida del script (ultima corrida, con Jaeger y Grafana en el stack):
```
PASS  SAT-1 GET inventory => 200
PASS  SAT-2a sin X-Api-Key => 401
PASS  SAT-2b ops-console sin scope reserve => 403
PASS  SAT-3 POST reservations => 201
PASS  SAT-4a replay => 200
PASS  SAT-4b misma reservationId
PASS  SAT-5 stock insuficiente => 409
PASS  SAT-5b code INSUFFICIENT_STOCK
PASS  SAT-6 dashboard refleja SKU-1001 con lastUpdatedAt
PASS  SAT-7a reserva SKU-2002 => 201
PASS  SAT-7b sugerencia OPEN para SKU-2002
PASS  SAT-7c una sola sugerencia abierta (sin duplicados)
PASS  SAT-8a approve => 200
PASS  SAT-8b approve repetido => 409
SAT completado: 2026-11-24T20:00:15Z
```

## Pruebas automatizadas (`mvn verify`)

| Suite | Tipo | Casos | Resultado |
|---|---|---|---|
| FefoAllocationPolicyTest, ReorderPointRuleTest | Unitarias de dominio | 10 | PASS |
| RateLimiterComparisonTest | Unitarias / comparacion de estrategias | 5 | PASS |
| WebLayerTest, RateLimitFilterTest | Capa web (MockMvc) | 26 | PASS |
| OpenApiContractTest, ApiConformanceTest | Contrato HTTP | 12 | PASS |
| EventContractTest | Contrato de eventos | 8 | PASS |
| ReservationFlowIT | Integracion PostgreSQL (commit/rollback, idempotencia, 50 hilos) | 4 | PASS |
| MessagingFlowIT | Integracion PostgreSQL + RabbitMQ (flujo, duplicados, DLQ) | 4 | PASS |
| **Total** | | **69** | **PASS** |

## Exploracion de errores que origino DEF-02
Antes de la correccion: `GET /api/v1/reservations` (metodo no permitido), `GET /api/v1/nada` y `POST` con
`Content-Type: application/xml` respondian **500 INTERNAL_ERROR**. Despues: 405 (con `Allow`), 404 y 415.
