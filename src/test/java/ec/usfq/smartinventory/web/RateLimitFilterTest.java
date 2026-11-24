package ec.usfq.smartinventory.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ec.usfq.smartinventory.application.DashboardQueryService;
import ec.usfq.smartinventory.application.InventoryQueryService;
import ec.usfq.smartinventory.application.ReplenishmentService;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.config.AppConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 429 de punta a punta en el filtro: rafaga de 3, recarga despreciable, cuota independiente por cliente. */
@WebMvcTest(properties = {"app.rate-limit.capacity=3", "app.rate-limit.refill-per-second=0.001"})
@Import({AppConfig.class, ProblemWriter.class, WebLayerTest.Metrics.class})
class RateLimitFilterTest {

    @Autowired MockMvc mvc;
    @Autowired MeterRegistry metrics;
    @MockitoBean InventoryQueryService inventory;
    @MockitoBean ReservationService reservations;
    @MockitoBean ReplenishmentService replenishment;
    @MockitoBean DashboardQueryService dashboard;

    @Test
    void exceedingTheBurstReturns429WithRetryAfterAndIsCountedPerClient() throws Exception {
        when(inventory.search(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new InventoryQueryService.PageResponse<>(List.of(), 0, 20, 0));
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/v1/inventory").header("X-Api-Key", "dev-ops-key"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-RateLimit-Remaining", String.valueOf(2 - i)));
        }
        mvc.perform(get("/api/v1/inventory").header("X-Api-Key", "dev-ops-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // otro cliente conserva su propia cuota (clave de conteo = cliente autenticado, no IP)
        mvc.perform(get("/api/v1/inventory").header("X-Api-Key", "dev-fulfillment-key")).andExpect(status().isOk());

        assertEquals(1.0, metrics.counter("smartinventory.ratelimit.rejected", "client", "ops-console").count());
    }

    @Test
    void unauthenticatedRequestsAreRejectedBeforeConsumingQuota() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(get("/api/v1/inventory")).andExpect(status().isUnauthorized());
        }
    }
}
