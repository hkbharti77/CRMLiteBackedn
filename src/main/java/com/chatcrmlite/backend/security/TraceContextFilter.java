package com.chatcrmlite.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter to inject Trace ID and Tenant ID into MDC for structured logging.
 * This runs before AuthTokenFilter to ensure even unauthenticated requests have a traceId.
 */
@Component
@Order(1) // Run very early in the filter chain
public class TraceContextFilter extends OncePerRequestFilter {

    private static final String TRACE_ID_HEADER = "X-Trace-ID";
    private static final String CORRELATION_ID_MDC = "traceId";
    private static final String TENANT_ID_MDC = "tenantId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        MDC.put(CORRELATION_ID_MDC, traceId);
        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            // Tenant ID will be populated in MDC after AuthTokenFilter runs if authenticated
            // For now we just run the chain
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
