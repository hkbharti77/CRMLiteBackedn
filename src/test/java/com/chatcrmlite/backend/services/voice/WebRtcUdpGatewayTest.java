package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebRtcUdpGatewayTest {

    @Mock
    private WhatsAppVoiceCallBridgeService voiceCallBridgeService;

    @Mock
    private DeepgramVoiceService deepgramVoiceService;

    @Mock
    private WebRtcDtlsHandler dtlsHandler;

    private WhatsAppWebRtcMediaGateway mediaGateway;

    private static final String FINGERPRINT = "AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00:AA:BB:CC:DD:EE:FF:11:22:33:44:55:66:77:88:99:00";

    @BeforeEach
    void setUp() {
        lenient().when(dtlsHandler.getSha256Fingerprint()).thenReturn(FINGERPRINT);

        mediaGateway = new WhatsAppWebRtcMediaGateway(voiceCallBridgeService, deepgramVoiceService, dtlsHandler);
        ReflectionTestUtils.setField(mediaGateway, "portRangeStart", 51000);
        ReflectionTestUtils.setField(mediaGateway, "publicMediaIp", "127.0.0.1");
        ReflectionTestUtils.setField(mediaGateway, "turnEnabled", false);
        ReflectionTestUtils.setField(mediaGateway, "dtlsTimeoutMs", 5000);
        ReflectionTestUtils.setField(mediaGateway, "mediaStartTimeoutMs", 5000);
    }

    @AfterEach
    void tearDown() {
        // cleanup any lingering sessions
    }

    @Test
    @DisplayName("UDP socket is allocated dynamically within port range and released on call termination")
    void testDynamicPortAllocationAndCleanup() {
        String callId = "call_port_test_01";
        UUID tenantId = UUID.randomUUID();
        String validSdpOffer = """
                v=0\r
                o=- 123456 2 IN IP4 127.0.0.1\r
                s=-\r
                t=0 0\r
                m=audio 40000 UDP/TLS/RTP/SAVPF 111\r
                c=IN IP4 192.168.1.100\r
                a=rtpmap:111 opus/48000/2\r
                a=mid:audio\r
                a=candidate:1 1 UDP 2130706431 192.168.1.100 40000 typ host\r
                """;

        String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, "919876543210", validSdpOffer);

        assertNotNull(sdpAnswer);
        assertTrue(sdpAnswer.contains("m=audio "));
        assertTrue(sdpAnswer.contains("a=mid:audio"));
        assertTrue(sdpAnswer.contains("a=setup:active"));
        assertTrue(sdpAnswer.contains("a=fingerprint:SHA-256 " + FINGERPRINT));

        // Terminate call session
        mediaGateway.terminateSession(callId);

        // Verify bridge termination invoked
        verify(voiceCallBridgeService, times(1)).terminateSession(callId);
    }

    @Test
    @DisplayName("Port collision is handled by finding next available port")
    void testPortCollisionResolution() throws Exception {
        // Intentionally bind to port 51000 to trigger a collision
        DatagramSocket blockerSocket = null;
        try {
            blockerSocket = new DatagramSocket(51000);
            assertTrue(blockerSocket.isBound());

            String callId = "call_collision_test_01";
            UUID tenantId = UUID.randomUUID();
            String sdpOffer = """
                    v=0\r
                    m=audio 40000 UDP/TLS/RTP/SAVPF 111\r
                    c=IN IP4 10.0.0.1\r
                    a=candidate:1 1 UDP 2130706431 10.0.0.1 40000 typ host\r
                    """;

            String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, "919876543210", sdpOffer);

            assertNotNull(sdpAnswer);
            // Must have bound to port > 51000
            assertFalse(sdpAnswer.contains("m=audio 51000 UDP/TLS/RTP/SAVPF"));

            mediaGateway.terminateSession(callId);
        } finally {
            if (blockerSocket != null && !blockerSocket.isClosed()) {
                blockerSocket.close();
            }
        }
    }

    @Test
    @DisplayName("SDP parser handles missing candidate gracefully using c-line and m-line fallback")
    void testSdpMissingCandidateFallback() {
        String callId = "call_sdp_fallback_01";
        UUID tenantId = UUID.randomUUID();
        // SDP offer without an explicit a=candidate line, but with c= and m= lines
        String sdpOffer = """
                v=0\r
                o=- 123456 2 IN IP4 127.0.0.1\r
                s=-\r
                t=0 0\r
                c=IN IP4 127.0.0.1\r
                m=audio 34567 UDP/TLS/RTP/SAVPF 111\r
                a=rtpmap:111 opus/48000/2\r
                """;

        String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, "919876543210", sdpOffer);
        assertNotNull(sdpAnswer);
        assertTrue(sdpAnswer.contains("a=setup:active"));

        mediaGateway.terminateSession(callId);
    }

    @Test
    @DisplayName("SDP parser handles null or completely empty offer without throwing unhandled exceptions")
    void testNullOrEmptySdpOffer() {
        String callId = "call_empty_sdp_01";
        UUID tenantId = UUID.randomUUID();

        assertDoesNotThrow(() -> {
            String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, "919876543210", null);
            assertNotNull(sdpAnswer);
            assertTrue(sdpAnswer.contains("a=setup:active"));
        });

        mediaGateway.terminateSession(callId);
    }

    @Test
    @DisplayName("Idempotent session termination does not error when called multiple times")
    void testIdempotentSessionTermination() {
        String callId = "call_idempotent_term_01";
        UUID tenantId = UUID.randomUUID();

        mediaGateway.createMediaSession(callId, tenantId, "919876543210", null);

        assertDoesNotThrow(() -> {
            mediaGateway.terminateSession(callId);
            mediaGateway.terminateSession(callId);
            mediaGateway.terminateSession(callId);
        });
    }

    @Test
    @DisplayName("Unsupported codec in offer falls back to standard Opus PT 111")
    void testUnsupportedCodecHandling() {
        String callId = "call_unsupported_codec_01";
        UUID tenantId = UUID.randomUUID();
        // Offer with only PCMU / G711 (no opus line)
        String sdpOffer = """
                v=0\r
                m=audio 40000 UDP/TLS/RTP/SAVPF 0\r
                c=IN IP4 10.0.0.1\r
                a=rtpmap:0 PCMU/8000\r
                """;

        String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, "919876543210", sdpOffer);
        assertNotNull(sdpAnswer);
        // Gateway defaults opus PT to 111
        assertTrue(sdpAnswer.contains("opus/48000/2"));

        mediaGateway.terminateSession(callId);
    }

    @Test
    @DisplayName("Media state transition and failure diagnostics recording on terminate")
    void testMediaStateDiagnosticsOnTermination() {
        String callId = "call_diag_test_01";
        UUID tenantId = UUID.randomUUID();

        mediaGateway.createMediaSession(callId, tenantId, "919876543210", null);
        mediaGateway.terminateSession(callId);

        // Verify lastMediaStates preserved for diagnosis
        var states = (java.util.Map<?, ?>) ReflectionTestUtils.getField(mediaGateway, "lastMediaStates");
        assertNotNull(states);
        assertTrue(states.containsKey(callId));
    }
}
