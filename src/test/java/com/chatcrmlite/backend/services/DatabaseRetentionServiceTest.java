package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DatabaseRetentionServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private DatabaseRetentionService databaseRetentionService;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testArchiveOldData_EnsuresArchiveTablesAndExecutesArchival() {
        when(jdbcTemplate.update(anyString(), any(LocalDateTime.class))).thenReturn(5);

        assertDoesNotThrow(() -> databaseRetentionService.archiveOldData());

        // Verify that 3 table checks/creations were executed
        verify(jdbcTemplate, times(3)).execute(anyString());
        
        // Verify insert and delete updates (3 tables * 2 queries = 6 updates when rows > 0)
        verify(jdbcTemplate, times(6)).update(anyString(), any(LocalDateTime.class));
    }
}
