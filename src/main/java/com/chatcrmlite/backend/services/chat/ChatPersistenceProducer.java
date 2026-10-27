package com.chatcrmlite.backend.services.chat;

import com.chatcrmlite.backend.dto.chat.ChatPersistenceEvent;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceProducer {

    public static final String CHAT_PERSISTENCE_STREAM = "stream:chat:persistence";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Non-blocking durable enqueue of a chat message event to Redis Stream.
     * Takes < 1ms, decoupling HTTP response from PostgreSQL roundtrips.
     */
    public void enqueueMessage(UUID ownerId, String sessionId, WebChatMessage.Sender sender,
                               String content, String source, String idempotencyKey) {
        if (ownerId == null || sessionId == null || content == null || content.isBlank()) {
            return;
        }

        try {
            String eventId = UUID.randomUUID().toString();
            String safeIdempotencyKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                    ? idempotencyKey
                    : "msg_" + sessionId + "_" + sender.name() + "_" + System.currentTimeMillis();

            ChatPersistenceEvent event = ChatPersistenceEvent.builder()
                    .eventId(eventId)
                    .ownerId(ownerId)
                    .sessionId(sessionId)
                    .sender(sender.name())
                    .content(content)
                    .source(source != null ? source : "CHAT")
                    .idempotencyKey(safeIdempotencyKey)
                    .timestamp(System.currentTimeMillis())
                    .build();

            Map<String, String> payload = new HashMap<>();
            payload.put("eventId", eventId);
            payload.put("ownerId", ownerId.toString());
            payload.put("sessionId", sessionId);
            payload.put("sender", sender.name());
            payload.put("content", content);
            payload.put("source", event.getSource());
            payload.put("idempotencyKey", safeIdempotencyKey);
            payload.put("timestamp", String.valueOf(event.getTimestamp()));

            RecordId recordId = stringRedisTemplate.opsForStream().add(CHAT_PERSISTENCE_STREAM, payload);
            log.debug("📬 [ChatPersistenceProducer] Enqueued message to {} (recordId={}, idempotencyKey={})",
                    CHAT_PERSISTENCE_STREAM, recordId, safeIdempotencyKey);
        } catch (Exception ex) {
            log.error("❌ [ChatPersistenceProducer] Failed to enqueue message to Redis stream: {}", ex.getMessage(), ex);
        }
    }
}
