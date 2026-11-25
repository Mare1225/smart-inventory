package ec.usfq.smartinventory.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.messaging.EventEnvelope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Contrato de eventos sin broker: cada routing key que publica el codigo tiene JSON Schema y canal en AsyncAPI,
 * los ejemplos del contrato son validos y el sobre que serializa la aplicacion cumple el esquema.
 * La validacion contra eventos reales (outbox + RabbitMQ) esta en MessagingFlowIT.
 */
class EventContractTest {

    static final List<String> PUBLISHED = List.of(
            RabbitConfig.STOCK_RESERVED, RabbitConfig.SUGGESTION_OPENED, RabbitConfig.SUGGESTION_APPROVED);

    /** Igual que el ObjectMapper de Spring Boot (fechas ISO-8601, no epoch). */
    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @ParameterizedTest
    @ValueSource(strings = {RabbitConfig.STOCK_RESERVED, RabbitConfig.SUGGESTION_OPENED, RabbitConfig.SUGGESTION_APPROVED})
    void everyPublishedEventHasSchemaAndAsyncApiChannel(String routingKey) throws Exception {
        assertTrue(Files.exists(EventSchemas.file(routingKey, 1)), "falta contracts/schemas/" + routingKey + ".v1.json");
        String asyncapi = Files.readString(Path.of("contracts", "asyncapi.yaml"));
        assertTrue(asyncapi.contains("address: " + routingKey), "asyncapi.yaml no declara el canal " + routingKey);
        assertTrue(asyncapi.contains("./schemas/" + routingKey + ".v1.json"), "asyncapi.yaml no referencia el esquema");
    }

    @Test
    void consumerQueuesAndDlqAreDeclaredInAsyncApi() throws Exception {
        String asyncapi = Files.readString(Path.of("contracts", "asyncapi.yaml"));
        for (String q : List.of(RabbitConfig.DASHBOARD_Q, RabbitConfig.REPLENISHMENT_Q, RabbitConfig.DLQ)) {
            assertTrue(asyncapi.contains("address: " + q), "asyncapi.yaml no declara la cola " + q);
        }
        assertTrue(asyncapi.contains("name: " + RabbitConfig.EVENTS_EXCHANGE));
    }

    @Test
    void examplesInsideEachSchemaAreValid() {
        for (String type : PUBLISHED) {
            var examples = EventSchemas.schemaJson(type, 1).path("examples");
            assertFalse(examples.isEmpty(), type + " sin ejemplos");
            examples.forEach(ex -> assertEquals(Set.of(), EventSchemas.validate(ex.toString()), type));
        }
    }

    @Test
    void envelopeSerializedByTheApplicationMatchesSchema() throws Exception {
        var envelope = new EventEnvelope(UUID.randomUUID(), RabbitConfig.STOCK_RESERVED, 1, Instant.now(),
                "req-12345678", "smart-inventory", Map.of(
                "reservationId", UUID.randomUUID().toString(), "sku", "SKU-1001", "location", "BOD-UIO",
                "quantity", 35, "destination", "PEDIDO-1",
                "lines", List.of(Map.of("lotCode", "L-B", "quantity", 30), Map.of("lotCode", "L-A", "quantity", 5))));
        assertEquals(Set.of(), EventSchemas.validate(MAPPER.writeValueAsString(envelope)));
    }

    @Test
    void schemaRejectsBreakingChanges() throws Exception {
        // quitar un campo obligatorio o cambiar su tipo debe romper la validacion (prueba que el esquema protege)
        var missingSku = new EventEnvelope(UUID.randomUUID(), RabbitConfig.STOCK_RESERVED, 1, Instant.now(), "req-1",
                "smart-inventory", Map.of("reservationId", UUID.randomUUID().toString(), "location", "BOD-UIO",
                "quantity", 1, "destination", "X", "lines", List.of()));
        assertFalse(EventSchemas.validate(MAPPER.writeValueAsString(missingSku)).isEmpty());

        var wrongType = new EventEnvelope(UUID.randomUUID(), RabbitConfig.SUGGESTION_OPENED, 1, Instant.now(), "req-1",
                "smart-inventory", Map.of("suggestionId", UUID.randomUUID().toString(), "sku", "SKU-1",
                "suggestedQty", "ten"));
        assertFalse(EventSchemas.validate(MAPPER.writeValueAsString(wrongType)).isEmpty());

        var unknownVersion = new EventEnvelope(UUID.randomUUID(), RabbitConfig.STOCK_RESERVED, 2, Instant.now(), "r",
                "smart-inventory", Map.of());
        assertFalse(EventSchemas.validate(MAPPER.writeValueAsString(unknownVersion)).isEmpty());
    }

    @Test
    void additiveOptionalFieldIsBackwardCompatible() throws Exception {
        var withExtra = new EventEnvelope(UUID.randomUUID(), RabbitConfig.SUGGESTION_APPROVED, 1, Instant.now(),
                "req-1", "smart-inventory", Map.of("suggestionId", UUID.randomUUID().toString(), "sku", "SKU-1",
                "approvedBy", "purchasing", "comment", "campo nuevo opcional"));
        assertEquals(Set.of(), EventSchemas.validate(MAPPER.writeValueAsString(withExtra)));
    }
}
