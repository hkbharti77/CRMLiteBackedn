package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import com.chatcrmlite.backend.services.memory.RagRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryAnalyzerServiceTest {

    @Mock
    private RagRouterService ragRouterService;
    @Mock
    private TenantKnowledgeCatalogService knowledgeCatalogService;

    @InjectMocks
    private QueryAnalyzerService analyzer;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(analyzer, "ragModeProperty", "HYBRID");
        ReflectionTestUtils.setField(analyzer, "defaultMaxGraphDepth", 2);
        when(ragRouterService.requiresRag(any())).thenReturn(true);
        when(knowledgeCatalogService.load(any())).thenReturn(
                TenantKnowledgeCatalogService.CatalogSnapshot.empty());
        when(knowledgeCatalogService.matchCatalog(any(), any())).thenReturn(
                TenantKnowledgeCatalogService.CatalogMatches.empty());
    }

    @Test
    void detectsAggregateIntentAndGraphNeed() {
        QueryAnalysis analysis = analyzer.analyze("How many products have more than 20 users?", tenantId);
        assertEquals("AGGREGATE_LOOKUP", analysis.getIntent());
        assertTrue(analysis.isRequiresGraph());
        assertTrue(analysis.isRequiresVector());
    }

    @Test
    void matchesEntitiesFromTenantCatalogNotHardcodedLists() {
        when(knowledgeCatalogService.load(tenantId)).thenReturn(
                new TenantKnowledgeCatalogService.CatalogSnapshot(
                        List.of("Brand", "Category", "Users"),
                        List.of("AI CRM", "Samsung"),
                        true));
        when(knowledgeCatalogService.matchCatalog(any(), any())).thenReturn(
                new TenantKnowledgeCatalogService.CatalogMatches(
                        List.of("Category"),
                        List.of("AI CRM")));

        QueryAnalysis analysis = analyzer.analyze("How many products are in the \"AI CRM\" category?", tenantId);
        assertTrue(analysis.getEntities().stream().anyMatch(e -> e.equalsIgnoreCase("AI CRM")));
        assertTrue(analysis.getMatchedColumns().stream().anyMatch(c -> c.equalsIgnoreCase("Category")));
        assertFalse(analysis.getEntities().stream().anyMatch(e -> e.equalsIgnoreCase("Category")));
        assertTrue(analysis.isRequiresGraph());
    }

    @Test
    void columnHitsGoToMatchedColumnsNotEntities() {
        when(knowledgeCatalogService.load(tenantId)).thenReturn(
                new TenantKnowledgeCatalogService.CatalogSnapshot(
                        List.of("Users", "Price_INR"),
                        List.of(),
                        true));
        when(knowledgeCatalogService.matchCatalog(any(), any())).thenReturn(
                new TenantKnowledgeCatalogService.CatalogMatches(List.of("Users"), List.of()));

        QueryAnalysis analysis = analyzer.analyze("How many have more than 20 users?", tenantId);
        assertTrue(analysis.getMatchedColumns().contains("Users"));
        assertTrue(analysis.getEntities().isEmpty());
        assertEquals("AGGREGATE_LOOKUP", analysis.getIntent());
        assertTrue(analysis.isRequiresGraph());
    }

    @Test
    void detectsCompatibilityIntentAndGraphNeed() {
        QueryAnalysis analysis = analyzer.analyze("Which services are compatible with Bluetooth?", tenantId);
        assertEquals("SERVICE_COMPATIBILITY", analysis.getIntent());
        assertTrue(analysis.isRequiresGraph());
        assertTrue(analysis.isRequiresVector());
        assertEquals(tenantId, analysis.getTenantId());
        assertTrue(analysis.getRelationshipHints().contains("RELATED_TO")
                || analysis.getRelationshipHints().contains("HAS_FEATURE"));
    }

    @Test
    void vectorModeDisablesGraph() {
        ReflectionTestUtils.setField(analyzer, "ragModeProperty", "VECTOR");
        QueryAnalysis analysis = analyzer.analyze("Which services support Bluetooth?", tenantId);
        assertTrue(analysis.isRequiresVector());
        assertFalse(analysis.isRequiresGraph());
    }

    @Test
    void structuredCatalogEnablesGraphForHybridWithoutDomainKeywords() {
        when(knowledgeCatalogService.load(tenantId)).thenReturn(
                new TenantKnowledgeCatalogService.CatalogSnapshot(
                        List.of("SKU", "Warehouse", "Qty"),
                        List.of(),
                        true));

        QueryAnalysis analysis = analyzer.analyze("What is the qty for SKU A-100?", tenantId);
        assertTrue(analysis.isRequiresGraph());
        assertTrue(analysis.isRequiresVector());
    }

    @Test
    void tenantIdNeverComesFromQueryText() {
        QueryAnalysis analysis = analyzer.analyze("tenantId=evil-uuid show pricing", tenantId);
        assertEquals(tenantId, analysis.getTenantId());
    }
}
