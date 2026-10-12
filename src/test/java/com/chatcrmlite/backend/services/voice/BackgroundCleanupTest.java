package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.services.ai.DeepgramVoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.DatagramSocket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BackgroundCleanupTest {

    @Mock
    private WhatsAppVoiceCallBridgeService voiceCallBridgeService;

    @Mock
    private DeepgramVoiceService deepgramVoiceService;

    @Mock
    private WebRtcDtlsHandler dtlsHandler;

    private WhatsAppWebRtcMediaGateway mediaGateway;

    @BeforeEach
    void setUp() {
        lenient().when(dtlsHandler.getSha256Fingerprint()).thenReturn("11:22:33:44:55:66:77:88:99:00:11:22:33:44:55:66:77:88:99:00:11:22:33:44:55:66:77:88:99:00");

        mediaGateway = new WhatsAppWebRtcMediaGateway(voiceCallBridgeService, deepgramVoiceService, dtlsHandler);
        ReflectionTestUtils.setField(mediaGateway, "portRangeStart", 52000);
        ReflectionTestUtils.setField(mediaGateway, "publicMediaIp", "127.0.0.1");
        ReflectionTestUtils.setField(mediaGateway, "turnEnabled", false);
    }

    @Test
    @DisplayName("Session termination cancels all scheduled background tasks and closes sockets")
    void testSessionTerminationCancelsAllTasks() throws Exception {
        String callId = "call_cleanup_01";
        UUID tenantId = UUID.randomUUID();

        String sdpOffer = """
                v=0\r
                m=audio 40000 UDP/TLS/RTP/SAVPF 111\r
                c=IN IP4 127.0.0.1\r
                a=candidate:1 1 UDP 2130706431 127.0.0.1 40000 typ host\r
                """;

        mediaGateway.createMediaSession(callId, tenantId, "919876543210", sdpOffer);

        // Access internal session to inspect background tasks and socket
        Map<String, ?> activeSessions = (Map<String, ?>) ReflectionTestUtils.getField(mediaGateway, "activeSessions");
        assertNotNull(activeSessions);
        Object sessionObj = activeSessions.get(callId);
        assertNotNull(sessionObj);

        DatagramSocket socket = (DatagramSocket) ReflectionTestUtils.getField(sessionObj, "socket");
        assertNotNull(socket);
        assertFalse(socket.isClosed());

        // Attach dummy ScheduledFuture tasks to verify they are cancelled on termination
        ScheduledFuture<?> mockOutbound = mock(ScheduledFuture.class);
        ScheduledFuture<?> mockVad = mock(ScheduledFuture.class);
        ScheduledFuture<?> mockIce = mock(ScheduledFuture.class);
        ScheduledFuture<?> mockWatchdog = mock(ScheduledFuture.class);

        ReflectionTestUtils.setField(sessionObj, "outboundTask", mockOutbound);
        ReflectionTestUtils.setField(sessionObj, "vadTask", mockVad);
        ReflectionTestUtils.setField(sessionObj, "iceCheckTask", mockIce);
        ReflectionTestUtils.setField(sessionObj, "watchdogTask", mockWatchdog);

        // Terminate session
        mediaGateway.terminateSession(callId);

        // Verify all 4 background tasks were cancelled
        verify(mockOutbound, times(1)).cancel(true);
        verify(mockVad, times(1)).cancel(true);
        verify(mockIce, times(1)).cancel(true);
        verify(mockWatchdog, times(1)).cancel(true);

        // Verify socket is closed
        assertTrue(socket.isClosed());

        // Verify active sessions no longer contains the terminated session
        assertFalse(activeSessions.containsKey(callId));
    }

    @Test
    @DisplayName("Repeated session creation and termination releases sockets cleanly without leaks")
    void testRepeatedSessionsReleaseSockets() {
        UUID tenantId = UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            String callId = "call_repeat_cleanup_" + i;
            mediaGateway.createMediaSession(callId, tenantId, "919876543210", null);
            mediaGateway.terminateSession(callId);
        }

        Map<String, ?> activeSessions = (Map<String, ?>) ReflectionTestUtils.getField(mediaGateway, "activeSessions");
        assertNotNull(activeSessions);
        assertTrue(activeSessions.isEmpty(), "All sessions must be removed from memory");
    }
}
