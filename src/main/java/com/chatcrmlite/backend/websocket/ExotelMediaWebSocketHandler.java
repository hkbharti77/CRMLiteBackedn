package com.chatcrmlite.backend.websocket;

import com.chatcrmlite.backend.services.voice.ExotelStreamService;
import com.chatcrmlite.backend.services.voice.dto.AudioChunk;
import com.chatcrmlite.backend.services.voice.dto.ExotelAudioCodec;
import com.chatcrmlite.backend.services.voice.dto.ExotelCallSession;
import com.chatcrmlite.backend.services.voice.dto.ExotelStreamMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExotelMediaWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ExotelStreamService streamService;
    private final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(10);

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Exotel AgentStream WebSocket connected: {}", session.getId());
        // Extract parameters from URL if needed to identify business or tenant
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            ExotelStreamMessage exotelMsg = objectMapper.readValue(message.getPayload(), ExotelStreamMessage.class);

            if (exotelMsg.getEvent() == null) {
                log.warn("Received malformed Exotel message without event type.");
                return;
            }

            switch (exotelMsg.getEvent().toLowerCase()) {
                case "connect":
                case "start":
                    handleStart(session, exotelMsg);
                    break;
                case "media":
                    handleMedia(session, exotelMsg);
                    break;
                case "stop":
                    handleStop(session, exotelMsg);
                    break;
                default:
                    log.debug("Ignored Exotel event: {}", exotelMsg.getEvent());
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message", e);
        }
    }

    private void handleStart(WebSocketSession wsSession, ExotelStreamMessage msg) {
        String streamId = msg.getStreamId();
        String callId = msg.getCallId();
        log.info("AgentStream started for CallId: {}, StreamId: {}", callId, streamId);
        
        // Ensure streamId is present as Exotel sends it
        if (streamId == null && msg.getMetadata() != null) {
            streamId = (String) msg.getMetadata().get("streamId");
        }

        ExotelCallSession callSession = new ExotelCallSession(callId, streamId, null, "en", wsSession);
        streamService.createSession(streamId, callSession);
        
        // Start a dedicated thread to pump outbound queue to Exotel
        startOutboundPump(callSession);
    }

    private void handleMedia(WebSocketSession wsSession, ExotelStreamMessage msg) {
        String streamId = msg.getStreamId();
        if (msg.getMedia() != null && msg.getMedia().getPayload() != null) {
            AudioChunk chunk = ExotelAudioCodec.decodeBase64ToChunk(
                msg.getMedia().getPayload(), 
                msg.getMedia().getChunk()
            );
            if (chunk != null) {
                streamService.processInboundAudio(streamId, chunk);
            }
        }
    }

    private void handleStop(WebSocketSession wsSession, ExotelStreamMessage msg) {
        String streamId = msg.getStreamId();
        log.info("AgentStream stop received for stream: {}", streamId);
        streamService.removeSession(streamId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("AgentStream WebSocket closed: {} with status {}", session.getId(), status);
        // Find session by websocket session ID if streamId isn't available
        // For robustness, cleanup is handled in streamService
    }

    private void startOutboundPump(ExotelCallSession callSession) {
        executorService.submit(() -> {
            try {
                while (callSession.getState() != com.chatcrmlite.backend.services.voice.dto.CallState.CLOSED && callSession.getWebSocketSession().isOpen()) {
                    AudioChunk chunk = callSession.getOutboundAudioQueue().poll(50, TimeUnit.MILLISECONDS);
                    if (chunk != null) {
                        String base64Payload = ExotelAudioCodec.encodeChunkToBase64(chunk);
                        
                        ExotelStreamMessage outMsg = new ExotelStreamMessage();
                        outMsg.setEvent("media");
                        outMsg.setStreamId(callSession.getStreamId());
                        
                        ExotelStreamMessage.Media media = new ExotelStreamMessage.Media();
                        media.setPayload(base64Payload);
                        outMsg.setMedia(media);
                        
                        String json = objectMapper.writeValueAsString(outMsg);
                        callSession.getWebSocketSession().sendMessage(new TextMessage(json));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error pumping outbound audio to Exotel", e);
            }
        });
    }
}
