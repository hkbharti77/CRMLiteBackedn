package com.chatcrmlite.backend.websocket;

import com.chatcrmlite.backend.services.voice.ExotelStreamService;
import com.chatcrmlite.backend.services.voice.dto.AudioChunk;
import com.chatcrmlite.backend.services.voice.dto.ExotelAudioCodec;
import com.chatcrmlite.backend.services.voice.dto.ExotelCallSession;
import com.chatcrmlite.backend.services.voice.dto.ExotelStreamMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;
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
    // Map wsSessionId → streamId so we can clean up on disconnect
    private final ConcurrentHashMap<String, String> sessionToStreamId = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Exotel AgentStream WebSocket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            String rawPayload = message.getPayload();

            // Parse as generic JSON first so we can extract event without strict binding
            JsonNode root = objectMapper.readTree(rawPayload);
            String event = root.has("event") ? root.get("event").asText() : null;

            if (event == null) {
                log.warn("Received Exotel message without event field. Raw: {}", rawPayload);
                return;
            }

            // Log start/stop events fully for debugging, skip media (too verbose)
            if (!"media".equals(event)) {
                log.info("[ExotelWS] Event={} Raw={}", event, rawPayload);
            }

            ExotelStreamMessage exotelMsg = objectMapper.treeToValue(root, ExotelStreamMessage.class);

            switch (event.toLowerCase()) {
                case "connected":
                    // Exotel sends 'connected' first as a handshake — just acknowledge, no session yet
                    log.info("[ExotelWS] Exotel handshake 'connected' received for ws={}", session.getId());
                    break;
                case "connect":
                case "start":
                    handleStart(session, exotelMsg, root);
                    break;
                case "media":
                    handleMedia(session, exotelMsg);
                    break;
                case "stop":
                    handleStop(session, exotelMsg);
                    break;
                default:
                    log.debug("Ignored Exotel event: {}", event);
            }
        } catch (Exception e) {
            log.error("Error processing WebSocket message: {}", message.getPayload(), e);
        }
    }

    private void handleStart(WebSocketSession wsSession, ExotelStreamMessage msg, JsonNode root) {
        // ── Extract streamId ─────────────────────────────────────────────────
        // Exotel sends: top-level stream_sid AND nested start.stream_sid
        String streamId = firstNonNull(
                msg.getStreamId(),                  // covers @JsonAlias stream_sid
                jsonText(root, "stream_sid"),
                jsonText(root, "streamSid")
        );

        // ── Extract from nested 'start' object ───────────────────────────────
        String callId = null;
        String callerPhone = null;
        String callerTo = null;

        JsonNode startNode = root.has("start") ? root.get("start") : null;
        if (startNode != null) {
            if (streamId == null) {
                streamId = firstNonNull(
                        jsonText(startNode, "stream_sid"),
                        jsonText(startNode, "streamSid")
                );
            }
            callId = firstNonNull(
                    jsonText(startNode, "call_sid"),
                    jsonText(startNode, "callSid")
            );
            callerPhone = jsonText(startNode, "from");  // caller's phone number
            callerTo    = jsonText(startNode, "to");    // your Exotel virtual number
        }

        // Fallback callId from top-level if not in nested object
        if (callId == null) {
            callId = firstNonNull(msg.getCallId(), jsonText(root, "call_sid"));
        }

        // ── Ultimate fallback: wsSession.getId() ─────────────────────────────
        if (streamId == null) {
            streamId = wsSession.getId();
            log.warn("[ExotelWS] streamId not found — using wsSessionId={} as fallback", streamId);
        }

        log.info("[ExotelWS] ✅ Session starting | streamId={} | callId={} | from={} | to={}",
                streamId, callId, callerPhone, callerTo);

        ExotelCallSession callSession = new ExotelCallSession(callId, streamId, callerPhone, "en", wsSession);
        streamService.createSession(streamId, callSession);
        // Always update the wsSession→streamId map (overwrites ghost entry from 'connected' if any)
        sessionToStreamId.put(wsSession.getId(), streamId);

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
        // Use the wsSessionId→streamId map to clean up even if stop event wasn't received
        String streamId = sessionToStreamId.remove(session.getId());
        if (streamId != null) {
            streamService.removeSession(streamId);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String jsonText(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        String val = node.get(field).asText(null);
        return (val == null || val.isBlank() || "null".equals(val)) ? null : val;
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
                        
                        // Debug log the first chunk sent to see the exact JSON structure
                        if (callSession.getTtsFirstAudioMs() == 0) {
                            log.info("[ExotelWS] Sending first outbound chunk: {}", json.substring(0, Math.min(json.length(), 200)) + "...");
                            callSession.setTtsFirstAudioMs(1);
                        }
                        
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
