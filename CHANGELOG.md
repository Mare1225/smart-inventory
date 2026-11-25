# Changelog

Formato: [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) · versionado semantico.

## [1.0.0] - pendiente de tag

### Agregado
- Contratos de eventos: `contracts/asyncapi.yaml` (AsyncAPI 3.0) y JSON Schema de `inventory.stock-reserved`,
  `replenishment.suggestion-opened` y `replenishment.suggestion-approved`.
- OpenAPI 1.1.0 (compatible): 415, `MISSING_HEADER`, `X-Request-Id`, detalle del rate limit y catalogo de codigos.
- Cabeceras de seguridad (CSP, nosniff, X-Frame-Options, Referrer-Policy, `Cache-Control: no-store` en la API).
- Observabilidad: trazas JDBC y AMQP, metrica `smartinventory.event.lag`, contador de duplicados, Jaeger en compose,
  panel de Grafana aprovisionado y alertas AppDown, RateLimitRejectionsHigh, EventLagHigh, ConsumerMissing.
- Pruebas: capa web, rate limit, conformidad con OpenAPI, contrato de eventos e integracion con RabbitMQ (69 en total).
- CI: validacion de contratos (Spectral, AsyncAPI) y SAST con Semgrep.
- Documentacion: backlog y trazabilidad, sprints, comparacion de herramientas, API canvas, hallazgos de seguridad,
  evidencias de SAT, rendimiento y observabilidad, ADR-0004.

### Corregido
- 404, 405 y 415 respondian 500.
- El outbox se atrasaba ~40 s bajo carga: ahora confirma por lote.
- Alertas de DLQ y colas sin datos en Prometheus.
- La prueba k6 media respuestas 429 en lugar del caso critico.
- Pruebas de integracion incompatibles con Docker Engine 29.
- `scripts/sat.sh` no ejecutaba en checkouts de Windows (CRLF).
- springdoc 2.8.3 para compatibilidad con Spring Boot 3.4.

## [0.1.0] - 2026-11-23
- Version inicial: consulta de stock, reserva FEFO idempotente, outbox, consumidores de dashboard y reposicion,
  rate limiting, pipeline y documentacion base.
