package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.whatsapp.calling.*;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.voice.VoiceTurn;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallPermission;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallPermissionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceTurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhatsAppCallingApiServiceTest {

    @Mock private WhatsAppCallingAgentService callingAgentService;
    @Mock private OutboundCallPermissionService outboundCallPermissionService;
    @Mock private OutboundSendIdempotencyService idempotencyService;
    @Mock private WhatsAppCallSessionRepository callSessionRepository;
    @Mock private WhatsAppCallPermissionRepository permissionRepository;
    @Mock private WhatsAppConfigRepository whatsappConfigRepository;
    @Mock private ContactRepository contactRepository;
    @Mock private VoiceTurnRepository voiceTurnRepository;
    @Mock private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;
    @Mock private com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository voiceSessionRepository;

    @InjectMocks
    private WhatsAppCallingApiService apiService;

    private UUID tenantA;
    private UUID tenantB;
    private WhatsAppConfig configA;
    private Contact contactA;

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        configA = new WhatsAppConfig();
        configA.setPhoneNumberId("phone_meta_123");
        configA.setWabaId("waba_meta_456");

        contactA = new Contact();
        contactA.setId(UUID.randomUUID());
        contactA.setName("Rahul Sharma");
        contactA.setWaId("919876543210");
        Tenant tA = new Tenant();
        tA.setId(tenantA);
        contactA.setTenant(tA);
    }

    // ──────────────── Outbound Call Initiation ────────────────

    @Test
    @DisplayName("Initiate Call: Valid request with toWaId succeeds and returns WhatsAppCallResponse")
    void testInitiateOutboundCall_ValidToWaId_Success() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(callingAgentService.initiateOutboundCall(eq(tenantA), eq("phone_meta_123"), eq("919876543210"), any()))
                .thenReturn("wa_call_test_001");

        WhatsAppCallSession created = WhatsAppCallSession.builder()
                .callId("wa_call_test_001")
                .state("OFFER_SENT")
                .direction("BUSINESS_INITIATED")
                .phoneNumberId("phone_meta_123")
                .toWaId("919876543210")
                .createdAt(Instant.now())
                .build();
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_test_001"))
                .thenReturn(Optional.of(created));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("+919876543210")
                .build();

        WhatsAppCallResponse resp = apiService.initiateOutboundCall(tenantA, req, null);

        assertNotNull(resp);
        assertEquals("wa_call_test_001", resp.getCallId());
        assertEquals("OFFER_SENT", resp.getStatus());
        assertEquals("BUSINESS_INITIATED", resp.getDirection());
        assertEquals("919876543210", resp.getToWaId());
    }

    @Test
    @DisplayName("Initiate Call: Valid contactId resolves contact's waId and initiates call")
    void testInitiateOutboundCall_ValidContactId_Success() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(contactRepository.findById(contactA.getId())).thenReturn(Optional.of(contactA));
        when(callingAgentService.initiateOutboundCall(eq(tenantA), eq("phone_meta_123"), eq("919876543210"), any()))
                .thenReturn("wa_call_contact_002");

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .contactId(contactA.getId())
                .build();

        WhatsAppCallResponse resp = apiService.initiateOutboundCall(tenantA, req, null);

        assertNotNull(resp);
        assertEquals("wa_call_contact_002", resp.getCallId());
        verify(contactRepository).findById(contactA.getId());
    }

    @Test
    @DisplayName("Initiate Call: Contact belonging to another tenant returns 404 Not Found")
    void testInitiateOutboundCall_CrossTenantContact_Returns404() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));

        Contact contactOtherTenant = new Contact();
        contactOtherTenant.setId(UUID.randomUUID());
        Tenant tB = new Tenant();
        tB.setId(tenantB);
        contactOtherTenant.setTenant(tB);
        when(contactRepository.findById(contactOtherTenant.getId())).thenReturn(Optional.of(contactOtherTenant));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .contactId(contactOtherTenant.getId())
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(callingAgentService);
    }

    @Test
    @DisplayName("Initiate Call: Missing both contactId and toWaId returns 400 Bad Request")
    void testInitiateOutboundCall_MissingRecipient_Returns400() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder().build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(callingAgentService);
    }

    @Test
    @DisplayName("Initiate Call: Invalid phone format returns 400 Bad Request")
    void testInitiateOutboundCall_InvalidPhoneFormat_Returns400() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("abc-invalid")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    @DisplayName("Initiate Call: Phone number ID belonging to another tenant returns 403 Forbidden")
    void testInitiateOutboundCall_CrossTenantPhoneNumberId_Returns403() {
        when(whatsappConfigRepository.findTenantIdByPhoneNumberId("phone_tenant_b"))
                .thenReturn(Optional.of(tenantB));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .phoneNumberId("phone_tenant_b")
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(callingAgentService);
    }

    @Test
    @DisplayName("Initiate Call: Outbound permission blocked maps to 403 Forbidden")
    void testInitiateOutboundCall_PermissionBlocked_Returns403() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(callingAgentService.initiateOutboundCall(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Outbound call not permitted: active permission required or rate limit reached"));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    @DisplayName("Initiate Call: Concurrency limit reached maps to 429 Too Many Requests")
    void testInitiateOutboundCall_ConcurrencyLimit_Returns429() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(callingAgentService.initiateOutboundCall(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Concurrency limit reached on phone number: 5/5"));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    @DisplayName("Initiate Call: Meta client error maps to 502 Bad Gateway")
    void testInitiateOutboundCall_MetaFailure_Returns502() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(callingAgentService.initiateOutboundCall(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Failed to initiate outbound call with Meta: Connection reset"));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, null));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    // ──────────────── Idempotency ────────────────

    @Test
    @DisplayName("Idempotency: First request claims key and marks succeeded")
    void testIdempotency_FirstRequest_AcquiredAndMarkedSucceeded() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(idempotencyService.computePayloadHash(any())).thenReturn("hash_123");
        when(idempotencyService.claimOrCheck(eq("outbound:call:" + tenantA + ":key_abc"), eq("hash_123"), any()))
                .thenReturn(OutboundSendIdempotencyService.ClaimResult.acquired("exec_001"));
        when(callingAgentService.initiateOutboundCall(any(), any(), any(), any()))
                .thenReturn("wa_call_idem_001");

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        WhatsAppCallResponse resp = apiService.initiateOutboundCall(tenantA, req, "key_abc");

        assertNotNull(resp);
        assertEquals("wa_call_idem_001", resp.getCallId());
        verify(idempotencyService).markSucceeded("outbound:call:" + tenantA + ":key_abc", "wa_call_idem_001");
    }

    @Test
    @DisplayName("Idempotency: Repeated request with same key returns existing call without re-invoking agent service")
    void testIdempotency_RepeatedRequest_ReturnsExistingCall() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(idempotencyService.computePayloadHash(any())).thenReturn("hash_123");
        when(idempotencyService.claimOrCheck(eq("outbound:call:" + tenantA + ":key_abc"), eq("hash_123"), any()))
                .thenReturn(OutboundSendIdempotencyService.ClaimResult.succeeded("wa_call_prev_999", "wa_call_prev_999"));

        WhatsAppCallSession prev = WhatsAppCallSession.builder()
                .callId("wa_call_prev_999")
                .state("OFFER_SENT")
                .direction("BUSINESS_INITIATED")
                .phoneNumberId("phone_meta_123")
                .toWaId("919876543210")
                .createdAt(Instant.now())
                .build();
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_prev_999")).thenReturn(Optional.of(prev));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        WhatsAppCallResponse resp = apiService.initiateOutboundCall(tenantA, req, "key_abc");

        assertNotNull(resp);
        assertEquals("wa_call_prev_999", resp.getCallId());
        verifyNoInteractions(callingAgentService); // Did not initiate another call!
    }

    @Test
    @DisplayName("Idempotency: Reused key with different payload returns 409 Conflict")
    void testIdempotency_DifferentPayload_Returns409Conflict() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(idempotencyService.computePayloadHash(any())).thenReturn("hash_456");
        when(idempotencyService.claimOrCheck(eq("outbound:call:" + tenantA + ":key_abc"), eq("hash_456"), any()))
                .thenReturn(OutboundSendIdempotencyService.ClaimResult.conflict("Payload mismatch"));

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, "key_abc"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(callingAgentService);
    }

    @Test
    @DisplayName("Idempotency: Concurrent duplicate request in progress returns 202 Accepted")
    void testIdempotency_ConcurrentDuplicate_Returns202Accepted() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(idempotencyService.computePayloadHash(any())).thenReturn("hash_123");
        when(idempotencyService.claimOrCheck(eq("outbound:call:" + tenantA + ":key_abc"), eq("hash_123"), any()))
                .thenReturn(OutboundSendIdempotencyService.ClaimResult.inProgress());

        InitiateWhatsAppCallRequest req = InitiateWhatsAppCallRequest.builder()
                .toWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.initiateOutboundCall(tenantA, req, "key_abc"));
        assertEquals(HttpStatus.ACCEPTED, ex.getStatusCode());
        verifyNoInteractions(callingAgentService);
    }

    // ──────────────── Call Details & Tenant Isolation ────────────────

    @Test
    @DisplayName("Get Call Details: Valid call returns detail DTO with safe metadata")
    void testGetCallDetails_ValidCall_Success() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_detail_100")
                .state("ACTIVE")
                .direction("USER_INITIATED")
                .phoneNumberId("phone_meta_123")
                .fromWaId("919876543210")
                .toWaId("phone_meta_123")
                .startedAt(Instant.now().minusSeconds(60))
                .connectedAt(Instant.now().minusSeconds(50))
                .durationSeconds(50)
                .build();
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_detail_100"))
                .thenReturn(Optional.of(session));

        WhatsAppCallDetailDTO detail = apiService.getCallDetails(tenantA, "wa_call_detail_100");

        assertNotNull(detail);
        assertEquals("wa_call_detail_100", detail.getCallId());
        assertEquals("ACTIVE", detail.getState());
        assertEquals(50, detail.getDurationSeconds());
    }

    @Test
    @DisplayName("Get Call Details: Unknown call or call of another tenant returns 404 Not Found")
    void testGetCallDetails_AnotherTenantCall_Returns404NotFound() {
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "call_belongs_to_tenant_b"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.getCallDetails(tenantA, "call_belongs_to_tenant_b"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("Call not found", ex.getReason());
    }

    // ──────────────── Call Listing & Pagination ────────────────

    @Test
    @DisplayName("List Calls: Database-level pagination delegates to repository findAll with spec")
    @SuppressWarnings("unchecked")
    void testListCalls_PaginationAndFiltering() {
        WhatsAppCallSession s1 = WhatsAppCallSession.builder()
                .callId("call_01").state("ACTIVE").direction("USER_INITIATED").createdAt(Instant.now()).build();
        WhatsAppCallSession s2 = WhatsAppCallSession.builder()
                .callId("call_02").state("ENDED").direction("BUSINESS_INITIATED").createdAt(Instant.now()).build();

        Page<WhatsAppCallSession> page = new PageImpl<>(List.of(s1, s2));
        when(callSessionRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        Page<WhatsAppCallSummaryDTO> result = apiService.listCalls(
                tenantA, 0, 20, "createdAt", "DESC", "ACTIVE", "USER_INITIATED", null, null, null, null
        );

        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals("call_01", result.getContent().get(0).getCallId());
    }

    // ──────────────── Turns & Transcripts ────────────────

    @Test
    @DisplayName("Get Turns: Call with no aiSessionId returns empty page")
    void testGetCallTurns_NoAiSessionId_ReturnsEmptyPage() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("call_no_turns")
                .state("ENDED")
                .aiSessionId(null)
                .build();
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "call_no_turns"))
                .thenReturn(Optional.of(session));

        Page<WhatsAppCallTurnDTO> turns = apiService.getCallTurns(tenantA, "call_no_turns", 0, 50);

        assertNotNull(turns);
        assertEquals(0, turns.getTotalElements());
        assertTrue(turns.getContent().isEmpty());
    }

    @Test
    @DisplayName("Get Turns: Call with aiSessionId returns paginated conversation turns")
    void testGetCallTurns_WithAiSessionId_ReturnsTurns() {
        UUID aiSessionId = UUID.randomUUID();
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("call_with_turns")
                .state("ENDED")
                .aiSessionId(aiSessionId)
                .build();
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "call_with_turns"))
                .thenReturn(Optional.of(session));

        VoiceTurn t1 = new VoiceTurn();
        t1.setId(UUID.randomUUID());
        t1.setTurnNumber(1);
        t1.setUserTranscript("Hello, I need help with my appointment.");
        t1.setCreatedAt(LocalDateTime.now().minusSeconds(30));

        VoiceTurn t2 = new VoiceTurn();
        t2.setId(UUID.randomUUID());
        t2.setTurnNumber(2);
        t2.setBotResponseText("Certainly, I can help you with that.");
        t2.setCreatedAt(LocalDateTime.now().minusSeconds(20));

        Page<VoiceTurn> turnPage = new PageImpl<>(List.of(t1, t2));
        when(voiceTurnRepository.findBySessionIdOrderByTurnNumberAsc(eq(aiSessionId), any(Pageable.class)))
                .thenReturn(turnPage);

        Page<WhatsAppCallTurnDTO> turns = apiService.getCallTurns(tenantA, "call_with_turns", 0, 50);

        assertNotNull(turns);
        assertEquals(2, turns.getTotalElements());
        assertEquals("USER", turns.getContent().get(0).getSpeaker());
        assertEquals("Hello, I need help with my appointment.", turns.getContent().get(0).getTranscript());
        assertEquals("ASSISTANT", turns.getContent().get(1).getSpeaker());
        assertEquals("Certainly, I can help you with that.", turns.getContent().get(1).getTranscript());
    }

    @Test
    @DisplayName("Get Turns: Cross-tenant call returns 404 Not Found")
    void testGetCallTurns_CrossTenant_Returns404NotFound() {
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "call_other_tenant"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.getCallTurns(tenantA, "call_other_tenant", 0, 50));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ──────────────── Analytics ────────────────

    @Test
    @DisplayName("Analytics: Aggregates metrics correctly from database aggregation queries")
    void testGetAnalytics_CorrectAggregation() {
        when(callSessionRepository.countCallsByTenant(tenantA, null, null, null)).thenReturn(10L);
        when(callSessionRepository.countCallsByDirection(tenantA, null, null, null))
                .thenReturn(List.of(new Object[]{"USER_INITIATED", 6L}, new Object[]{"BUSINESS_INITIATED", 4L}));
        when(callSessionRepository.countCallsByState(tenantA, null, null, null))
                .thenReturn(List.of(new Object[]{"ACTIVE", 2L}, new Object[]{"ENDED", 6L}, new Object[]{"FAILED", 2L}));
        when(callSessionRepository.countAnsweredCalls(tenantA, null, null, null)).thenReturn(8L);
        when(callSessionRepository.countCallsByTerminationReason(tenantA, null, null, null))
                .thenReturn(List.of(new Object[]{"USER_HANGUP", 5L}, new Object[]{"CALL_COMPLETED", 3L}));
        when(callSessionRepository.getDurationStats(tenantA, null, null, null))
                .thenReturn(Collections.singletonList(new Object[]{600L, 75.0}));

        WhatsAppCallingAnalyticsDTO analytics = apiService.getAnalytics(tenantA, null, null, null);

        assertNotNull(analytics);
        assertEquals(10, analytics.getTotalCalls());
        assertEquals(6, analytics.getInboundCalls());
        assertEquals(4, analytics.getOutboundCalls());
        assertEquals(8, analytics.getAnsweredCalls());
        assertEquals(2, analytics.getFailedCalls());
        assertEquals(6, analytics.getCompletedCalls());
        assertEquals(80.0, analytics.getAnswerRate());
        assertEquals(75.0, analytics.getAverageDurationSeconds());
        assertEquals(600, analytics.getTotalDurationSeconds());
    }

    @Test
    @DisplayName("Analytics: Empty dataset returns zeroed metrics without division by zero or errors")
    void testGetAnalytics_EmptyDataset() {
        when(callSessionRepository.countCallsByTenant(tenantA, null, null, null)).thenReturn(0L);
        when(callSessionRepository.countCallsByDirection(tenantA, null, null, null)).thenReturn(Collections.emptyList());
        when(callSessionRepository.countCallsByState(tenantA, null, null, null)).thenReturn(Collections.emptyList());
        when(callSessionRepository.countAnsweredCalls(tenantA, null, null, null)).thenReturn(0L);
        when(callSessionRepository.countCallsByTerminationReason(tenantA, null, null, null)).thenReturn(Collections.emptyList());
        when(callSessionRepository.getDurationStats(tenantA, null, null, null)).thenReturn(Collections.emptyList());

        WhatsAppCallingAnalyticsDTO analytics = apiService.getAnalytics(tenantA, null, null, null);

        assertNotNull(analytics);
        assertEquals(0, analytics.getTotalCalls());
        assertEquals(0.0, analytics.getAnswerRate());
        assertEquals(0.0, analytics.getAverageDurationSeconds());
    }

    // ──────────────── Permissions ────────────────

    @Test
    @DisplayName("Permissions: Requesting permission succeeds and returns PENDING response")
    void testRequestPermission_Success() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(outboundCallPermissionService.requestCallPermission(eq(tenantA), eq("phone_meta_123"), eq("919876543210"), any()))
                .thenReturn(true);

        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId("phone_meta_123")
                .userWaId("919876543210")
                .status("PENDING")
                .build();
        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantA, "phone_meta_123", "919876543210"))
                .thenReturn(Optional.of(perm));

        RequestCallPermissionRequest req = RequestCallPermissionRequest.builder()
                .userWaId("919876543210")
                .build();

        CallPermissionResponseDTO resp = apiService.requestPermission(tenantA, req);

        assertNotNull(resp);
        assertEquals("PENDING", resp.getStatus());
        assertEquals("919876543210", resp.getUserWaId());
    }

    @Test
    @DisplayName("Permissions: Rate limit exceeded returns 429 Too Many Requests")
    void testRequestPermission_RateLimit_Returns429() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(outboundCallPermissionService.requestCallPermission(any(), any(), any(), any()))
                .thenReturn(false);

        RequestCallPermissionRequest req = RequestCallPermissionRequest.builder()
                .userWaId("919876543210")
                .build();

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.requestPermission(tenantA, req));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
    }

    @Test
    @DisplayName("Permissions: Get permission returns status and isActive evaluation")
    void testGetPermission_Success() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));

        WhatsAppCallPermission perm = WhatsAppCallPermission.builder()
                .phoneNumberId("phone_meta_123")
                .userWaId("919876543210")
                .status("GRANTED_TEMPORARY")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantA, "phone_meta_123", "919876543210"))
                .thenReturn(Optional.of(perm));
        when(outboundCallPermissionService.isPermissionActive(tenantA, "phone_meta_123", "919876543210"))
                .thenReturn(true);
        when(outboundCallPermissionService.canInitiateOutboundCall(tenantA, "phone_meta_123", "919876543210"))
                .thenReturn(true);

        CallPermissionResponseDTO resp = apiService.getPermission(tenantA, "919876543210", null);

        assertNotNull(resp);
        assertEquals("GRANTED_TEMPORARY", resp.getStatus());
        assertTrue(resp.isActive());
        assertTrue(resp.isCanInitiateCall());
    }

    @Test
    @DisplayName("Permissions: Get non-existent permission returns 404 Not Found")
    void testGetPermission_NotFound_Returns404() {
        when(whatsappConfigRepository.findByTenantId(tenantA)).thenReturn(Optional.of(configA));
        when(permissionRepository.findByTenantIdAndPhoneNumberIdAndUserWaId(tenantA, "phone_meta_123", "919876543210"))
                .thenReturn(Optional.empty());
        when(permissionRepository.findByTenantIdAndUserWaId(tenantA, "919876543210"))
                .thenReturn(Collections.emptyList());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.getPermission(tenantA, "919876543210", null));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    // ──────────────── WhatsApp Call Recording Playback Tests ────────────────

    @Test
    @DisplayName("Recording: Get recording metadata returns AVAILABLE with signed URLs and playback tokens")
    void testGetCallRecording_Success_ReturnsAvailable() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_rec_123")
                .state("TERMINATED")
                .direction("USER_INITIATED")
                .durationSeconds(45)
                .recordingPublicId("tenants/" + tenantA + "/whatsapp_recordings/wa_call_rec_123")
                .recordingUrl("https://res.cloudinary.com/demo/video/upload/v1/rec.mp3")
                .recordingFormat("mp3")
                .recordingDurationSeconds(45)
                .recordingSizeBytes(360000L)
                .recordingStatus("AVAILABLE")
                .build();
        session.setTenantId(tenantA);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_rec_123"))
                .thenReturn(Optional.of(session));
        when(cloudinaryStorageService.isConfigured()).thenReturn(true);
        when(cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId()))
                .thenReturn("https://res.cloudinary.com/demo/video/upload/s--signed--/rec.mp3");

        WhatsAppCallRecordingDTO result = apiService.getCallRecording(tenantA, "wa_call_rec_123");

        assertNotNull(result);
        assertEquals("wa_call_rec_123", result.getCallId());
        assertTrue(result.isAvailable());
        assertEquals("AVAILABLE", result.getStatus());
        assertEquals("mp3", result.getFormat());
        assertEquals(45, result.getDurationSeconds());
        assertEquals(360000L, result.getSizeBytes());
        assertNotNull(result.getPlaybackUrl());
        assertTrue(result.getPlaybackUrl().contains("/recording/stream?token="));
        assertNotNull(result.getDownloadUrl());
        assertTrue(result.getDownloadUrl().contains("/recording/download?token="));
        assertEquals("https://res.cloudinary.com/demo/video/upload/s--signed--/rec.mp3", result.getSignedDirectUrl());
    }

    @Test
    @DisplayName("Recording: Call without recording returns NOT_AVAILABLE state gracefully")
    void testGetCallRecording_NoRecording_ReturnsNotAvailable() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_no_rec")
                .state("TERMINATED")
                .direction("USER_INITIATED")
                .durationSeconds(12)
                .recordingStatus("NOT_AVAILABLE")
                .build();
        session.setTenantId(tenantA);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_no_rec"))
                .thenReturn(Optional.of(session));

        WhatsAppCallRecordingDTO result = apiService.getCallRecording(tenantA, "wa_call_no_rec");

        assertNotNull(result);
        assertEquals("wa_call_no_rec", result.getCallId());
        assertFalse(result.isAvailable());
        assertEquals("NOT_AVAILABLE", result.getStatus());
        assertNull(result.getPlaybackUrl());
        assertNull(result.getSignedDirectUrl());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("Recording: Tenant isolation blocks access to another tenant's call recording (404)")
    void testGetCallRecording_TenantIsolation_Returns404() {
        when(callSessionRepository.findByTenantIdAndCallId(tenantB, "wa_call_rec_123"))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                apiService.getCallRecording(tenantB, "wa_call_rec_123"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    @DisplayName("Recording: Active call without recording yet returns PROCESSING status")
    void testGetCallRecording_ActiveCall_ReturnsProcessing() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_active")
                .state("ACTIVE")
                .direction("BUSINESS_INITIATED")
                .build();
        session.setTenantId(tenantA);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_active"))
                .thenReturn(Optional.of(session));

        WhatsAppCallRecordingDTO result = apiService.getCallRecording(tenantA, "wa_call_active");

        assertNotNull(result);
        assertFalse(result.isAvailable());
        assertEquals("PROCESSING", result.getStatus());
    }

    @Test
    @DisplayName("Recording: Stream full audio content returns 200 OK with audio/mpeg")
    void testStreamCallRecording_FullContent_Returns200() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_stream")
                .recordingPublicId("tenants/" + tenantA + "/whatsapp_recordings/wa_call_stream")
                .build();
        session.setTenantId(tenantA);

        byte[] fakeAudio = new byte[1024];
        Arrays.fill(fakeAudio, (byte) 0x55);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_stream"))
                .thenReturn(Optional.of(session));
        when(cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId()))
                .thenReturn("https://signed-url");
        try {
            when(cloudinaryStorageService.downloadFile("https://signed-url")).thenReturn(fakeAudio);
        } catch (Exception ignored) {}

        org.springframework.http.ResponseEntity<byte[]> response =
                apiService.streamCallRecording(tenantA, "wa_call_stream", null, null);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("audio/mpeg", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_TYPE));
        assertEquals("bytes", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.ACCEPT_RANGES));
        assertEquals("1024", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_LENGTH));
        assertEquals(1024, response.getBody().length);
    }

    @Test
    @DisplayName("Recording: Stream Range request returns 206 Partial Content for browser seeking")
    void testStreamCallRecording_RangeRequest_Returns206PartialContent() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_seek")
                .recordingPublicId("tenants/" + tenantA + "/whatsapp_recordings/wa_call_seek")
                .build();
        session.setTenantId(tenantA);

        byte[] fakeAudio = new byte[1000];
        for (int i = 0; i < 1000; i++) fakeAudio[i] = (byte) (i % 128);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_seek"))
                .thenReturn(Optional.of(session));
        when(cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId()))
                .thenReturn("https://signed-url");
        try {
            when(cloudinaryStorageService.downloadFile("https://signed-url")).thenReturn(fakeAudio);
        } catch (Exception ignored) {}

        // Request bytes 100-299 (200 bytes chunk)
        org.springframework.http.ResponseEntity<byte[]> response =
                apiService.streamCallRecording(tenantA, "wa_call_seek", null, "bytes=100-299");

        assertNotNull(response);
        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 100-299/1000", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_RANGE));
        assertEquals("200", response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_LENGTH));
        assertEquals(200, response.getBody().length);
        assertEquals(fakeAudio[100], response.getBody()[0]);
        assertEquals(fakeAudio[299], response.getBody()[199]);
    }

    @Test
    @DisplayName("Recording: Secure download returns attachment Content-Disposition")
    void testDownloadCallRecording_Success_ReturnsAttachment() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_dl")
                .recordingPublicId("tenants/" + tenantA + "/whatsapp_recordings/wa_call_dl")
                .build();
        session.setTenantId(tenantA);

        byte[] fakeAudio = new byte[512];
        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_dl"))
                .thenReturn(Optional.of(session));
        when(cloudinaryStorageService.getSignedAudioPlaybackUrl(session.getRecordingPublicId()))
                .thenReturn("https://signed-url");
        try {
            when(cloudinaryStorageService.downloadFile("https://signed-url")).thenReturn(fakeAudio);
        } catch (Exception ignored) {}

        org.springframework.http.ResponseEntity<byte[]> response =
                apiService.downloadCallRecording(tenantA, "wa_call_dl", null);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION)
                .contains("attachment; filename="));
    }

    @Test
    @DisplayName("Recording: Idempotent save or update metadata does not duplicate records")
    void testSaveOrUpdateCallRecording_Idempotent() {
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .callId("wa_call_idem")
                .state("TERMINATED")
                .durationSeconds(30)
                .build();
        session.setTenantId(tenantA);

        when(callSessionRepository.findByTenantIdAndCallId(tenantA, "wa_call_idem"))
                .thenReturn(Optional.of(session));
        when(callSessionRepository.save(any(WhatsAppCallSession.class))).thenAnswer(inv -> inv.getArgument(0));

        WhatsAppCallRecordingDTO res1 = apiService.saveOrUpdateCallRecording(
                tenantA, "wa_call_idem", "public_id_1", "https://url1", "mp3", 30, 200000L, "AVAILABLE"
        );
        assertEquals("public_id_1", res1.getPublicId());
        assertEquals("AVAILABLE", res1.getStatus());

        // Update again with new duration/url
        WhatsAppCallRecordingDTO res2 = apiService.saveOrUpdateCallRecording(
                tenantA, "wa_call_idem", "public_id_1", "https://url2", "mp3", 35, 250000L, "AVAILABLE"
        );
        assertEquals("public_id_1", res2.getPublicId());
        assertEquals(35, res2.getDurationSeconds());
        assertEquals(250000L, res2.getSizeBytes());
    }
}
