package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.dto.rag.QueryAnalysis;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.memory.RagRouterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryAnalyzerServiceTest {

    @Mock
    private RagRouterService ragRouterService;
    @Mock
    private BusinessServiceRepository businessServiceRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private QueryAnalyzerService analyzer;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        ReflectionTestUtils.setField(analyzer, "ragModeProperty", "HYBRID");
        ReflectionTestUtils.setField(analyzer, "defaultMaxGraphDepth", 2);
        when(ragRouterService.requiresRag(any())).thenReturn(true);
        when(userRepository.findById(tenantId)).thenReturn(Optional.empty());
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
    void tenantIdNeverComesFromQueryText() {
        QueryAnalysis analysis = analyzer.analyze("tenantId=evil-uuid show pricing", tenantId);
        assertEquals(tenantId, analysis.getTenantId());
    }
}
