package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.ai.action.AiAction;
import com.chatcrmlite.backend.dto.ai.catalog.CatalogCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AiCatalogDecisionServiceTest {

    private AiCatalogDecisionService decisionService;

    @BeforeEach
    void setUp() {
        decisionService = new AiCatalogDecisionService();
    }

    @Test
    @DisplayName("Empty candidate list results in NONE action")
    void emptyCandidatesYieldsNone() {
        AiAction action = decisionService.decideFromCandidates(Collections.emptyList(), "What is your address?", AiAction.DecisionSource.NATIVE_TOOL);
        assertEquals(AiAction.AiActionType.NONE, action.type());
        assertNull(action.catalogId());
    }

    @Test
    @DisplayName("Clear single candidate results in SEND_CATALOG action")
    void singleTopCandidateYieldsSend() {
        UUID docId = UUID.randomUUID();
        CatalogCandidate top = new CatalogCandidate(docId, "ERP Pricing Guide", "Prices", "pricing", 0.90);

        AiAction action = decisionService.decideFromCandidates(List.of(top), "What are your software prices?", AiAction.DecisionSource.NATIVE_TOOL);
        assertEquals(AiAction.AiActionType.SEND_CATALOG, action.type());
        assertEquals(docId, action.catalogId());
        assertNotNull(action.caption());
    }

    @Test
    @DisplayName("Multiple competing close candidates result in CLARIFY action")
    void closeCandidatesYieldClarify() {
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        CatalogCandidate c1 = new CatalogCandidate(doc1, "Villa Price List", "Prices", "price", 0.88);
        CatalogCandidate c2 = new CatalogCandidate(doc2, "Villa Floor Plans", "Layout", "layout", 0.85);

        AiAction action = decisionService.decideFromCandidates(List.of(c1, c2), "Can you share villa details?", AiAction.DecisionSource.NATIVE_TOOL);
        assertEquals(AiAction.AiActionType.CLARIFY, action.type());
        assertNull(action.catalogId());
        assertTrue(action.caption().contains("Villa Price List") || action.caption().contains("options"));
    }
}
