# Evidencia de rendimiento (SAT-10)

**Entorno:** `docker compose` local (app + PostgreSQL 16 + RabbitMQ 3.13 en la misma maquina), Windows 11 + Docker Desktop 29,
imagen construida desde el commit indicado. k6 0.54.0 en contenedor dentro de la red de compose.
**Carga:** 20 VUs x 60 s: 15 VUs consultan `GET /api/v1/inventory?sku=SKU-PERF`, 5 VUs reservan 1 u. de `SKU-PERF`
con `Idempotency-Key` unica. Limite de tasa elevado y stock de carga sembrado (`perf/README.md`).
**Objetivo:** p95 consulta < 200 ms, p95 reserva < 400 ms, errores < 1 %.

## Corrida 1: defecto en la propia prueba (descartada)
Primera ejecucion del `perf/reserve.js` original: 709 252 peticiones, **p95 = 3,4 ms**. El numero era falso: las 20 VUs
compartian una API key con limite de 10 req/s, asi que > 99 % de las respuestas eran `429` (y, agotado el stock semilla,
`409`). Ambas respuestas se resuelven en ~1 ms y se mezclaban en el p95.
**Correccion:** umbrales solo sobre `status:200` / `status:201`, metricas `throttled` y `sold_out` aparte, SKU de carga
con stock suficiente y limite elevado solo durante la prueba.

## Corrida 2: medicion valida, defecto encontrado en el outbox

| Metrica | Consulta (200) | Reserva (201) |
|---|---|---|
| p50 | 7,5 ms | 45,1 ms |
| p95 | **28,6 ms** | **129,2 ms** |
| p99 | 48,1 ms | 207,7 ms |
| max | 189 ms | 433 ms |

87 454 peticiones (1 457 req/s), 5 361 reservas, 0 % errores, 0 % 429, 0 % 409. **Objetivos cumplidos.**

**Defecto (DEF-01):** `smartinventory_outbox_pending` subio a **~2 700** y tardo ~40 s en vaciarse despues de la carga:
el dashboard quedaba hasta 40 s atrasado. Causa: `OutboxPublisher` esperaba una confirmacion del broker **por mensaje**
y publicaba a lo sumo 50 eventos cada 500 ms (~70 eventos/s), por debajo de las ~89 reservas/s generadas.

## Corrida 3: despues de la correccion de DEF-01
Correccion: confirmacion **por lote** (`waitForConfirmsOrDie` una vez por lote de 200) y drenado de hasta 10 lotes por ciclo.

| Metrica | Consulta (200) | Reserva (201) |
|---|---|---|
| p50 | 6,0 ms | 33,8 ms |
| p95 | **24,5 ms** | **82,8 ms** |
| p99 | 43,1 ms | 143,3 ms |
| max | 252 ms | 452 ms |

106 164 peticiones (1 769 req/s), 7 450 reservas (+39 %), 0 % errores.
**Outbox pendiente muestreado cada 5 s durante la carga:** 0, 21, 14, 14, 34, 61, 7, 25, 49, 16, 78, 43 y **0** al terminar
(maximo 78 frente a ~2 700 antes).

## Reconciliacion despues de ambas cargas
```
 reservas | reservado (seed repuesto antes de la corrida 3) | dashboard.reservations_count
    12811 |                                             7450 |                        12811
dashboard.q 0 · replenishment.q 0 · inventory.dlq 0
```
Cada reserva produjo exactamente un evento procesado por el dashboard (sin perdidas ni duplicados).

## Limites de esta medicion
Una sola maquina (sin latencia de red real entre servicios) y una instancia de la app. En staging los valores seran
mayores; repetir con `BASE_URL` de staging y registrar aqui (issue de evidencias).
