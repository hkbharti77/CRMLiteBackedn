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
    private float[] queryEmbedding;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        queryEmbedding = new float[]{0.1f, 0.2f, 0.3f};
        ReflectionTestUtils.setField(faqMatchingService, "matchingThreshold", 0.85f);
    }

    @Test
    @DisplayName("Should return 1.0 confidence match on exact question hit")
    void testExactMatch_Success() {
        String query = "What is the return policy?";
        FaqItem item = new FaqItem();
        item.setId(UUID.randomUUID());
        item.setTenantId(tenantId);
        item.setQuestion("What is the return policy?");
        item.setAnswer("30-day money back guarantee");

        when(faqItemRepository.findFirstByTenantAndExactQuestion(tenantId, query))
                .thenReturn(Optional.of(item));

        FaqMatchingService.MatchResult result = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);

        assertNotNull(result);
        assertTrue(result.isHighConfidence());
        assertEquals(1.0f, result.getScore(), 0.0001f);
        assertEquals("30-day money back guarantee", result.getFaqItem().getAnswer());

        verify(faqItemRepository, times(1)).incrementHitCount(item.getId());
        verify(faqItemRepository, never()).findNearestByEmbedding(any(), any());
    }

    @Test
    @DisplayName("Should return high confidence match when PgVector similarity >= threshold")
    void testPgVectorMatch_AboveThreshold() {
        String query = "How do refunds work?";
        UUID faqId = UUID.randomUUID();

        FaqItem item = new FaqItem();
        item.setId(faqId);
        item.setTenantId(tenantId);
        item.setQuestion("What is your refund process?");
        item.setAnswer("You can request refund within 30 days.");

        when(faqItemRepository.findFirstByTenantAndExactQuestion(tenantId, query))
                .thenReturn(Optional.empty());

        FaqVectorMatch match = mock(FaqVectorMatch.class);
        when(match.getId()).thenReturn(faqId);
        when(match.getDistance()).thenReturn(0.08); // distance = 0.08 => similarity = 0.92 >= 0.85

        when(faqItemRepository.findNearestByEmbedding(eq(tenantId), anyString()))
                .thenReturn(Optional.of(match));
        when(faqItemRepository.findById(faqId))
                .thenReturn(Optional.of(item));

        FaqMatchingService.MatchResult result = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);

        assertNotNull(result);
        assertTrue(result.isHighConfidence());
        assertEquals(0.92f, result.getScore(), 0.01f);
        assertEquals(faqId, result.getFaqItem().getId());

        verify(faqItemRepository, times(1)).incrementHitCount(faqId);
    }

    @Test
    @DisplayName("Should return low confidence match when PgVector similarity < threshold")
    void testPgVectorMatch_BelowThreshold() {
        String query = "Can I fly a rocket to Mars?";
        UUID faqId = UUID.randomUUID();

        when(faqItemRepository.findFirstByTenantAndExactQuestion(tenantId, query))
                .thenReturn(Optional.empty());

        FaqVectorMatch match = mock(FaqVectorMatch.class);
        when(match.getDistance()).thenReturn(0.40); // distance = 0.40 => similarity = 0.60 < 0.85

        when(faqItemRepository.findNearestByEmbedding(eq(tenantId), anyString()))
                .thenReturn(Optional.of(match));

        FaqMatchingService.MatchResult result = faqMatchingService.findBestMatch(tenantId, query, queryEmbedding);

        assertNotNull(result);
        assertFalse(result.isHighConfidence());
        assertNull(result.getFaqItem());
        assertEquals(0.0f, result.getScore(), 0.0001f);

        verify(faqItemRepository, never()).incrementHitCount(any());
        verify(faqItemRepository, never()).findById(any());
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

        verifyNoInteractions(faqItemRepository);
    }

    @Test
    @DisplayName("Should return no match if embedding is null and exact match fails")
    void testNullEmbedding_NoExactMatch() {
        when(faqItemRepository.findFirstByTenantAndExactQuestion(tenantId, "Hello"))
                .thenReturn(Optional.empty());

        FaqMatchingService.MatchResult result = faqMatchingService.findBestMatch(tenantId, "Hello", null);

        assertFalse(result.isHighConfidence());
        assertNull(result.getFaqItem());
        verify(faqItemRepository, never()).findNearestByEmbedding(any(), any());
    }
}
