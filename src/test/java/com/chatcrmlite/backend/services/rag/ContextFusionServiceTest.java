package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.FusedContext;
import com.chatcrmlite.backend.dto.rag.GraphEvidence;
import com.chatcrmlite.backend.dto.rag.HybridRetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalResult;
import com.chatcrmlite.backend.dto.rag.RetrievalSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContextFusionServiceTest {

    private ContextFusionService fusion;

    @BeforeEach
    void setUp() {
        fusion = new ContextFusionService();
        ReflectionTestUtils.setField(fusion, "vectorWeight", 0.6);
        ReflectionTestUtils.setField(fusion, "graphWeight", 0.4);
    }

    @Test
    void deduplicatesIdenticalEvidence() {
        UUID tenant = UUID.randomUUID();
        HybridRetrievalResult retrieval = HybridRetrievalResult.builder()
                .vectorResults(List.of(
                        RetrievalResult.builder()
                                .tenantId(tenant)
                                .content("Same fact")
                                .score(0.5)
                                .sourceType(RetrievalSource.VECTOR_CHUNK)
                                .sourceId("1")
                                .build(),
                        RetrievalResult.builder()
                                .tenantId(tenant)
                                .content("Same fact")
                                .score(0.4)
                                .sourceType(RetrievalSource.VECTOR_CHUNK)
                                .sourceId("2")
                                .build()
                ))
                .graphResults(List.of())
                .build();

        FusedContext fused = fusion.fuse(retrieval);
        assertEquals(1, fused.getVectorContextLines().size());
    }

    @Test
    void keepsGraphRelationshipsStructured() {
        UUID tenant = UUID.randomUUID();
        HybridRetrievalResult retrieval = HybridRetrievalResult.builder()
                .vectorResults(List.of())
                .graphResults(List.of(
                        GraphEvidence.builder()
                                .tenantId(tenant)
                                .entity("Service A")
                                .entityId("a")
                                .relationship("HAS_FEATURE")
                                .target("Bluetooth")
                                .targetId("b")
                                .fact("Service A --HAS_FEATURE--> Bluetooth")
                                .sourceType("FAQ")
                                .sourceId("faq-1")
                                .confidence(0.9)
                                .hopDepth(1)
                                .build()
                ))
                .build();

        FusedContext fused = fusion.fuse(retrieval);
        assertEquals(1, fused.getGraphContextLines().size());
        assertTrue(fused.getGraphContextLines().get(0).contains("HAS_FEATURE"));
        assertFalse(fused.getSources().isEmpty());
    }

    @Test
    void vectorOnlyWhenNoGraph() {
        UUID tenant = UUID.randomUUID();
        HybridRetrievalResult retrieval = HybridRetrievalResult.builder()
                .vectorResults(List.of(
                        RetrievalResult.builder()
                                .tenantId(tenant)
                                .content("Doc evidence")
                                .score(0.3)
                                .sourceType(RetrievalSource.VECTOR_CHUNK)
                                .sourceId("c1")
                                .build()
                ))
                .graphResults(List.of())
                .build();

        FusedContext fused = fusion.fuse(retrieval);
        assertEquals(1, fused.getVectorContextLines().size());
        assertTrue(fused.getGraphContextLines().isEmpty());
    }
}
