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

    @Test
    void needsStructuredSeedFallbackForAggregateOrMatchedColumns() {
        assertTrue(GraphRetrievalService.needsStructuredSeedFallback(QueryAnalysis.builder()
                .intent("AGGREGATE_LOOKUP")
                .entities(List.of())
                .matchedColumns(List.of())
                .build()));
        assertTrue(GraphRetrievalService.needsStructuredSeedFallback(QueryAnalysis.builder()
                .intent("STRUCTURED_FACTUAL")
                .build()));
        assertTrue(GraphRetrievalService.needsStructuredSeedFallback(QueryAnalysis.builder()
                .intent("GENERAL_FACTUAL")
                .matchedColumns(List.of("Users"))
                .build()));
        assertFalse(GraphRetrievalService.needsStructuredSeedFallback(QueryAnalysis.builder()
                .intent("GENERAL_FACTUAL")
                .matchedColumns(List.of())
                .entities(List.of())
                .build()));
    }

    @Test
    void aggregateWithoutEntitiesStillSafeWhenNeo4jMissing() {
        GraphRetrievalService service = new GraphRetrievalService(null);
        assertTrue(service.retrieve(QueryAnalysis.builder()
                .tenantId(UUID.randomUUID())
                .requiresGraph(true)
                .intent("AGGREGATE_LOOKUP")
                .matchedColumns(List.of("Users"))
                .entities(List.of())
                .originalQuery("How many products have more than 20 users?")
                .build()).isEmpty());
    }
}
