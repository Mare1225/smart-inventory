# Registro de sprints

Equipo: 2 integrantes. Flujo: ramas cortas -> pull request revisado -> `main`; imagen `sha-<commit>`; release con tag semver.

## Sprint 1 · Definir
**Objetivo:** requisitos, arquitectura de implementacion y contratos antes de codificar.
**Entregado:** historias US-01..04 con criterios (`docs/backlog.md`), NFR con objetivos medibles, OpenAPI base
(`openapi/openapi.yaml` 1.0.0), esquema del evento `inventory.stock-reserved`, ADR-0001 (outbox), ADR-0002 (FEFO con
bloqueo pesimista), ADR-0003 (rate limit), guion SAT (`docs/sat.md`), plan de pruebas (piramide: dominio -> web ->
contrato -> integracion -> SAT/DAST/carga).
**Revision:** contrato revisado contra los criterios; se decidio Inventario + Reserva + Reposicion + Dashboard; Flota fuera.
**Retrospectiva:** (+) contrato primero evito discusiones de formato despues. (-) faltaba el contrato de los eventos de
reposicion. **Accion:** documentar todo evento que publique el codigo (cerrado en Sprint 3 con AsyncAPI).

## Sprint 2 · Construir
**Objetivo:** API de inventario y reserva, persistencia, publicacion y consumo de eventos.
**Entregado:** Flyway V1/V2, `InventoryQueryService`, `ReservationService` (FEFO, `FOR UPDATE`, idempotencia),
`OutboxWriter`/`OutboxPublisher`, `DashboardConsumer`, `ReplenishmentConsumer`, `processed_event`, DLX/DLQ, manejo de
errores `problem+json`. Pruebas: dominio (15) y `ReservationFlowIT` (atomicidad, rollback, idempotencia, 50 hilos).
**Revision (demo):** reserva de 35 u. de SKU-1001 consume L-B y L-A, ignora L-OLD vencido; replay devuelve la misma reserva.
**Retrospectiva:** (+) el dominio sin Spring se probo rapido. (-) el codigo de la capa Spring no se habia compilado en el
entorno inicial. **Accion:** gate de `mvn verify` obligatorio en el PR.

## Sprint 3 · Integrar y proteger
**Objetivo:** CI/CD con controles DevSecOps, rate limit y resiliencia, contratos validados.
**Entregado:** pipeline (`.github/workflows/ci.yml`): gitleaks, build + 69 pruebas, Spectral + AsyncAPI, Semgrep, Trivy fs,
imagen `sha-*` no root + Trivy image + SBOM CycloneDX, deploy a staging con smoke test y rollback, SAT + ZAP, release con
aprobacion. Rate limit token bucket / ventana fija con comparacion medida. AsyncAPI 3.0 + 3 JSON Schema; pruebas de
conformidad OpenAPI y de eventos; `MessagingFlowIT` con RabbitMQ real (duplicados, DLQ).
Defectos corregidos: DEF-00 (Testcontainers/Docker 29), DEF-02 (500 en 404/405/415), DEF-03 (cabeceras), DEF-06 (CRLF).
**Revision:** SAT-1..9 y SAT-11 en PASS localmente.
**Retrospectiva:** (+) explorar errores a mano encontro 3 defectos que las pruebas no cubrian. (-) CodeQL no corre en repo
privado sin GHAS. **Accion:** Semgrep como SAST. Pendiente: staging y DAST (issues).

## Sprint 4 · Operar y cerrar
**Objetivo:** observabilidad, carga, correcciones y release.
**Entregado:** trazas OTLP (HTTP + JDBC + AMQP) en Jaeger, metrica de atraso evento->consumidor, panel Grafana
aprovisionado, 9 alertas con runbook, k6 con p95 24,5 ms / 82,8 ms. Defectos: DEF-01 (outbox lento, ADR-0004), DEF-04
(alertas de cola ciegas), DEF-05 (k6 media 429).
**Revision:** reconciliacion post-carga exacta (12 811 reservas = 12 811 en dashboard, DLQ vacia).
**Retrospectiva:** _(completar en la reunion final)_ · **Pendiente:** release `v1.0.0`, capturas, demo de 10-12 min (issues).

## Historial de versiones
| Version | Contenido |
|---|---|
| API `1.0.0` | Contrato inicial (Sprint 1) |
| API `1.1.0` | Cambio compatible: 415, MISSING_HEADER, X-Request-Id, detalle de rate limit |
| Eventos `schemaVersion 1` | stock-reserved, suggestion-opened, suggestion-approved |
| Software `v1.0.0` | Release final (ver `CHANGELOG.md`) |
