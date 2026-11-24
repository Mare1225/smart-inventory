package ec.usfq.smartinventory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ec.usfq.smartinventory.application.ReplenishmentService;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.config.RabbitConfig;
import ec.usfq.smartinventory.contract.EventSchemas;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Recorrido asincrono completo con PostgreSQL y RabbitMQ reales: reserva -> outbox -> publisher confirms -> colas
 * dashboard.q y replenishment.q -> consumidores idempotentes. Verifica ademas que cada evento publicado cumple su
 * JSON Schema (contracts/schemas), que un duplicado no tiene efecto y que un mensaje envenenado termina en la DLQ.
 */
@Testcontainers
@SpringBootTest(properties = {"app.outbox.poll-ms=100"})
class MessagingFlowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management");

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired ReservationService reservations;
    @Autowired ReplenishmentService replenishment;
    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbitTemplate;

    /** SKU propio por prueba, con politica de reorden: punto 20, objetivo 100. */
    private void seed(String sku, int physical) {
        jdbc.update("insert into batch (sku, location, lot_code, expires_on, physical) values (?, 'BOD-IT', 'L1', current_date + 30, ?)",
                sku, physical);
        jdbc.update("insert into reorder_config (sku, reorder_point, target_level, supplier) values (?, 20, 100, 'PROV-IT')", sku);
    }

    private ReservationService.Result reserve(String sku, int qty, String correlationId) {
        return reservations.reserve(new ReservationService.Command("it-client", "key-" + UUID.randomUUID(), sku,
                "BOD-IT", qty, "PEDIDO-IT", correlationId));
    }

    private Integer dashboardCount(String sku) {
        List<Integer> r = jdbc.queryForList("select reservations_count from dashboard_view where sku = ?", Integer.class, sku);
        return r.isEmpty() ? null : r.get(0);
    }

    private int openSuggestions(String sku) {
        return jdbc.queryForObject("select count(*) from replenishment_suggestion where sku = ? and status = 'OPEN'",
                Integer.class, sku);
    }

    private List<Map<String, Object>> outboxFor(String correlationId) {
        return jdbc.queryForList("select event_type, payload::text as payload, published_at from outbox where correlation_id = ?",
                correlationId);
    }

    @Test
    void reservationEventReachesBothConsumersAndEveryEventMatchesItsSchema() {
        seed("IT-FLOW", 25);
        String corr = "corr-flow-" + UUID.randomUUID();
        reserve("IT-FLOW", 10, corr);   // disponible 15 < punto de reorden 20

        await().atMost(TIMEOUT).until(() -> Integer.valueOf(1).equals(dashboardCount("IT-FLOW")));
        await().atMost(TIMEOUT).until(() -> openSuggestions("IT-FLOW") == 1);
        assertEquals(85, jdbc.queryForObject("select suggested_qty from replenishment_suggestion where sku = 'IT-FLOW'", Integer.class));

        // la sugerencia publica su propio evento con el mismo correlationId (traza logica de punta a punta)
        await().atMost(TIMEOUT).until(() -> outboxFor(corr).stream().allMatch(r -> r.get("published_at") != null)
                && outboxFor(corr).size() == 2);
        var events = outboxFor(corr);
        assertEquals(Set.of(RabbitConfig.STOCK_RESERVED, RabbitConfig.SUGGESTION_OPENED),
                Set.copyOf(events.stream().map(r -> (String) r.get("event_type")).toList()));
        for (var e : events) {
            assertEquals(Set.of(), EventSchemas.validate((String) e.get("payload")), "evento fuera de contrato: " + e);
        }
    }

    @Test
    void redeliveredEventIsIgnoredByBothConsumers() {
        seed("IT-DUP", 25);
        String corr = "corr-dup-" + UUID.randomUUID();
        reserve("IT-DUP", 10, corr);
        await().atMost(TIMEOUT).until(() -> Integer.valueOf(1).equals(dashboardCount("IT-DUP")) && openSuggestions("IT-DUP") == 1);

        String envelope = jdbc.queryForObject(
                "select payload::text from outbox where correlation_id = ? and event_type = ?", String.class,
                corr, RabbitConfig.STOCK_RESERVED);
        UUID eventId = UUID.fromString(envelope.replaceAll(".*\"eventId\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1"));

        // al-menos-una-vez: el broker puede entregar el mismo evento otra vez (p. ej. re-proceso desde la DLQ)
        for (int i = 0; i < 3; i++) {
            rabbitTemplate.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, RabbitConfig.STOCK_RESERVED, envelope);
        }
        await().pollDelay(Duration.ofSeconds(2)).atMost(TIMEOUT).until(() -> jdbc.queryForObject(
                "select count(*) from processed_event where event_id = ?", Integer.class, eventId) == 2);

        assertEquals(1, dashboardCount("IT-DUP"), "el dashboard conto el duplicado");
        assertEquals(1, openSuggestions("IT-DUP"), "se duplico la alerta abierta");
        assertEquals(1, jdbc.queryForObject("select count(*) from replenishment_suggestion where sku = 'IT-DUP'", Integer.class));
    }

    @Test
    void approvalPublishesSuggestionApprovedEventWithinContract() {
        seed("IT-APPR", 25);
        reserve("IT-APPR", 10, "corr-appr-" + UUID.randomUUID());
        await().atMost(TIMEOUT).until(() -> openSuggestions("IT-APPR") == 1);
        UUID id = jdbc.queryForObject("select id from replenishment_suggestion where sku = 'IT-APPR'", UUID.class);

        String corr = "corr-approve-" + UUID.randomUUID();
        var view = replenishment.approve(id, "purchasing", corr);
        assertEquals("APPROVED", view.status());

        await().atMost(TIMEOUT).until(() -> outboxFor(corr).size() == 1 && outboxFor(corr).get(0).get("published_at") != null);
        assertEquals(Set.of(), EventSchemas.validate((String) outboxFor(corr).get(0).get("payload")));
    }

    @Test
    void poisonMessageExhaustsRetriesAndLandsInDlq() {
        String marker = "poison-" + UUID.randomUUID();
        Message poison = MessageBuilder.withBody(("{not json " + marker).getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbitTemplate.send("", RabbitConfig.DASHBOARD_Q, poison);   // directo a la cola del consumidor

        // 3 intentos con backoff 1 s + 2 s y luego rechazo sin requeue -> inventory.dlx -> inventory.dlq
        Message dead = await().atMost(TIMEOUT).until(() -> rabbitTemplate.receive(RabbitConfig.DLQ, 500),
                m -> m != null && new String(m.getBody(), StandardCharsets.UTF_8).contains(marker));
        assertNotNull(dead);
        var death = dead.getMessageProperties().getXDeathHeader();
        assertTrue(death != null && !death.isEmpty(), "sin cabecera x-death");
        assertEquals(RabbitConfig.DASHBOARD_Q, death.get(0).get("queue"));
    }
}
