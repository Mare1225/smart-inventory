# Hallazgos de seguridad y triage

Politica: **bloquean** el pipeline secretos expuestos (gitleaks), SAST severidad ERROR (Semgrep), dependencias con CVE
HIGH/CRITICAL con parche disponible (Trivy fs) y CRITICAL en la imagen (Trivy image). Los hallazgos DAST se triagean aqui
(el job no falla automaticamente para no bloquear por falsos positivos; un hallazgo High sin decision bloquea el release).
Excepciones: `.trivyignore` con CVE, motivo, responsable y fecha de revision.

| ID | Fuente | Severidad | Hallazgo | Evidencia (no sensible) | Decision | Responsable | Estado |
|---|---|---|---|---|---|---|---|
| SEC-01 | Revision manual / exploracion de errores | Media | 404/405/415 devolvian 500 con mensaje generico: oculta el error real al integrador y ensucia la tasa de 5xx (alerta) | `GET /api/v1/reservations` -> 500 | Corregir | Alexander | Corregido (DEF-02) |
| SEC-02 | Revision previa a ZAP (reglas 10021, 10020, 10038, 10049) | Baja | Sin `X-Content-Type-Options`, `X-Frame-Options`, CSP ni `Cache-Control` | `curl -D -` sin cabeceras | Corregir | Alexander | Corregido (DEF-03) |
| SEC-03 | Revision manual | Baja | Errores de parametros exponian nombres de metodos Java (`list.size`) | cuerpo 400 | Corregir | Alexander | Corregido |
| SEC-04 | Semgrep local (`p/java`, `p/owasp-top-ten`, `p/secrets`) | - | Sin hallazgos de severidad ERROR | ejecucion 2026-11-24 | - | Alexander | OK |
| SEC-08 | Trivy fs (gate SCA del PR #1) | Alta | 5 CVE HIGH: pgjdbc 42.7.5 (CVE-2025-49146, CVE-2026-42198, CVE-2026-54291) y Spring Boot Actuator 3.4.5 (CVE-2026-22731, CVE-2026-22733, bypass de autenticacion) | job `sca` fallido | Corregir: Spring Boot 3.5.16, pgjdbc 42.7.15, springdoc 2.8.17; 69 pruebas en verde | Alexander | Corregido |
| SEC-05 | Diseno | Media | Rate limit en memoria por instancia; sin limite para peticiones no autenticadas | ADR-0003 | Aceptar en laboratorio; en produccion limite por IP en gateway + Redis | Equipo | Aceptado |
| SEC-06 | Diseno | Media | API keys estaticas (sin rotacion ni expiracion) | `application.yml` usa variables de entorno; valores dev solo locales | Aceptar en laboratorio; produccion OIDC/JWT | Equipo | Aceptado |
| SEC-07 | Compose local | Baja | Grafana con acceso anonimo (Viewer) y RabbitMQ guest/guest | `docker-compose.yml` | Aceptar: solo laboratorio local, nunca staging | Equipo | Aceptado |

## Controles verificados por pruebas
- Autorizacion vertical: `WebLayerTest.verticalPrivilegeEscalationIsBlocked` (ops-console no reserva; compras no lee
  inventario; fulfillment no aprueba). La autorizacion horizontal no aplica: no hay recursos por usuario (los clientes son
  sistemas con scopes); la idempotencia separa claves por cliente (`<client>:<Idempotency-Key>`).
- Inyeccion: filtros con patron `[A-Za-z0-9_-]` y `sort` por lista blanca (`injectionAttemptInFilterIs400`); SQL siempre
  parametrizado.
- Exposicion de errores: nunca stack traces ni SQL (`GlobalExceptionHandler`); `requestId` para soporte.
- Abuso de rate limit: `RateLimitFilterTest`, SAT-11.
- Log injection: `X-Request-Id` saneado (`unsafeRequestIdIsReplacedToPreventLogInjection`).

## DAST (OWASP ZAP) en staging
Pendiente de ejecucion contra staging (issue asignado). Registrar aqui cada alerta ZAP: ID de regla, severidad, URL,
decision (corregir / aceptar con justificacion / falso positivo) y responsable. Ejecutar baseline sobre `/v3/api-docs` y un
escaneo autenticado con un *context* que agregue `X-Api-Key` de la cuenta de prueba de staging.
