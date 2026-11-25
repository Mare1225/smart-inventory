package ec.usfq.smartinventory.contract;

import static com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.ValidationReport;
import ec.usfq.smartinventory.application.ApiException;
import ec.usfq.smartinventory.application.DashboardQueryService;
import ec.usfq.smartinventory.application.InventoryQueryService;
import ec.usfq.smartinventory.application.ReplenishmentService;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.config.AppConfig;
import ec.usfq.smartinventory.domain.InsufficientStockException;
import ec.usfq.smartinventory.web.ProblemWriter;
import ec.usfq.smartinventory.web.WebSliceTestConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Conformidad: peticiones y respuestas REALES de la aplicacion (controladores, filtros y manejador de errores)
 * se validan contra openapi/openapi.yaml. Si el codigo devuelve un campo, estado o media type que el contrato no
 * declara, la prueba falla (el contrato no puede cambiar en silencio).
 */
@WebMvcTest(properties = {"app.rate-limit.capacity=1000", "app.rate-limit.refill-per-second=1000"})
@Import({AppConfig.class, ProblemWriter.class, WebSliceTestConfig.class})
class ApiConformanceTest {

    static final String SPEC = "openapi/openapi.yaml";
    static final String BODY = """
            {"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-1"}""";

    @Autowired MockMvc mvc;
    @MockitoBean InventoryQueryService inventory;
    @MockitoBean ReservationService reservations;
    @MockitoBean ReplenishmentService replenishment;
    @MockitoBean DashboardQueryService dashboard;

    private static ResultMatcher conformsToContract() {
        return openApi().isValid(SPEC);
    }

    /** Para peticiones invalidas a proposito: solo la RESPUESTA de error debe cumplir el contrato. */
    private static final OpenApiInteractionValidator RESPONSE_ONLY = OpenApiInteractionValidator.createForSpecificationUrl(SPEC)
            .withLevelResolver(LevelResolver.create().withLevel("validation.request", ValidationReport.Level.IGNORE).build())
            .build();

    private static ResultMatcher responseConformsToContract() {
        return openApi().isValid(RESPONSE_ONLY);
    }

    private static ReservationService.Result reservation(boolean replay) {
        return new ReservationService.Result(UUID.randomUUID(), "SKU-1001", "BOD-UIO", 35, "PEDIDO-1", "CONFIRMED",
                List.of(new ReservationService.Line("L-B", LocalDate.of(2026, 12, 1), 35)), replay);
    }

    private static ReplenishmentService.SuggestionView suggestion(String status) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        return new ReplenishmentService.SuggestionView(UUID.randomUUID(), "SKU-2002", "PROV-SIERRA", 85, status, now,
                "APPROVED".equals(status) ? "purchasing" : null, "APPROVED".equals(status) ? now : null);
    }

    @Test
    void inventoryPage() throws Exception {
        when(inventory.search(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new InventoryQueryService.PageResponse<>(List.of(new InventoryQueryService.StockItem(
                        "SKU-1001", "BOD-UIO", "L-B", LocalDate.of(2026, 12, 1), 30, 5, 25)), 0, 20, 1));
        mvc.perform(get("/api/v1/inventory?sku=SKU-1001&sort=expiresOn&order=asc").header("X-Api-Key", "dev-ops-key")
                        .header("X-Request-Id", "req-conformance-1"))
                .andExpect(status().isOk()).andExpect(conformsToContract());
    }

    @Test
    void inventoryInvalidParameter() throws Exception {
        mvc.perform(get("/api/v1/inventory?size=500").header("X-Api-Key", "dev-ops-key"))
                .andExpect(status().isBadRequest()).andExpect(responseConformsToContract());
    }

    @Test
    void reservationCreatedAndReplayed() throws Exception {
        when(reservations.reserve(any())).thenReturn(reservation(false), reservation(true));
        for (int expected : new int[] {201, 200}) {
            mvc.perform(post("/api/v1/reservations").header("X-Api-Key", "dev-fulfillment-key")
                            .header("Idempotency-Key", "conformance-0001").contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().is(expected)).andExpect(conformsToContract());
        }
    }

    @Test
    void reservationErrors() throws Exception {
        when(reservations.reserve(any())).thenThrow(new InsufficientStockException(35, 3));
        mvc.perform(post("/api/v1/reservations").header("X-Api-Key", "dev-fulfillment-key")
                        .header("Idempotency-Key", "conformance-0002").contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict()).andExpect(conformsToContract());
    }

    @Test
    void unauthorizedAndForbidden() throws Exception {
        mvc.perform(get("/api/v1/dashboard/inventory"))
                .andExpect(status().isUnauthorized()).andExpect(responseConformsToContract());
        mvc.perform(get("/api/v1/replenishment/suggestions").header("X-Api-Key", "dev-fulfillment-key"))
                .andExpect(status().isForbidden()).andExpect(conformsToContract());
    }

    @Test
    void suggestionsListAndApprove() throws Exception {
        when(replenishment.list(any(), any(), any(), anyInt())).thenReturn(List.of(suggestion("OPEN")));
        mvc.perform(get("/api/v1/replenishment/suggestions?status=OPEN&sku=SKU-2002")
                        .header("X-Api-Key", "dev-purchasing-key"))
                .andExpect(status().isOk()).andExpect(conformsToContract());

        var approved = suggestion("APPROVED");
        when(replenishment.approve(any(), any(), any())).thenReturn(approved);
        mvc.perform(post("/api/v1/replenishment/suggestions/" + approved.id() + "/approve")
                        .header("X-Api-Key", "dev-purchasing-key"))
                .andExpect(status().isOk()).andExpect(conformsToContract());

        when(replenishment.approve(any(), any(), any()))
                .thenThrow(ApiException.conflict("INVALID_TRANSITION", "Suggestion is APPROVED"));
        mvc.perform(post("/api/v1/replenishment/suggestions/" + approved.id() + "/approve")
                        .header("X-Api-Key", "dev-purchasing-key"))
                .andExpect(status().isConflict()).andExpect(conformsToContract());
    }

    @Test
    void dashboardView() throws Exception {
        var now = Instant.now();
        when(dashboard.current()).thenReturn(new DashboardQueryService.View(now, List.of(
                new DashboardQueryService.Row("SKU-1001", 130, 2, UUID.randomUUID(), "CONFIRMED",
                        now.minusMillis(40), now, 40, false))));
        mvc.perform(get("/api/v1/dashboard/inventory").header("X-Api-Key", "dev-ops-key"))
                .andExpect(status().isOk()).andExpect(conformsToContract());
    }
}
