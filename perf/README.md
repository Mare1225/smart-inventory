# Prueba de rendimiento (SAT-10)

Caso critico: consulta de stock (`GET /api/v1/inventory`) y reserva FEFO (`POST /api/v1/reservations`).
Objetivo p95: **consulta < 200 ms, reserva < 400 ms**, error rate < 1 %. Tamano: 20 VUs x 60 s (15 consulta, 5 comando).

## Por que se eleva el limite de tasa
Las 20 VUs comparten una API key. Con el limite real (rafaga 20, 10 req/s) mas del 99 % de las peticiones serian 429,
que responden en ~1 ms y falsean el p95. El rate limiting se prueba por separado (`RateLimiterComparisonTest`,
`WebLayerTest`, SAT-11); aqui se mide la ruta completa API -> PostgreSQL -> outbox.

## Pasos
```bash
RATE_LIMIT_CAPACITY=1000000 RATE_LIMIT_REFILL=1000000 RATE_LIMIT_LIMIT=1000000 docker compose up -d app
docker compose exec -T postgres psql -U inventory -d inventory < perf/seed-perf.sql
k6 run -e BASE_URL=http://localhost:8080 perf/reserve.js
# sin k6 instalado:
docker run --rm --network smart-inventory_default -v "$PWD/perf:/perf" -e BASE_URL=http://app:8080 grafana/k6:0.54.0 run /perf/reserve.js
docker compose up -d app        # volver al limite normal
```

Resultados registrados en `docs/evidencias/rendimiento.md`.
