package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.ai.catalog.CatalogCandidate;
import com.chatcrmlite.backend.models.CatalogStatus;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.TenantAiCatalog;
import com.chatcrmlite.backend.repositories.TenantAiCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class CatalogCandidateServiceTest {

    @Mock
    private TenantAiCatalogRepository catalogRepository;

    private CatalogCandidateService candidateService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        candidateService = new CatalogCandidateService(catalogRepository);
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Exact keyword matching returns high relevance candidate")
    void findsMatchingCandidateForPricing() {
        Tenant tenant = new Tenant();
        tenant.setBusinessName("Test Corp");

        TenantAiCatalog pricingDoc = TenantAiCatalog.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .title("2026 ERP Pricing Guide")
                .description("Detailed cost breakdown for software licenses")
                .aiTriggerInstruction("Send when customer asks for pricing or quotes")
                .status(CatalogStatus.ACTIVE)
                .build();

        when(catalogRepository.findByTenantIdAndStatus(tenantId, CatalogStatus.ACTIVE))
                .thenReturn(List.of(pricingDoc));

        List<CatalogCandidate> candidates = candidateService.findCandidates(tenantId, "Can you share the pricing guide?", 0.65);

        assertFalse(candidates.isEmpty());
        assertEquals("2026 ERP Pricing Guide", candidates.get(0).title());
        assertTrue(candidates.get(0).relevanceScore() >= 0.65);
    }

    @Test
    @DisplayName("Unrelated query returns empty candidate list when below threshold")
    void unrelatedQueryReturnsEmpty() {
        Tenant tenant = new Tenant();
        tenant.setBusinessName("Test Corp");

        TenantAiCatalog doc = TenantAiCatalog.builder()
                .id(UUID.randomUUID())
                .tenant(tenant)
                .title("Healthcare Services Overview")
                .description("Hospital consultation details")
                .aiTriggerInstruction("Send when user asks for doctor appointments")
                .status(CatalogStatus.ACTIVE)
                .build();

        when(catalogRepository.findByTenantIdAndStatus(tenantId, CatalogStatus.ACTIVE))
                .thenReturn(List.of(doc));

        List<CatalogCandidate> candidates = candidateService.findCandidates(tenantId, "What are your office opening hours?", 0.65);

        assertTrue(candidates.isEmpty(), "Unrelated query should not match healthcare services doc");
    }
}
