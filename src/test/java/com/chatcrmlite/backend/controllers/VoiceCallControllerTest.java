package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.voice.VoiceTestCallRequestDTO;
import com.chatcrmlite.backend.dto.whatsapp.calling.*;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppCallingApiService;
import com.chatcrmlite.backend.utils.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VoiceCallControllerTest {

    @Mock
    private WhatsAppCallingApiService callingApiService;

    @Mock
    private TenantResolver tenantResolver;

    @InjectMocks
    private VoiceCallController voiceCallController;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        when(tenantResolver.resolveAuthenticatedTenantId()).thenReturn(tenantId);
    }

    @Test
    @DisplayName("listCalls should return paginated list of call sessions for authenticated tenant")
    void testListCallsSuccess() {
        WhatsAppCallSummaryDTO summary = WhatsAppCallSummaryDTO.builder()
                .id(UUID.randomUUID())
                .callId("wa-call-12345")
                .fromWaId("+919876543210")
                .toWaId("+919123456789")
                .direction("USER_INITIATED")
                .state("COMPLETED")
                .durationSeconds(120)
                .build();

        Page<WhatsAppCallSummaryDTO> page = new PageImpl<>(List.of(summary));
        when(callingApiService.listCalls(eq(tenantId), eq(0), eq(20), anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(page);

        ResponseEntity<Page<WhatsAppCallSummaryDTO>> response = voiceCallController.listCalls(
                0, 20, "createdAt", "DESC", null, null, null, null, null, null
        );

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getTotalElements());
        assertEquals("wa-call-12345", response.getBody().getContent().get(0).getCallId());
        verify(callingApiService, times(1)).listCalls(eq(tenantId), eq(0), eq(20), anyString(), anyString(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("getCallDetails should return call session details")
    void testGetCallDetailsSuccess() {
        String callId = "wa-call-99999";
        WhatsAppCallDetailDTO detail = WhatsAppCallDetailDTO.builder()
                .id(UUID.randomUUID())
                .callId(callId)
                .state("COMPLETED")
                .durationSeconds(85)
                .build();

        when(callingApiService.getCallDetails(eq(tenantId), eq(callId))).thenReturn(detail);

        ResponseEntity<WhatsAppCallDetailDTO> response = voiceCallController.getCallDetails(callId);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(callId, response.getBody().getCallId());
        verify(callingApiService).getCallDetails(eq(tenantId), eq(callId));
    }

    @Test
    @DisplayName("getCallTurns should return chronological turn transcript with latencies")
    void testGetCallTurnsSuccess() {
        String callId = "wa-call-turns-001";
        WhatsAppCallTurnDTO turn1 = WhatsAppCallTurnDTO.builder()
                .turnId(UUID.randomUUID())
                .turnNumber(1)
                .speaker("USER")
                .transcript("Hello, I need assistance")
                .sttLatencyMs(250)
                .llmLatencyMs(350)
                .ttsLatencyMs(120)
                .ttfaMs(470)
                .wasInterrupted(false)
                .build();

        Page<WhatsAppCallTurnDTO> turnsPage = new PageImpl<>(List.of(turn1));
        when(callingApiService.getCallTurns(eq(tenantId), eq(callId), eq(0), eq(50)))
                .thenReturn(turnsPage);

        ResponseEntity<Page<WhatsAppCallTurnDTO>> response = voiceCallController.getCallTurns(callId, 0, 50);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().getContent().size());
        assertEquals(470, response.getBody().getContent().get(0).getTtfaMs());
        verify(callingApiService).getCallTurns(eq(tenantId), eq(callId), eq(0), eq(50));
    }

    @Test
    @DisplayName("getCallingAnalytics should return aggregated Voice AI metrics")
    void testGetCallingAnalyticsSuccess() {
        WhatsAppCallingAnalyticsDTO analytics = WhatsAppCallingAnalyticsDTO.builder()
                .totalCalls(25)
                .inboundCalls(15)
                .outboundCalls(10)
                .answeredCalls(22)
                .totalDurationSeconds(3600)
                .averageDurationSeconds(144.0)
                .build();

        when(callingApiService.getAnalytics(eq(tenantId), isNull(), isNull(), isNull()))
                .thenReturn(analytics);

        ResponseEntity<WhatsAppCallingAnalyticsDTO> response = voiceCallController.getCallingAnalytics(null, null, null);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(25, response.getBody().getTotalCalls());
        assertEquals(3600, response.getBody().getTotalDurationSeconds());
        verify(callingApiService).getAnalytics(eq(tenantId), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("initiateTestCall should initiate outbound call and return 201 Created")
    void testInitiateTestCallSuccess() {
        VoiceTestCallRequestDTO request = VoiceTestCallRequestDTO.builder()
                .toPhone("+919876543210")
                .phoneNumberId("phone-id-123")
                .build();

        WhatsAppCallResponse callResponse = WhatsAppCallResponse.builder()
                .callId("wa-test-call-101")
                .status("INITIATED")
                .build();

        when(callingApiService.initiateOutboundCall(eq(tenantId), any(InitiateWhatsAppCallRequest.class), isNull()))
                .thenReturn(callResponse);

        ResponseEntity<WhatsAppCallResponse> response = voiceCallController.initiateTestCall(request);

        assertNotNull(response);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("wa-test-call-101", response.getBody().getCallId());
        verify(callingApiService).initiateOutboundCall(eq(tenantId), any(InitiateWhatsAppCallRequest.class), isNull());
    }

    @Test
    @DisplayName("Cross-tenant isolation: querying unowned call returns 404 Not Found")
    void testCrossTenantIsolationReturns404() {
        String unownedCallId = "call-belonging-to-other-tenant";
        when(callingApiService.getCallDetails(eq(tenantId), eq(unownedCallId)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Call session not found"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                voiceCallController.getCallDetails(unownedCallId)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(callingApiService).getCallDetails(eq(tenantId), eq(unownedCallId));
    }
}
