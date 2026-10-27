package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import com.chatcrmlite.backend.repositories.FaqVectorMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaqMatchingServiceTest {

    @Mock
    private FaqItemRepository faqItemRepository;

    @InjectMocks
    private FaqMatchingService faqMatchingService;

    private UUID tenantId;
    private UUID otherTenantId;
    private float[] queryEmbedding;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        otherTenantId = UUID.randomUUID();
        queryEmbedding = new float[]{0.1f, 0.2f, 0.3f};
        ReflectionTestUtils.setField(faqMatchingService, "matchingThreshold", 0.75f);
    }

    @Test
    @DisplayName("Level 1: In-Memory Exact Match should succeed with normalized punctuation and case")
    void testExactNormalizedMatch_Success() {
        FaqItem item = new FaqItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(tenantId);
        item.setQuestion("What is the return policy?");
        item.setAnswer("30-day money back guarantee");

        when(faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId))
                .thenReturn(List.of(item));

        // Test with different casing and punctuation
        String userQuery = "  what IS the return POLICY   ";
        FaqMatchingService.MatchResult result = faqMatchingService.findExactNormalizedMatch(tenantId, userQuery);

        assertNotNull(result);
        assertTrue(result.isHighConfidence());
        assertEquals(1.0f, result.getScore(), 0.0001f);
        assertEquals("30-day money back guarantee", result.getFaqItem().getAnswer());
        assertEquals("EXACT", result.getMatchType());

        // Second call should hit in-memory cache without hitting DB again!
        FaqMatchingService.MatchResult secondResult = faqMatchingService.findExactNormalizedMatch(tenantId, "What is the return policy?");
        assertTrue(secondResult.isHighConfidence());
        verify(faqItemRepository, times(1)).findByTenantIdAndIsActiveTrue(tenantId);
    }

    @Test
    @DisplayName("Level 1 Guard: Compound / multi-intent query must NOT trigger single FAQ fast path")
    void testCompoundQueryGuard_BypassesFastPath() {
        String compoundQuery = "Does Gyan VaniAi work with international clients, and what are the pricing plans?";
        FaqMatchingService.MatchResult result = faqMatchingService.findExactNormalizedMatch(tenantId, compoundQuery);

        assertNotNull(result);
        assertFalse(result.isHighConfidence());
        assertEquals("COMPOUND_BYPASS", result.getMatchType());
        verifyNoInteractions(faqItemRepository);
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant A FAQ should never be returned for Tenant B")
    void testTenantIsolation() {
        FaqItem tenantAItem = new FaqItem();
        tenantAItem.setId(UUID.randomUUID());
        tenantAItem.setTenantId(tenantId);
        tenantAItem.setQuestion("What are your business hours?");
        tenantAItem.setAnswer("9 AM - 6 PM");

        when(faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId))
                .thenReturn(List.of(tenantAItem));
        when(faqItemRepository.findByTenantIdAndIsActiveTrue(otherTenantId))
                .thenReturn(List.of());

        // Tenant A matches
        FaqMatchingService.MatchResult resultA = faqMatchingService.findExactNormalizedMatch(tenantId, "What are your business hours?");
        assertTrue(resultA.isHighConfidence());

        // Tenant B with same question must NOT match Tenant A's FAQ
        FaqMatchingService.MatchResult resultB = faqMatchingService.findExactNormalizedMatch(otherTenantId, "What are your business hours?");
        assertFalse(resultB.isHighConfidence());
        assertNull(resultB.getFaqItem());
    }

    @Test
    @DisplayName("Cache Invalidation: Updates should invalidate cache and reload fresh DB data")
    void testCacheInvalidation() {
        FaqItem item = new FaqItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(tenantId);
        item.setQuestion("Original Question");
        item.setAnswer("Original Answer");
        FaqItem updatedItem = new FaqItem();
        updatedItem.setId(item.getId());
        updatedItem.setTenantId(tenantId);
        updatedItem.setQuestion("Original Question");
        updatedItem.setAnswer("Updated Answer");

        when(faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId))
                .thenReturn(List.of(item), List.of(updatedItem));

        // Initial load
        FaqMatchingService.MatchResult r1 = faqMatchingService.findExactNormalizedMatch(tenantId, "Original Question");
        assertTrue(r1.isHighConfidence());
        assertEquals("Original Answer", r1.getFaqItem().getAnswer());

        // Invalidate cache
        faqMatchingService.invalidateTenantCache(tenantId);

        // Next fetch should load the updated answer
        FaqMatchingService.MatchResult r2 = faqMatchingService.findExactNormalizedMatch(tenantId, "Original Question");
        assertTrue(r2.isHighConfidence());
        assertEquals("Updated Answer", r2.getFaqItem().getAnswer());
        verify(faqItemRepository, times(2)).findByTenantIdAndIsActiveTrue(tenantId);
    }

    @Test
    @DisplayName("Level 2: Semantic pgvector match should succeed when exact match misses")
    void testSemanticPgVectorMatch_AboveThreshold() {
        String query = "How do refunds work?";
        UUID faqId = UUID.randomUUID();

        FaqItem item = new FaqItem();
        item.setId(faqId);
        item.setTenantId(tenantId);
        item.setQuestion("What is your refund process?");
        item.setAnswer("You can request a refund within 30 days.");

        when(faqItemRepository.findByTenantIdAndIsActiveTrue(tenantId))
                .thenReturn(List.of(item));

        FaqVectorMatch match = mock(FaqVectorMatch.class);
        when(match.getId()).thenReturn(faqId);
        when(match.getDistance()).thenReturn(0.08); // similarity = 0.92 >= 0.75

        when(faqItemRepository.findNearestByEmbedding(eq(tenantId), anyString()))
                .thenReturn(Optional.of(match));
        when(faqItemRepository.findById(faqId))
                .thenReturn(Optional.of(item));

        // Exact match fails, semantic succeeds
        FaqMatchingService.MatchResult result = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);

        assertNotNull(result);
        assertTrue(result.isHighConfidence());
        assertEquals("SEMANTIC", result.getMatchType());
        assertEquals(0.92f, result.getScore(), 0.01f);
        assertEquals(faqId, result.getFaqItem().getId());
    }

    @Test
    @DisplayName("Should return no match for empty or null query")
    void testEmptyQuery() {
        FaqMatchingService.MatchResult result1 = faqMatchingService.findBestMatch(tenantId, "", queryEmbedding);
        assertFalse(result1.isHighConfidence());
        assertNull(result1.getFaqItem());

        FaqMatchingService.MatchResult result2 = faqMatchingService.findBestMatch(tenantId, null, queryEmbedding);
        assertFalse(result2.isHighConfidence());
        assertNull(result2.getFaqItem());
    }
}
