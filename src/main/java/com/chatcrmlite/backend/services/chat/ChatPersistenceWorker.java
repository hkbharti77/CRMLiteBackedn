package com.chatcrmlite.backend.services.chat;

import com.chatcrmlite.backend.models.SessionStatus;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WebChatMessageRepository;
import com.chatcrmlite.backend.repositories.WebChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatPersistenceWorker implements StreamListener<String, MapRecord<String, String, String>> {

    public static final String CHAT_PERSISTENCE_STREAM = "stream:chat:persistence";
    public static final String CHAT_PERSISTENCE_GROUP = "cg:chat:persistence:workers";
    public static final String CHAT_PERSISTENCE_DLQ = "stream:chat:persistence:dlq";
    private static final int MAX_RETRIES = 5;

    private final UserRepository userRepository;
    private final WebChatSessionRepository sessionRepository;
    private final WebChatMessageRepository messageRepository;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public void onMessage(MapRecord<String, String, String> record) {
        RecordId recordId = record.getId();
        Map<String, String> value = record.getValue();

        // 1. Skip stream initialization markers
        if (value.containsKey("_init")) {
            acknowledge(recordId);
            return;
        }

        String idempotencyKey = value.get("idempotencyKey");
        String retryCountKey = "retry:chat:persistence:" + recordId.getValue();

        try {
            // 2. Idempotency Guard: skip if already inserted in PostgreSQL
            if (idempotencyKey != null && messageRepository.existsByIdempotencyKey(idempotencyKey)) {
                log.debug("⏩ [ChatPersistenceWorker] Message already committed for idempotencyKey={}, skipping", idempotencyKey);
                acknowledge(recordId);
                stringRedisTemplate.delete(retryCountKey);
                return;
            }

            String ownerIdStr = value.get("ownerId");
            String sessionId = value.get("sessionId");
            String senderStr = value.get("sender");
            String content = value.get("content");

            if (ownerIdStr == null || sessionId == null || content == null) {
                log.warn("⚠️ [ChatPersistenceWorker] Malformed chat persistence payload: {}", value);
                acknowledge(recordId);
                return;
            }

            UUID ownerId = UUID.fromString(ownerIdStr);
            User owner = userRepository.findById(ownerId).orElse(null);
            if (owner == null) {
                log.warn("⚠️ [ChatPersistenceWorker] Owner user {} not found in database", ownerId);
                acknowledge(recordId);
                return;
            }

            WebChatMessage.Sender sender = WebChatMessage.Sender.valueOf(senderStr);

            // 3. Update or create WebChatSession
            WebChatSession session = sessionRepository.findByOwnerAndSessionId(owner, sessionId)
                    .orElseGet(() -> sessionRepository.save(new WebChatSession(owner, sessionId)));

            if (session.getStatus() == SessionStatus.CLOSED || session.getStatus() == SessionStatus.PENDING_TIMEOUT) {
                session.setStatus(SessionStatus.ACTIVE);
                session.setTimeoutStartedAt(null);
                session.setCloseReason(null);
            }
            session.setLastActivityAt(LocalDateTime.now());
            sessionRepository.save(session);

            // 4. Save message with idempotency key
            WebChatMessage message = new WebChatMessage(session, sender, content, idempotencyKey);
            messageRepository.save(message);

            // 5. Acknowledge record on successful DB commit
            acknowledge(recordId);
            stringRedisTemplate.delete(retryCountKey);
            log.debug("💾 [ChatPersistenceWorker] Persisted {} message for session={} (idempotencyKey={})",
                    sender, sessionId, idempotencyKey);

        } catch (Exception ex) {
            log.error("❌ [ChatPersistenceWorker] Error persisting message (recordId={}): {}", recordId, ex.getMessage(), ex);
            handleFailure(record, retryCountKey, ex);
        }
    }

    private void acknowledge(RecordId recordId) {
        try {
            stringRedisTemplate.opsForStream().acknowledge(CHAT_PERSISTENCE_STREAM, CHAT_PERSISTENCE_GROUP, recordId);
        } catch (Exception e) {
            log.warn("⚠️ [ChatPersistenceWorker] Failed to acknowledge record {}: {}", recordId, e.getMessage());
        }
    }

    private void handleFailure(MapRecord<String, String, String> record, String retryCountKey, Exception ex) {
        try {
            Long retries = stringRedisTemplate.opsForValue().increment(retryCountKey);
            if (retries != null && retries >= MAX_RETRIES) {
                log.error("🚨 [ChatPersistenceWorker] Moving record {} to DLQ {} after {} failed attempts",
                        record.getId(), CHAT_PERSISTENCE_DLQ, retries);
                Map<String, String> dlqPayload = new java.util.HashMap<>(record.getValue());
                dlqPayload.put("error", ex.getMessage() != null ? ex.getMessage() : "Unknown error");
                dlqPayload.put("failedAt", LocalDateTime.now().toString());
                stringRedisTemplate.opsForStream().add(CHAT_PERSISTENCE_DLQ, dlqPayload);
                acknowledge(record.getId());
                stringRedisTemplate.delete(retryCountKey);
            }
        } catch (Exception dlqEx) {
            log.error("❌ [ChatPersistenceWorker] Failed to route to DLQ: {}", dlqEx.getMessage());
        }
    }
}
