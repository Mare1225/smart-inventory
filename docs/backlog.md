# Backlog y trazabilidad

Prioridad: P1 = recorrido vertical minimo; P2 = requisito del deber que lo hace operable; P3 = mejora.
Cada historia enlaza criterio -> endpoint/evento -> codigo -> cambio de datos -> pruebas -> evidencia.

## Historias de usuario

| ID | Prio | Historia | Criterios de aceptacion | Endpoint / evento | Codigo | Datos | Pruebas | Evidencia |
|---|---|---|---|---|---|---|---|---|
| US-01 | P1 | Como operador, quiero consultar stock por SKU, ubicacion y lote para decidir una accion de bodega. | (a) paginado `page/size<=100`; (b) `physical`, `reserved`, `available` por lote; (c) sin datos => 200 con `items: []`; (d) filtros y orden invalidos => 400 `problem+json` | `GET /api/v1/inventory` | `InventoryController`, `InventoryQueryService` | lectura de `batch` | `WebLayerTest` (inventory*, injection*, pageSize*), `ApiConformanceTest.inventoryPage`, SAT-1 | `docs/evidencias/sat-resultados.md` |
| US-02 | P1 | Como sistema de fulfillment, quiero reservar una cantidad usando FEFO para no asignar lotes vencidos. | (a) lotes por vencimiento, nunca vencidos; (b) atomica, sin sobreasignar (`reserved <= physical`); (c) misma `Idempotency-Key` => 200 + misma reserva; misma clave con otro cuerpo => 422; (d) stock insuficiente => 409 sin cambios | `POST /api/v1/reservations` -> `inventory.stock-reserved` | `ReservationService`, `FefoAllocationPolicy`, `OutboxWriter` | `batch.reserved`, `reservation`, `reservation_line`, `idempotency_key`, `outbox` | `FefoAllocationPolicyTest` (7), `ReservationFlowIT` (4, incl. 50 hilos concurrentes), `WebLayerTest`, SAT-3/4/5, k6 | SAT, `docs/evidencias/rendimiento.md` |
| US-03 | P1 | Como responsable de compras, quiero recibir una alerta de riesgo de quiebre y revisar una sugerencia. | (a) regla: disponible no vencido < punto de reorden => sugerir hasta nivel objetivo; (b) se publica `replenishment.suggestion-opened`; (c) eventos repetidos no duplican una sugerencia OPEN; (d) aprobar OPEN -> APPROVED; repetir => 409 | `inventory.stock-reserved` (consume), `GET /api/v1/replenishment/suggestions`, `POST .../{id}/approve`, `replenishment.suggestion-*` | `ReplenishmentConsumer`, `ReorderPointRule`, `ReplenishmentService` | `replenishment_suggestion` (indice unico OPEN por SKU), `processed_event` | `ReorderPointRuleTest`, `MessagingFlowIT` (flujo, duplicados, aprobacion), SAT-7/8 | SAT |
| US-04 | P1 | Como supervisor, quiero ver en el dashboard el cambio de inventario y el estado del pedido. | (a) vista actualizada por consumidor; (b) `processingDelayMs` y `stale` identifican demora; (c) expone `lastUpdatedAt`; (d) error de procesamiento => DLQ + alerta | `GET /api/v1/dashboard/inventory` | `DashboardConsumer`, `DashboardQueryService` | `dashboard_view` | `MessagingFlowIT` (flujo, DLQ), SAT-6, SAT-9 | SAT, panel Grafana |

## Requisitos no funcionales

| NFR | Objetivo | Implementacion | Verificacion |
|---|---|---|---|
| Seguridad | API key por cliente con scopes; validar entradas; sin secretos/PII en logs | `ApiKeyAuthFilter`, Bean Validation, lista blanca de `sort`, `SecurityHeadersFilter`, `SecurityProperties.toString` | `WebLayerTest` (401/403/escalada/inyeccion), Semgrep, gitleaks, Trivy, ZAP (`docs/security-findings.md`) |
| Rendimiento | p95 consulta < 200 ms, comando < 400 ms con 20 VUs x 60 s | indices `ix_batch_fefo`, seccion critica corta, outbox por lote | k6: **24,5 ms / 82,8 ms** (`docs/evidencias/rendimiento.md`) |
| Disponibilidad | caida de broker no detiene reservas; BD caida => 503 + Retry-After | Outbox, readiness con `db` y `rabbit`, `GlobalExceptionHandler.dependencyDown`, reintentos con backoff del consumidor | SAT-9 (broker detenido: 201, outbox retiene 1 evento, drena en ~15 s) |
| Integridad | saldo nunca negativo; comandos idempotentes | `FOR UPDATE`, `CHECK (reserved <= physical)`, `idempotency_key`, `processed_event` | `ReservationFlowIT.concurrentReservationsNeverOversell`, reconciliacion post-carga (12 811 = 12 811) |
| Mantenibilidad | modulos con responsabilidades claras; configuracion externa | paquetes `web/application/domain/messaging/ratelimit/config`, `application.yml` + variables de entorno | ADRs, README |
| Observabilidad | logs JSON correlacionados, metricas, trazas, health, alertas | ECS logs con `requestId`/`traceId`, Micrometer, OTLP -> Jaeger, Grafana aprovisionado | `docs/evidencias/observabilidad.md` |

## Tareas por sprint (resumen; detalle en `docs/sprints.md`)

| Sprint | Tareas | Estado |
|---|---|---|
| 1 | Historias y criterios; OpenAPI base; ADR 0001-0003; guion SAT; plan de pruebas | Hecho |
| 2 | Esquema Flyway; consulta y reserva FEFO; outbox; consumidores; pruebas unitarias e integracion | Hecho |
| 3 | Rate limit (2 estrategias); pipeline CI/CD con gates; AsyncAPI + JSON Schema; pruebas de contrato; staging/DAST | Hecho salvo despliegue a staging y DAST en staging (issues) |
| 4 | Observabilidad (trazas, panel, alertas); carga k6; correcciones DEF-01..04; runbook; release; demo | Hecho salvo release etiquetado, capturas y demo (issues) |

## Defectos registrados

| ID | Encontrado por | Defecto | Correccion | Commit / prueba |
|---|---|---|---|---|
| DEF-00 | `mvn verify` | Testcontainers 1.20 incompatible con Docker Engine 29: integracion no corria | Testcontainers 1.21.4 | `fix(test)` |
| DEF-01 | k6 | Outbox publicaba ~70 ev/s: atraso del dashboard ~40 s bajo carga | Confirmacion por lote (ADR-0004) | `perf(outbox)`; rendimiento.md |
| DEF-02 | Exploracion manual de errores | 404/405/415 respondian 500 | Manejadores en `GlobalExceptionHandler` | `fix(web)`; `WebLayerTest.wrongMethodIs405*` etc. |
| DEF-03 | Revision previa a DAST | Faltaban cabeceras de seguridad | `SecurityHeadersFilter` | `fix(web)`; `apiResponsesCarrySecurityHeaders` |
| DEF-04 | Revision de alertas | Alertas por cola sin datos (Prometheus no raspaba metricas detalladas) | Job `rabbitmq-queues` | `fix(ops)` |
| DEF-05 | Primera corrida k6 | La prueba de carga media 429 y no el caso critico | Umbrales por status, SKU de carga | `fix(perf)` |
| DEF-06 | Primer SAT en Windows | `sat.sh` con CRLF no ejecutaba | `.gitattributes` | `build:` |
