package ec.usfq.smartinventory.messaging;

import ec.usfq.smartinventory.config.RabbitConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publicador del Outbox: lee pendientes (FOR UPDATE SKIP LOCKED permite varias instancias), publica con
 * confirmacion del broker y recien entonces marca published_at. Entrega al-menos-una-vez: por eso los
 * consumidores son idempotentes. Si el broker cae, las filas quedan pendientes y se publican al volver.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate tx;
    private final int batchSize;
    private final int maxBatchesPerPoll;
    private final long confirmTimeoutMs;

    public OutboxPublisher(JdbcTemplate jdbc, RabbitTemplate rabbit, TransactionTemplate tx, MeterRegistry metrics,
                           @Value("${app.outbox.batch-size:200}") int batchSize,
                           @Value("${app.outbox.max-batches-per-poll:10}") int maxBatchesPerPoll,
                           @Value("${app.outbox.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.tx = tx;
        this.batchSize = batchSize;
        this.maxBatchesPerPoll = maxBatchesPerPoll;
        this.confirmTimeoutMs = confirmTimeoutMs;
        Gauge.builder("smartinventory.outbox.pending", this, OutboxPublisher::pendingCount)
                .description("Eventos en outbox sin publicar").register(metrics);
    }

    double pendingCount() {
        Long n = jdbc.queryForObject("select count(*) from outbox where published_at is null", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Publica en lotes: envia todo el lote y espera UNA confirmacion del broker (antes: una por mensaje, lo que
     * limitaba el publicador a ~70 eventos/s y bajo carga atrasaba el dashboard ~40 s; ver
     * docs/evidencias/rendimiento.md). Si la confirmacion falla, ninguna fila del lote se marca: se reintenta
     * completo en el siguiente ciclo y los consumidores descartan los duplicados por eventId.
     * Si el lote salio lleno, sigue drenando en el mismo ciclo (hasta {@code maxBatchesPerPoll}).
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-ms:500}")
    public void publishPending() {
        for (int i = 0; i < maxBatchesPerPoll; i++) {
            Integer published = tx.execute(status -> publishBatch());
            if (published == null || published < batchSize) {
                return;
            }
        }
    }

    private int publishBatch() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, routing_key, payload::text as payload, correlation_id
                from outbox where published_at is null
                order by occurred_at limit ? for update skip locked""", batchSize);
        if (rows.isEmpty()) {
            return 0;
        }
        try {
            rabbit.invoke(ops -> {
                for (Map<String, Object> row : rows) {
                    ops.convertAndSend(RabbitConfig.EVENTS_EXCHANGE, (String) row.get("routing_key"),
                            (Object) row.get("payload"), message -> {
                                var p = message.getMessageProperties();
                                p.setContentType("application/json");
                                p.setMessageId(String.valueOf(row.get("id")));
                                p.setCorrelationId((String) row.get("correlation_id"));
                                return message;
                            });
                }
                ops.waitForConfirmsOrDie(confirmTimeoutMs);
                return true;
            });
        } catch (Exception e) {
            log.warn("outbox batch of {} events not confirmed; will retry on next poll", rows.size(), e);
            return 0;
        }
        UUID[] ids = rows.stream().map(r -> (UUID) r.get("id")).toArray(UUID[]::new);
        jdbc.update(con -> {
            var ps = con.prepareStatement("update outbox set published_at = now() where id = any(?)");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        });
        return rows.size();
    }
}
