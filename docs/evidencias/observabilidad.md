# Evidencia de observabilidad

Stack local: `docker compose up -d --build`. Grafana `http://localhost:3000` (panel *Smart Inventory - operacion*, abre sin
login solo en local), Prometheus `:9090`, Jaeger `:16686`, RabbitMQ `:15672`.

## Logs (JSON ECS, correlacionados)
Cada linea lleva `service.name`, `log.level`, `@timestamp`, `requestId` (= `X-Request-Id` = `correlationId` del evento) y,
dentro de una traza, `traceId`/`spanId`. Las API keys nunca se registran (`SecurityProperties.ApiKey.toString`).
```json
{"@timestamp":"2026-11-24T20:00:13.099Z","log.level":"INFO","service.name":"smart-inventory",
 "log.logger":"ec.usfq.smartinventory.messaging.DashboardConsumer","message":"dashboard updated for sku SKU-1001",
 "traceId":"af2082d3d52b0c69b92ecc98c57d4f31","spanId":"7286cc616dee5353",
 "requestId":"0b221c8f-1ae6-4997-86b5-b0ff708e7bfe","ecs.version":"8.11"}
```
Pregunta "que paso en esta reserva": `docker compose logs app | grep <requestId>` muestra API, outbox y ambos consumidores.

## Trazas (OTLP -> Jaeger)
Traza de `POST /api/v1/reservations` (10 spans): `http post /api/v1/reservations` 65 ms > `connection` 53 ms > 8 spans
`query`/`result-set` (idempotencia, `FOR UPDATE`, updates, outbox) de 1-10 ms. Muestra donde se consume el tiempo.

**Limite conocido:** el publicador del outbox corre en un `@Scheduled`, asi que el consumo aparece como otra traza (con
spans AMQP de publicacion/consumo). La union logica entre ambas es el `requestId`/`correlationId`, presente en el evento,
en el mensaje AMQP (`correlation_id`) y en los logs. Mejora posible: guardar `traceparent` en la fila del outbox.

## Metricas (Prometheus)
| Pregunta operativa | Metrica |
|---|---|
| Tasa, errores y latencia | `http_server_requests_seconds_*` (histograma, p50/p95 por `uri`) |
| Throttling | `smartinventory_ratelimit_rejected_total{client}` |
| Resultado de reservas | `smartinventory_reservations_total{result=created/replayed/rejected}` |
| Atraso del consumidor | `smartinventory_event_lag_seconds{consumer,eventType}` (occurredAt -> procesado) |
| Eventos sin publicar | `smartinventory_outbox_pending` |
| Profundidad de colas / DLQ / consumidores | `rabbitmq_detailed_queue_messages{queue}`, `rabbitmq_detailed_queue_consumers` |
| Duplicados absorbidos | `smartinventory_events_duplicates_total{consumer}` |

Verificado en vivo tras el SAT: `rabbitmq_detailed_queue_messages{queue="inventory.dlq"} = 0`,
`smartinventory_outbox_pending = 0`, series de `event_lag` para los 2 consumidores.

## Health
`/actuator/health/liveness` (proceso) y `/actuator/health/readiness` (incluye `db` y `rabbit`; sin detalles ni secretos).
Con RabbitMQ detenido la readiness devolvio `{"status":"DOWN"}` 503 mientras las reservas seguian respondiendo 201 (SAT-9).

## Alertas (`ops/alerts.yml`)
AppDown, HighErrorRate, HighP95Latency, RateLimitRejectionsHigh, OutboxBacklog, EventLagHigh, DeadLetterQueueNotEmpty,
ConsumerQueueAging, ConsumerMissing. Todas enlazan al runbook. La falla del pipeline la notifica GitHub Actions.

## Capturas
Pendiente (issue de evidencias): capturas del panel durante la carga k6, de una traza en Jaeger y de la alerta
DeadLetterQueueNotEmpty disparada con un mensaje envenenado.
