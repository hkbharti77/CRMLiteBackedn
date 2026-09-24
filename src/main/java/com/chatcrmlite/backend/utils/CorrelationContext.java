package com.chatcrmlite.backend.utils;

import org.slf4j.MDC;
import java.util.UUID;

/**
 * Utility for managing end-to-end correlation IDs across thread boundaries,
 * async message queues, and MDC logging contexts.
 */
public class CorrelationContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    public static final String MDC_KEY = "correlation_id";

    private static final ThreadLocal<String> CORRELATION_ID = new ThreadLocal<>();

    public static String getCorrelationId() {
        String id = CORRELATION_ID.get();
        if (id == null) {
            id = MDC.get(MDC_KEY);
        }
        if (id == null) {
            id = generateNew();
            setCorrelationId(id);
        }
        return id;
    }

    public static void setCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.trim().isEmpty()) {
            correlationId = generateNew();
        }
        CORRELATION_ID.set(correlationId);
        MDC.put(MDC_KEY, correlationId);
    }

    public static String generateNew() {
        return "corr_" + UUID.randomUUID().toString().replace("-", "");
    }

    public static void clear() {
        CORRELATION_ID.remove();
        MDC.remove(MDC_KEY);
    }
}
