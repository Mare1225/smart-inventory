package ec.usfq.smartinventory.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Cabeceras de seguridad en todas las respuestas (hallazgos tipicos de ZAP baseline: 10021, 10020, 10038, 10049).
 * La API solo devuelve JSON, por eso su CSP no permite cargar nada; Swagger UI necesita scripts propios y recibe
 * una CSP menos estricta.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'";
    static final String UI_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.setHeader("X-Frame-Options", "DENY");
        resp.setHeader("Referrer-Policy", "no-referrer");
        resp.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        boolean api = req.getRequestURI().startsWith("/api/");
        resp.setHeader("Content-Security-Policy", api ? API_CSP : UI_CSP);
        if (api) {
            resp.setHeader("Cache-Control", "no-store");   // stock y reservas no deben quedar en caches intermedias
        }
        chain.doFilter(req, resp);
    }
}
