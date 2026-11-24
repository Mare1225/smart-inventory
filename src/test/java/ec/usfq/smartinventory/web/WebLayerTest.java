package ec.usfq.smartinventory.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ec.usfq.smartinventory.application.ApiException;
import ec.usfq.smartinventory.application.DashboardQueryService;
import ec.usfq.smartinventory.application.InventoryQueryService;
import ec.usfq.smartinventory.application.ReplenishmentService;
import ec.usfq.smartinventory.application.ReservationService;
import ec.usfq.smartinventory.config.AppConfig;
import ec.usfq.smartinventory.domain.InsufficientStockException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Capa web sin BD ni broker: filtros (correlacion, cabeceras, autenticacion/scope, rate limit), validacion y el
 * formato problem+json que ve quien integra. Los casos de uso son dobles (Mockito).
 */
@WebMvcTest(properties = {"app.rate-limit.capacity=1000", "app.rate-limit.refill-per-second=1000"})
@Import({AppConfig.class, ProblemWriter.class, WebSliceTestConfig.class})
class WebLayerTest {

    static final String OPS = "dev-ops-key";
    static final String FULFILLMENT = "dev-fulfillment-key";
    static final String PURCHASING = "dev-purchasing-key";
    static final String PROBLEM = "application/problem+json";
    static final String BODY = """
            {"sku":"SKU-1001","location":"BOD-UIO","quantity":35,"destination":"PEDIDO-1"}""";

    @Autowired MockMvc mvc;
    @MockitoBean InventoryQueryService inventory;
    @MockitoBean ReservationService reservations;
    @MockitoBean ReplenishmentService replenishment;
    @MockitoBean DashboardQueryService dashboard;

