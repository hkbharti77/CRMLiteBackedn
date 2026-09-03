package com.chatcrmlite.backend.websocket;

import com.chatcrmlite.backend.services.voice.ExotelStreamService;
import com.chatcrmlite.backend.services.voice.dto.AudioChunk;
import com.chatcrmlite.backend.services.voice.dto.CallState;
import com.chatcrmlite.backend.services.voice.dto.ExotelCallSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExotelMediaWebSocketHandlerTest {

    private ExotelMediaWebSocketHandler handler;

    @Mock
    private ExotelStreamService streamService;

    @Mock
    private WebSocketSession webSocketSession;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new ExotelMediaWebSocketHandler(objectMapper, streamService);
    }

    @Test
    void testHandleStartEvent() throws Exception {
        String json = "{\n" +
                "  \"event\": \"start\",\n" +
                "  \"streamId\": \"stream-123\",\n" +
                "  \"callId\": \"call-456\"\n" +
                "}";

        handler.handleTextMessage(webSocketSession, new TextMessage(json));

        ArgumentCaptor<ExotelCallSession> sessionCaptor = ArgumentCaptor.forClass(ExotelCallSession.class);
        verify(streamService, times(1)).createSession(eq("stream-123"), sessionCaptor.capture());

        ExotelCallSession capturedSession = sessionCaptor.getValue();
        assertEquals("stream-123", capturedSession.getStreamId());
        assertEquals("call-456", capturedSession.getCallId());
        assertEquals(CallState.IDLE, capturedSession.getState());
    }

    @Test
    void testHandleMediaEvent() throws Exception {
        // "A" base64 encodes to 1 byte, but let's use valid base64 for dummy audio "dummy" -> ZHVtbXk=
        String json = "{\n" +
                "  \"event\": \"media\",\n" +
                "  \"streamId\": \"stream-123\",\n" +
                "  \"media\": {\n" +
                "    \"payload\": \"ZHVtbXk=\",\n" +
                "    \"chunk\": 1\n" +
                "  }\n" +
                "}";

        handler.handleTextMessage(webSocketSession, new TextMessage(json));

        ArgumentCaptor<AudioChunk> chunkCaptor = ArgumentCaptor.forClass(AudioChunk.class);
        verify(streamService, times(1)).processInboundAudio(eq("stream-123"), chunkCaptor.capture());

        AudioChunk capturedChunk = chunkCaptor.getValue();
        assertNotNull(capturedChunk);
        assertEquals("mulaw", capturedChunk.encoding());
        assertEquals(8000, capturedChunk.sampleRate());
        assertEquals(1, capturedChunk.sequenceNumber());
        assertArrayEquals(new byte[]{'d', 'u', 'm', 'm', 'y'}, capturedChunk.data());
    }

    @Test
    void testHandleStopEvent() throws Exception {
        String json = "{\n" +
                "  \"event\": \"stop\",\n" +
                "  \"streamId\": \"stream-123\"\n" +
                "}";

        handler.handleTextMessage(webSocketSession, new TextMessage(json));

        verify(streamService, times(1)).removeSession("stream-123");
    }
}
