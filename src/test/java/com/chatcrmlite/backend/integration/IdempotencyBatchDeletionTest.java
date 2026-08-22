package com.chatcrmlite.backend.integration;

import com.chatcrmlite.backend.ChatCrmBackendApplication;
import com.chatcrmlite.backend.models.ProcessedMessage;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ProcessedMessageRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.IdempotencyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
public class IdempotencyBatchDeletionTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private ProcessedMessageRepository processedMessageRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User testOwner;

    @BeforeEach
    void setUp() {
        processedMessageRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        User owner = User.builder()
                .email("testowner@example.com")
                .password("password")
                .role(User.Role.OWNER)
                .build();
        testOwner = userRepository.saveAndFlush(owner);
    }

    @Test
    void testBatchPurge_DeletesOldRecordsInBatches() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oldDate = now.minusDays(40); // Older than the 30-day cutoff
        LocalDateTime recentDate = now.minusDays(10);

        List<Object[]> oldArgs = new ArrayList<>();
        for (int i = 0; i < 6000; i++) {
            oldArgs.add(new Object[] { "old-msg-" + i, testOwner.getId(), oldDate });
        }

        List<Object[]> recentArgs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            recentArgs.add(new Object[] { "recent-msg-" + i, testOwner.getId(), recentDate });
        }

        jdbcTemplate.batchUpdate(
            "INSERT INTO processed_messages (message_id, owner_id, processed_at) VALUES (?, ?, ?)",
            oldArgs
        );
        
        jdbcTemplate.batchUpdate(
            "INSERT INTO processed_messages (message_id, owner_id, processed_at) VALUES (?, ?, ?)",
            recentArgs
        );

        long initialCount = processedMessageRepository.count();
        assertEquals(7000, initialCount);

        // Act
        idempotencyService.purgeOldRecordsInternal();

        // Assert
        long remainingCount = processedMessageRepository.count();
        assertEquals(1000, remainingCount, "Only the 1,000 recent messages should remain");
    }
}