    private static MockHttpServletRequestBuilder reserve(String key, String idempotencyKey, String body) {
        var b = post("/api/v1/reservations").contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            b.header("X-Api-Key", key);
        }
        if (idempotencyKey != null) {
            b.header("Idempotency-Key", idempotencyKey);
        }
        return b;
    }

    private static ReservationService.Result result(boolean replay) {
        return new ReservationService.Result(UUID.fromString("11111111-1111-1111-1111-111111111111"), "SKU-1001",
                "BOD-UIO", 35, "PEDIDO-1", "CONFIRMED",
                List.of(new ReservationService.Line("L-B", LocalDate.of(2026, 12, 1), 30),
                        new ReservationService.Line("L-A", LocalDate.of(2026, 12, 15), 5)), replay);
    }

    // ---- autenticacion y autorizacion (NFR seguridad, SAT-2) ----

    @Test
    void missingApiKeyIs401ProblemJsonWithChallenge() throws Exception {
        mvc.perform(get("/api/v1/inventory"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(header().string("WWW-Authenticate", "ApiKey realm=\"smart-inventory\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unknownApiKeyIs401() throws Exception {
        mvc.perform(get("/api/v1/inventory").header("X-Api-Key", "not-a-key")).andExpect(status().isUnauthorized());
    }

    @Test
    void verticalPrivilegeEscalationIsBlocked() throws Exception {
        // ops-console puede leer pero no reservar; compras no puede leer inventario; fulfillment no aprueba
        mvc.perform(reserve(OPS, "key-0000001", BODY)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Missing scope inventory:reserve"));
        mvc.perform(get("/api/v1/inventory").header("X-Api-Key", PURCHASING)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/replenishment/suggestions/" + UUID.randomUUID() + "/approve")
                .header("X-Api-Key", FULFILLMENT)).andExpect(status().isForbidden());
        verify(reservations, never()).reserve(any());
    }

    @Test
    void scopeTableCoversEveryEndpoint() {
        assertEquals("inventory:read", ApiKeyAuthFilter.requiredScope("GET", "/api/v1/inventory"));
        assertEquals("inventory:read", ApiKeyAuthFilter.requiredScope("GET", "/api/v1/dashboard/inventory"));
        assertEquals("inventory:reserve", ApiKeyAuthFilter.requiredScope("POST", "/api/v1/reservations"));
        assertEquals("replenishment:read", ApiKeyAuthFilter.requiredScope("GET", "/api/v1/replenishment/suggestions"));
        assertEquals("replenishment:approve",
                ApiKeyAuthFilter.requiredScope("POST", "/api/v1/replenishment/suggestions/x/approve"));
    }

    // ---- reserva: idempotencia y errores de negocio (US-02) ----

    @Test
    void firstReservationIs201AndReplayIs200WithHeader() throws Exception {
        when(reservations.reserve(any())).thenReturn(result(false), result(true));
        mvc.perform(reserve(FULFILLMENT, "key-0000001", BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].lotCode").value("L-B"))
                .andExpect(header().doesNotExist("Idempotent-Replay"));
        mvc.perform(reserve(FULFILLMENT, "key-0000001", BODY))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.reservationId").value("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void reservationPassesAuthenticatedClientAndCorrelationToUseCase() throws Exception {
        when(reservations.reserve(any())).thenReturn(result(false));
        mvc.perform(reserve(FULFILLMENT, "key-0000001", BODY).header("X-Request-Id", "req-abcdef12"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", "req-abcdef12"));
        verify(reservations).reserve(new ReservationService.Command("fulfillment", "key-0000001", "SKU-1001",
                "BOD-UIO", 35, "PEDIDO-1", "req-abcdef12"));
    }

    @Test
    void insufficientStockIs409() throws Exception {
        when(reservations.reserve(any())).thenThrow(new InsufficientStockException(99999, 165));
        mvc.perform(reserve(FULFILLMENT, "key-0000002", BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.detail").value("Requested 99999, assignable 165"));
    }

    @Test
    void reusedIdempotencyKeyWithOtherBodyIs422() throws Exception {
        when(reservations.reserve(any())).thenThrow(new ApiException(422, "IDEMPOTENCY_KEY_REUSED", "reused"));
        mvc.perform(reserve(FULFILLMENT, "key-0000003", BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void unknownSkuIs404() throws Exception {
        when(reservations.reserve(any())).thenThrow(ApiException.notFound("No stock registered"));
        mvc.perform(reserve(FULFILLMENT, "key-0000004", BODY)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void invalidBodyIs422WithFieldErrors() throws Exception {
        mvc.perform(reserve(FULFILLMENT, "key-0000005", """
                        {"sku":"SKU 1001!","location":"BOD-UIO","quantity":0,"destination":""}"""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("quantity")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("sku")))
                .andExpect(jsonPath("$.errors[*].field").value(hasItem("destination")));
        verify(reservations, never()).reserve(any());
    }

    @Test
    void quantityAboveMaximumIs422() throws Exception {
        mvc.perform(reserve(FULFILLMENT, "key-0000006", """
                        {"sku":"SKU-1001","location":"BOD-UIO","quantity":100001,"destination":"X"}"""))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingIdempotencyKeyIs400NamingTheHeader() throws Exception {
        mvc.perform(reserve(FULFILLMENT, null, BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void tooShortIdempotencyKeyIs400WithoutLeakingJavaNames() throws Exception {
        mvc.perform(reserve(FULFILLMENT, "abc", BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("idempotencyKey"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        mvc.perform(reserve(FULFILLMENT, "key-0000007", "{\"sku\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // ---- errores del framework: antes respondian 500 (DEF-02) ----

    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        mvc.perform(get("/api/v1/reservations").header("X-Api-Key", FULFILLMENT))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownRouteIs404() throws Exception {
        mvc.perform(get("/api/v1/nothing-here").header("X-Api-Key", OPS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void xmlBodyIs415() throws Exception {
        mvc.perform(post("/api/v1/reservations").header("X-Api-Key", FULFILLMENT)
                        .header("Idempotency-Key", "key-0000008")
                        .contentType(MediaType.APPLICATION_XML).content("<a/>"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---- consulta (US-01) ----

    @Test
    void inventoryQueryDelegatesFiltersAndPaging() throws Exception {
        when(inventory.search(eq("SKU-1001"), isNull(), isNull(), eq(1), eq(5), eq("expiresOn"), eq(true)))
                .thenReturn(new InventoryQueryService.PageResponse<>(List.of(), 1, 5, 0));
        mvc.perform(get("/api/v1/inventory?sku=SKU-1001&page=1&size=5&order=desc").header("X-Api-Key", OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void injectionAttemptInFilterIs400() throws Exception {
        mvc.perform(get("/api/v1/inventory").param("sku", "x' or 1=1--").header("X-Api-Key", OPS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("sku"));
        verify(inventory, never()).search(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean());
    }

    @Test
    void pageSizeAbove100Is400() throws Exception {
        mvc.perform(get("/api/v1/inventory?size=500").header("X-Api-Key", OPS))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ---- reposicion (US-03) ----

    @Test
    void approvingTwiceIs409InvalidTransition() throws Exception {
        UUID id = UUID.randomUUID();
        when(replenishment.approve(eq(id), eq("purchasing"), any()))
                .thenThrow(ApiException.conflict("INVALID_TRANSITION", "Suggestion is APPROVED"));
        mvc.perform(post("/api/v1/replenishment/suggestions/" + id + "/approve").header("X-Api-Key", PURCHASING))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void invalidSuggestionIdIs400() throws Exception {
        mvc.perform(post("/api/v1/replenishment/suggestions/not-a-uuid/approve").header("X-Api-Key", PURCHASING))
                .andExpect(status().isBadRequest());
    }

    // ---- cabeceras y correlacion ----

    @Test
    void apiResponsesCarrySecurityHeaders() throws Exception {
        when(inventory.search(any(), any(), any(), anyInt(), anyInt(), any(), anyBoolean()))
                .thenReturn(new InventoryQueryService.PageResponse<>(List.of(), 0, 20, 0));
        mvc.perform(get("/api/v1/inventory").header("X-Api-Key", OPS))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", SecurityHeadersFilter.API_CSP))
                .andExpect(header().string("X-RateLimit-Limit", "1000"));
    }

    @Test
    void unsafeRequestIdIsReplacedToPreventLogInjection() throws Exception {
        mvc.perform(get("/api/v1/inventory").header("X-Request-Id", "evil-id%0aINFO forged"))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
    }
}
