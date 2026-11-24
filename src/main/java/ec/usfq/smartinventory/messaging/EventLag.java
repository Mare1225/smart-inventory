package ec.usfq.smartinventory.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;

/**
 * Atraso de extremo a extremo de un evento: desde que ocurrio el cambio (occurredAt, dentro de la transaccion de
 * negocio) hasta que el consumidor lo procesa. Incluye espera en outbox + broker + cola. Responde a la pregunta
 * operativa "esta creciendo el atraso del consumidor?" (alerta EventLagHigh).
 */
final class EventLag {

    private EventLag() {
    }

    static void record(MeterRegistry metrics, String consumer, EventEnvelope e, Clock clock) {
        Duration lag = Duration.between(e.occurredAt(), clock.instant());
        Timer.builder("smartinventory.event.lag")
                .description("occurredAt -> procesado por el consumidor")
                .tag("consumer", consumer)
                .tag("eventType", e.eventType())
                .publishPercentileHistogram()
                .register(metrics)
                .record(lag.isNegative() ? Duration.ZERO : lag);
    }
}
