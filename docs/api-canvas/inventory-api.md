# API Product Canvas · Inventory API (detalle)

Version resumida en `Inventory_API_Product_Canvas.docx` (entregable del equipo). Este documento es el detalle al que
remite la seccion *Notes* del canvas. Contrato normativo: `openapi/openapi.yaml` (v1, documento 1.1.0) y
`contracts/asyncapi.yaml`.

| Campo | Valor |
|---|---|
| Bounded context | Inventario |
| Version | API v1 (ruta `/api/v1`) · eventos schemaVersion 1 |
| Contacto | Equipo Smart Inventory (repo `Mare1225/smart-inventory`, issues de GitHub) |
| Servidor | `http://localhost:8080` (compose) · staging: secreto `STAGING_URL` |

## Propuesta de valor
Unica fuente de verdad del stock por lote: permite comprometerlo sin sobreasignar y sin usar lotes vencidos, y avisa a
reposicion y al dashboard sin acoplarlos a la transaccion.

## Funciones principales
| Funcion | Operacion | Scope |
|---|---|---|
| Consultar stock fisico, reservado y disponible | `GET /api/v1/inventory` | `inventory:read` |
| Reservar por FEFO, atomica e idempotente | `POST /api/v1/reservations` | `inventory:reserve` |
| Ver sugerencias de reposicion (no muta stock) | `GET /api/v1/replenishment/suggestions` | `replenishment:read` |
| Aprobar sugerencia OPEN -> APPROVED | `POST /api/v1/replenishment/suggestions/{id}/approve` | `replenishment:approve` |
| Vista de dashboard con antiguedad del dato | `GET /api/v1/dashboard/inventory` | `inventory:read` |

## Agregados y entidades
- **Batch** (lote por SKU + ubicacion + `lotCode`): `physical`, `reserved`, `expiresOn`; invariante `0 <= reserved <= physical`.
- **Reservation** + **ReservationLine** (lote + cantidad): resultado FEFO; una reserva por `Idempotency-Key` y cliente.
- **ReplenishmentSuggestion**: OPEN | APPROVED; a lo sumo una OPEN por SKU.
- **DashboardView**: proyeccion por SKU mantenida por eventos.

## Parametros y cabeceras
`sku`, `location`, `lot`, `page`, `size` (1-100), `sort` (sku|location|lotCode|expiresOn|available), `order` (asc|desc),
`status`, `supplier`, `limit`. Cabeceras: `X-Api-Key` (obligatoria), `Idempotency-Key` (8-100, obligatoria en reservas),
`X-Request-Id` (opcional).

## Peticiones y respuestas
| Caso | Respuesta |
|---|---|
| Pagina de stock (puede estar vacia) | 200 `StockPage {items, page, size, totalItems}` |
| Reserva creada | 201 `Reservation {reservationId, status, lines[]}` |
| Reintento con la misma clave | 200 + `Idempotent-Replay: true`, misma reserva |
| Stock insuficiente | 409 `INSUFFICIENT_STOCK` (no reintentar igual) |
| SKU/ubicacion sin stock registrado | 404 `NOT_FOUND` |
| Cuerpo invalido / clave reutilizada con otro cuerpo | 422 `VALIDATION_FAILED` / `IDEMPOTENCY_KEY_REUSED` |
| Parametro o cabecera invalidos | 400 `INVALID_PARAMETER` / `MISSING_HEADER` / `MALFORMED_REQUEST` |
| Sin credenciales / sin scope | 401 `UNAUTHENTICATED` / 403 `FORBIDDEN` |
| Limite excedido | 429 `RATE_LIMITED` + `Retry-After` |
| BD no disponible | 503 `DEPENDENCY_UNAVAILABLE` + `Retry-After` |

## Protocolo asincrono
AMQP 0-9-1 (RabbitMQ 3.13), exchange `smartinventory.events` (topic), Outbox, entrega al-menos-una-vez.
- **Envia:** `inventory.stock-reserved` (consumen dashboard y reposicion), `replenishment.suggestion-opened`,
  `replenishment.suggestion-approved`.
- **Recibe:** `inventory.stock-reserved` (consumidores internos de dashboard y reposicion).
- **Sobre:** `eventId`, `eventType`, `schemaVersion`, `occurredAt`, `correlationId`, `producer`, `payload`.

## Requisitos de calidad
| Requisito | Objetivo | Medido |
|---|---|---|
| p95 consulta | < 200 ms | 24,5 ms (local, 20 VUs) |
| p95 comando | < 400 ms | 82,8 ms (local, 20 VUs) |
| Disponibilidad | 99,5 % (propuesta, por confirmar) | n/a en laboratorio |
| Integridad | nunca saldo negativo ni lotes vencidos | 50 reservas concurrentes sin sobreasignar |
| Rate limit | rafaga 20, 10 req/s por cliente | 20 x 200 y 40 x 429 en rafaga de 60 |
| Broker caido | reservas siguen, eventos esperan en outbox | SAT-9 |

## Temas abiertos
- No existe comando para **recibir stock** (entrada de mercaderia) ni para **cancelar/liberar reservas**: candidatos para v1.x
  (aditivos y compatibles). Hoy el stock se carga por migracion/semilla.
- Autenticacion por API key es suficiente para el laboratorio; produccion usaria OIDC/JWT con los mismos scopes.
- Rate limit en memoria por instancia: con varias replicas, contador compartido (Redis).
- Purga de `outbox` publicado e `idempotency_key` antiguas.
