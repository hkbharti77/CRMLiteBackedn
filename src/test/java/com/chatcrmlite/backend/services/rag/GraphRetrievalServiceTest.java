package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphRetrievalServiceTest {

    @Test
    void unavailableWithoutDriverReturnsEmpty() {
        GraphRetrievalService service = new GraphRetrievalService(null);
        assertFalse(service.isAvailable());
        List<?> results = service.retrieve(QueryAnalysis.builder()
                .tenantId(UUID.randomUUID())
                .requiresGraph(true)
                .entities(List.of("Bluetooth"))
                .build());
        assertTrue(results.isEmpty());
    }

    @Test
    void skipsWhenRequiresGraphFalse() {
        GraphRetrievalService service = new GraphRetrievalService(null);
        assertTrue(service.retrieve(QueryAnalysis.builder()
                .tenantId(UUID.randomUUID())
                .requiresGraph(false)
                .build()).isEmpty());
    }
}
