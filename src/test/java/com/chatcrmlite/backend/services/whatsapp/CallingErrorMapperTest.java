package com.chatcrmlite.backend.services.whatsapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CallingErrorMapperTest {

    @ParameterizedTest
    @ValueSource(ints = {
            138000, 138001, 138002, 138003, 138004, 138005, 138006, 138007,
            138008, 138009, 138010, 138011, 138012, 138013, 138014, 138015,
            138016, 138017, 138018, 138019, 138020, 138021, 138022, 138023
    })
    @DisplayName("Verify official Meta 138000-138023 calling error codes are mapped")
    void testOfficialMeta138000to138023ErrorCodes(int metaCode) {
        CallingErrorMapper.CallingErrorDetails details = CallingErrorMapper.map(metaCode);
        assertNotNull(details, "Details should never be null for code " + metaCode);
        assertNotNull(details.internalCode(), "Internal code should not be null for code " + metaCode);
        assertNotNull(details.description(), "Description should not be null for code " + metaCode);
        assertNotEquals("UNKNOWN_CALL_ERROR", details.internalCode(), "Code " + metaCode + " must have a dedicated mapping");
    }

    @Test
    @DisplayName("Verify known common Graph API and permission error codes")
    void testCommonGraphApiErrorCodes() {
        assertEquals("INVALID_REQUEST_SCHEMA", CallingErrorMapper.map(100).internalCode());
        assertEquals("CALL_PERMISSION_API_RATE_LIMIT", CallingErrorMapper.map(613).internalCode());
        assertEquals("VOICE_CALL_MESSAGE_NOT_SUPPORTED", CallingErrorMapper.map(131009).internalCode());
        assertEquals("PTN_RECIPIENT_NOT_ALLOWED", CallingErrorMapper.map(131030).internalCode());
        assertEquals("PAYMENT_ERROR", CallingErrorMapper.map(131044).internalCode());
        assertEquals("GRAPH_CALLING_FORBIDDEN_ON_SIP", CallingErrorMapper.map(131055).internalCode());
    }

    @Test
    @DisplayName("Verify retryable classification on temporary calling errors")
    void testRetryableErrorClassification() {
        assertTrue(CallingErrorMapper.map(138002).isRetryable(), "CONCURRENT_CALL_LIMIT should be retryable");
        assertTrue(CallingErrorMapper.map(138004).isRetryable(), "CONNECTION_ERROR should be retryable");
        assertTrue(CallingErrorMapper.map(138007).isRetryable(), "CONNECT_TIMEOUT should be retryable");
        assertTrue(CallingErrorMapper.map(138019).isRetryable(), "CALL_SETUP_FAILED should be retryable");
        assertTrue(CallingErrorMapper.map(138021).isRetryable(), "MEDIA_RECEIVE_TIMEOUT should be retryable");

        assertFalse(CallingErrorMapper.map(138000).isRetryable(), "CALLING_NOT_ENABLED should not be retryable");
        assertFalse(CallingErrorMapper.map(138006).isRetryable(), "CALL_PERMISSION_DENIED should not be retryable");
        assertFalse(CallingErrorMapper.map(138013).isRetryable(), "BIC_UNAVAILABLE should not be retryable");
    }

    @Test
    @DisplayName("Verify unknown Meta error code falls back to safe UNKNOWN_CALL_ERROR")
    void testUnknownMetaErrorCodeFallback() {
        CallingErrorMapper.CallingErrorDetails fallback = CallingErrorMapper.map(999999);
        assertNotNull(fallback);
        assertEquals("UNKNOWN_CALL_ERROR", fallback.internalCode());
        assertTrue(fallback.isRetryable());
        assertTrue(fallback.description().contains("999999"));
    }
}
