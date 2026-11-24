package ec.usfq.smartinventory.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Sobre comun de todos los eventos (contrato: contracts/asyncapi.yaml y contracts/schemas/). */
public record EventEnvelope(UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
                            String correlationId, String producer, Map<String, Object> payload) {
}
