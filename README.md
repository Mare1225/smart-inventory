# Smart Inventory (Desarrollo de Software Moderno, USFQ)

Recorrido vertical: **consultar stock → reservar con FEFO (idempotente) → evento por Outbox → dashboard y reposición por consumidores idempotentes**, con CI/CD DevSecOps y observabilidad.

## Ejecutar

```bash
docker compose up -d --build          # postgres, rabbitmq, app, prometheus, grafana, jaeger
bash scripts/sat.sh                   # SAT automatizado (docs/sat.md)
k6 run perf/reserve.js                # carga controlada; preparar el entorno con perf/README.md
mvn verify                            # unitarias + contrato + integracion (Testcontainers, requiere Docker)
```

Swagger UI: `http://localhost:8080/swagger-ui.html` · Prometheus `:9090` · RabbitMQ `:15672` (guest/guest) · Grafana `:3000` (panel *Smart Inventory - operacion*) · Jaeger `:16686`.

En Windows sin Maven: `docker run --rm -v "$PWD:/src" -w /src -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 mvn -B verify`.
Claves de **desarrollo** (`X-Api-Key`): `dev-ops-key`, `dev-fulfillment-key`, `dev-purchasing-key`. En staging se inyectan por variables de entorno del gestor de secretos; nunca se versionan.

```bash
curl -s -H 'X-Api-Key: dev-fulfillment-key' -H 'Idempotency-Key: demo-0001' -H 'Content-Type: application/json' \
  -d '{"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-1"}' localhost:8080/api/v1/reservations
```

## Mapa historia → implementación → prueba

| Historia | Endpoint / evento | Código | Prueba |
|---|---|---|---|
| US-01 | `GET /api/v1/inventory` | `InventoryController`, `InventoryQueryService` | SAT-1, contrato |
| US-02 | `POST /api/v1/reservations` | `ReservationService`, `FefoAllocationPolicy` | `FefoAllocationPolicyTest`, `ReservationFlowIT`, SAT-3..5 |
| US-03 | `GET/POST …/replenishment/suggestions` | `ReplenishmentConsumer`, `ReorderPointRule`, `ReplenishmentService` | `ReorderPointRuleTest`, SAT-7/8 |
| US-04 | `GET /api/v1/dashboard/inventory` | `DashboardConsumer`, `DashboardQueryService` | SAT-6, SAT-9 |

## Arquitectura (resumen)

`web` (filtros: correlación → autenticación/scope → rate limit; controladores; `problem+json`) → `application` (casos de uso transaccionales) → `domain` (FEFO, reposición: Java puro, sin Spring) · `ratelimit` (token bucket / ventana fija) · `messaging` (Outbox, publicador, consumidores, idempotencia).
Decisiones en `docs/adr/`. Contratos: `openapi/openapi.yaml` (HTTP) y `contracts/asyncapi.yaml` + `contracts/schemas/` (eventos). Runbook en `docs/runbook.md`.

## Patrones y SOLID (dónde está cada uno)

| Patrón | Ubicación | Beneficio probado |
|---|---|---|
| Strategy | `AllocationPolicy`, `ReplenishmentRule`, `RateLimiter` | Prueba unitaria de FEFO sin BD; comparación de estrategias de rate limit |
| Ports & Adapters | `domain`/`application` no importan broker; `messaging` y JDBC son adaptadores | Dominio probado sin infraestructura |
| Outbox + consumidor idempotente | `OutboxWriter`, `OutboxPublisher`, `ProcessedEvents` | `ReservationFlowIT` (atomicidad), SAT-9 |
| Unit of Work | `@Transactional` en el caso de uso | Rollback completo ante `InsufficientStockException` |

Se omitió *Repository* como capa separada a propósito: con SQL explícito y 4 consultas, una capa extra sería un patrón "para cumplir la lista" (ver ADR). Los equipos pueden extraerlo si añaden más agregados.

## Estado verificado

| Parte | Estado | Evidencia |
|---|---|---|
| `mvn verify`: 61 unitarias/web/contrato + 8 integracion (PostgreSQL y RabbitMQ con Testcontainers) | **Aprobado** | `docs/evidencias/sat-resultados.md` |
| SAT-1..11 contra `docker compose` (incluye broker caido y rate limit) | **Aprobado** | `docs/evidencias/sat-resultados.md` |
| Carga k6: p95 consulta 24,5 ms, reserva 82,8 ms, 0 % errores | **Aprobado** | `docs/evidencias/rendimiento.md` |
| Trazas, metricas, panel y alertas | **Verificado en vivo** | `docs/evidencias/observabilidad.md` |
| Semgrep, Spectral, AsyncAPI | **Sin hallazgos** | `docs/security-findings.md` |
| Despliegue a staging, DAST en staging, release `v1.0.0` | Pendiente | issues del repositorio |

## Documentacion

| Tema | Archivo |
|---|---|
| Backlog, trazabilidad historia -> prueba, NFR y defectos | `docs/backlog.md` |
| Sprints, revisiones y retrospectivas | `docs/sprints.md` |
| API Product Canvas (detalle) | `docs/api-canvas/inventory-api.md` |
| Comparacion GitHub Actions / Jenkins / Argo CD | `docs/comparacion-herramientas.md` |
| Hallazgos de seguridad y triage | `docs/security-findings.md` |
| Runbook de incidente | `docs/runbook.md` |
| Cambios por version | `CHANGELOG.md` |

## DAST autenticado
El baseline de ZAP apunta a `/v3/api-docs` (público). Para escanear endpoints protegidos, crear un *context* de ZAP con la cabecera `X-Api-Key` de la cuenta de prueba de staging y registrar hallazgos (severidad, evidencia no sensible, decisión, responsable) en `docs/security-findings.md`.

## Límites conocidos (para discutir en clase)
- Rate limit en memoria por instancia; no limita peticiones sin credenciales (401 antes del límite).
- Autenticación por API key con scopes: suficiente para el laboratorio; producción usaría OIDC/JWT.
- `outbox` y `idempotency_key` crecen: falta job de purga.
- Datos semilla con fechas de nov-2026.
