package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.exceptions.QuotaExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharedHostRateLimiterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SharedHostRateLimiter rateLimiter;

    @Test
    @DisplayName("Should successfully reserve immediate slot when host has no backlog")
    void testReserveSlot_Immediate() {
        Instant allocated = Instant.now().plusMillis(500);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), eq("example.com")))
                .thenReturn(Timestamp.from(allocated));

        SharedHostRateLimiter.SlotReservation slot = rateLimiter.reserveSlot("example.com");

        assertNotNull(slot);
        assertEquals("example.com", slot.hostName());
        assertFalse(slot.shouldDefer());
        assertFalse(slot.backpressureExceeded());
    }

    @Test
    @DisplayName("Should flag shouldDefer when wait time exceeds 30 seconds")
    void testReserveSlot_ShouldDefer() {
        // Allocated 35 seconds in the future
        Instant allocated = Instant.now().plusSeconds(35);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), eq("busy-site.com")))
                .thenReturn(Timestamp.from(allocated));

        SharedHostRateLimiter.SlotReservation slot = rateLimiter.reserveSlot("busy-site.com");

        assertTrue(slot.shouldDefer());
        assertFalse(slot.backpressureExceeded());
        assertTrue(slot.waitMs() > 30_000);
    }

    @Test
    @DisplayName("Should flag backpressureExceeded when backlog exceeds 15 minutes")
    void testReserveSlot_Backpressure() {
        // Allocated 16 minutes in the future
        Instant allocated = Instant.now().plusSeconds(16 * 60);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), eq("overwhelmed.org")))
                .thenReturn(Timestamp.from(allocated));

        SharedHostRateLimiter.SlotReservation slot = rateLimiter.reserveSlot("overwhelmed.org");

        assertTrue(slot.shouldDefer());
        assertTrue(slot.backpressureExceeded());
        assertTrue(slot.waitMs() > 15 * 60 * 1000);
    }

    @Test
    @DisplayName("paceOutboundRequest should throw QuotaExceededException when domain backpressure exceeded")
    void testPaceOutboundRequest_BackpressureException() {
        Instant allocated = Instant.now().plusSeconds(20 * 60);
        when(jdbcTemplate.queryForObject(anyString(), eq(Timestamp.class), eq("overloaded.com")))
                .thenReturn(Timestamp.from(allocated));

        assertThrows(QuotaExceededException.class, () ->
                rateLimiter.paceOutboundRequest("overloaded.com"));
    }
}
