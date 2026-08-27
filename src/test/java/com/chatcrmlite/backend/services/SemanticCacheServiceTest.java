package com.chatcrmlite.backend.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SemanticCacheServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private SemanticCacheService semanticCacheService;

    private UUID tenantId;
    private float[] queryEmbedding;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        queryEmbedding = new float[]{1.0f, 0.0f, 0.0f};
    }

    @Test
    void testGetCachedResponse_Hit() {
        String embeddingLiteral = Arrays.toString(queryEmbedding);

        Map<String, Object> resultRow = new HashMap<>();
        resultRow.put("response_text", "Cached Answer");
        resultRow.put("id", UUID.randomUUID());

        List<Map<String, Object>> results = Collections.singletonList(resultRow);

        when(jdbcTemplate.queryForList(anyString(), 
                eq(tenantId), eq(embeddingLiteral), anyDouble(), eq(embeddingLiteral)))
                .thenReturn(results);

        String response = semanticCacheService.getCachedResponse(queryEmbedding, tenantId);

        assertNotNull(response);
        assertEquals("Cached Answer", response);
        verify(jdbcTemplate, times(1)).update(anyString(), any(UUID.class));
    }

    @Test
    void testGetCachedResponse_Miss() {
        String embeddingLiteral = Arrays.toString(queryEmbedding);

        when(jdbcTemplate.queryForList(anyString(), 
                eq(tenantId), eq(embeddingLiteral), anyDouble(), eq(embeddingLiteral)))
                .thenReturn(Collections.emptyList());

        String response = semanticCacheService.getCachedResponse(queryEmbedding, tenantId);

        assertNull(response);
    }

    @Test
    void testPutCachedResponse() {
        String query = "hello";
        String response = "Hello there!";
        String embeddingLiteral = Arrays.toString(queryEmbedding);

        semanticCacheService.putCachedResponse(query, queryEmbedding, response, tenantId);

        verify(jdbcTemplate, times(1)).update(anyString(), 
                any(UUID.class), eq(tenantId), eq(query), eq(embeddingLiteral), eq(response));
    }
}
