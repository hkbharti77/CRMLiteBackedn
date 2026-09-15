package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.GraphEvidence;
import com.chatcrmlite.backend.dto.rag.HybridRetrievalResult;
import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import com.chatcrmlite.backend.dto.rag.RetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalSource;
import com.chatcrmlite.backend.services.HybridSearchService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HybridRetrievalServiceTest {

    @Mock
    private HybridSearchService hybridSearchService;
    @Mock
    private GraphRetrievalService graphRetrievalService;
    @Mock
    private QueryAnalyzerService queryAnalyzerService;

    @InjectMocks
    private HybridRetrievalService hybridRetrievalService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(hybridRetrievalService, "ragModeProperty", "HYBRID");
    }

    @Test
    void neo4jUnavailableDoesNotBreakVectorPath() {
        when(queryAnalyzerService.analyze(any(), eq(tenantId))).thenReturn(QueryAnalysis.builder()
                .tenantId(tenantId)
                .originalQuery("pricing")
                .requiresVector(true)
                .requiresGraph(true)
                .maxGraphDepth(2)
                .build());
        when(hybridSearchService.hybridSearchDetailed(eq(tenantId), any(), anyString(), anyInt()))
                .thenReturn(List.of(new HybridSearchService.ScoredChunk(
                        UUID.randomUUID(), UUID.randomUUID(), "Price is 100", 0.2, "doc")));
        when(graphRetrievalService.retrieve(any())).thenReturn(List.of());

        HybridRetrievalResult result = hybridRetrievalService.retrieve(
                "pricing", tenantId, new float[]{0.1f}, true, 8);

        assertFalse(result.getVectorResults().isEmpty());
        assertEquals(1, result.getVectorResults().size());
        assertEquals(RetrievalSource.VECTOR_CHUNK, result.getVectorResults().get(0).getSourceType());
    }

    @Test
    void vectorModeSkipsGraphEvenIfAnalyzerHintsGraph() {
        ReflectionTestUtils.setField(hybridRetrievalService, "ragModeProperty", "VECTOR");
        when(queryAnalyzerService.analyze(any(), eq(tenantId))).thenReturn(QueryAnalysis.builder()
                .tenantId(tenantId)
                .requiresVector(true)
                .requiresGraph(false)
                .build());
        when(hybridSearchService.hybridSearchDetailed(eq(tenantId), any(), anyString(), anyInt()))
                .thenReturn(List.of(new HybridSearchService.ScoredChunk(
                        UUID.randomUUID(), UUID.randomUUID(), "chunk", 0.1, "src")));

        HybridRetrievalResult result = hybridRetrievalService.retrieve(
                "q", tenantId, new float[]{0.1f}, true, 8);

        verify(graphRetrievalService, never()).retrieve(any());
        assertFalse(result.getVectorResults().isEmpty());
    }

    @Test
    void graphOnlyWhenModeGraph() {
        ReflectionTestUtils.setField(hybridRetrievalService, "ragModeProperty", "GRAPH");
        when(queryAnalyzerService.analyze(any(), eq(tenantId))).thenReturn(QueryAnalysis.builder()
                .tenantId(tenantId)
                .requiresVector(false)
                .requiresGraph(true)
                .build());
        when(graphRetrievalService.isAvailable()).thenReturn(true);
        when(graphRetrievalService.retrieve(any())).thenReturn(List.of(
                GraphEvidence.builder()
                        .tenantId(tenantId)
                        .entity("A")
                        .relationship("HAS_FEATURE")
                        .target("B")
                        .fact("A --HAS_FEATURE--> B")
                        .hopDepth(1)
                        .build()
        ));

        HybridRetrievalResult result = hybridRetrievalService.retrieve(
                "q", tenantId, new float[]{0.1f}, true, 8);

        verify(hybridSearchService, never()).hybridSearchDetailed(any(), any(), any(), anyInt());
        assertEquals(1, result.getGraphResults().size());
    }
}
