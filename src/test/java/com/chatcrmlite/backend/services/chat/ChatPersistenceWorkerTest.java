package com.chatcrmlite.backend.services.chat;

import com.chatcrmlite.backend.models.SessionStatus;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WebChatMessage;
import com.chatcrmlite.backend.models.WebChatSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WebChatMessageRepository;
import com.chatcrmlite.backend.repositories.WebChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatPersistenceWorkerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WebChatSessionRepository sessionRepository;

    @Mock
    private WebChatMessageRepository messageRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private StreamOperations<String, Object, Object> streamOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ChatPersistenceWorker chatPersistenceWorker;

    private UUID ownerId;
    private User owner;

    @BeforeEach
    void setUp() {
        ownerId = UUID.randomUUID();
        owner = new User();
        owner.setId(ownerId);
        lenient().when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Skips init marker records and acknowledges")
    void testInitMarker_Acknowledged() {
        RecordId recordId = RecordId.of("1700000000000-0");
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM)
                .withId(recordId)
                .ofMap(Map.of("_init", "true"));

        chatPersistenceWorker.onMessage(record);

        verify(streamOperations).acknowledge(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM,
                ChatPersistenceWorker.CHAT_PERSISTENCE_GROUP, recordId);
        verifyNoInteractions(messageRepository);
    }

    @Test
    @DisplayName("Idempotency guard: skips DB insert if idempotencyKey already exists")
    void testIdempotencyGuard_SkipsExistingMessage() {
        RecordId recordId = RecordId.of("1700000000001-0");
        String idempotencyKey = "msg-uuid-unique-123";
        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM)
                .withId(recordId)
                .ofMap(Map.of(
                        "idempotencyKey", idempotencyKey,
                        "ownerId", ownerId.toString(),
                        "sessionId", "session-abc",
                        "sender", "USER",
                        "content", "Hello"
                ));

        when(messageRepository.existsByIdempotencyKey(idempotencyKey)).thenReturn(true);

        chatPersistenceWorker.onMessage(record);

        verify(messageRepository, never()).save(any());
        verify(streamOperations).acknowledge(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM,
                ChatPersistenceWorker.CHAT_PERSISTENCE_GROUP, recordId);
        verify(stringRedisTemplate).delete("retry:chat:persistence:" + recordId.getValue());
    }

    @Test
    @DisplayName("Processes new message successfully and saves WebChatMessage with idempotency key")
    void testProcessesNewMessage_Success() {
        RecordId recordId = RecordId.of("1700000000002-0");
        String idempotencyKey = "msg-uuid-new-456";
        String sessionId = "session-test-xyz";

        MapRecord<String, String, String> record = StreamRecords.newRecord()
                .in(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM)
                .withId(recordId)
                .ofMap(Map.of(
                        "idempotencyKey", idempotencyKey,
                        "ownerId", ownerId.toString(),
                        "sessionId", sessionId,
                        "sender", "BOT",
                        "content", "This is an AI response."
                ));

        when(messageRepository.existsByIdempotencyKey(idempotencyKey)).thenReturn(false);
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));

        WebChatSession existingSession = new WebChatSession(owner, sessionId);
        existingSession.setStatus(SessionStatus.ACTIVE);
        when(sessionRepository.findByOwnerAndSessionId(owner, sessionId)).thenReturn(Optional.of(existingSession));

        chatPersistenceWorker.onMessage(record);

        ArgumentCaptor<WebChatMessage> msgCaptor = ArgumentCaptor.forClass(WebChatMessage.class);
        verify(messageRepository).save(msgCaptor.capture());
        WebChatMessage savedMsg = msgCaptor.getValue();
        assertEquals(WebChatMessage.Sender.BOT, savedMsg.getSender());
        assertEquals("This is an AI response.", savedMsg.getContent());
        assertEquals(idempotencyKey, savedMsg.getIdempotencyKey());

        verify(streamOperations).acknowledge(ChatPersistenceWorker.CHAT_PERSISTENCE_STREAM,
                ChatPersistenceWorker.CHAT_PERSISTENCE_GROUP, recordId);
        verify(stringRedisTemplate).delete("retry:chat:persistence:" + recordId.getValue());
    }
}
