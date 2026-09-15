package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.FaqItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphIngestionServiceTest {

    @Test
    void unavailableWithoutDriverIsSafe() {
        GraphIngestionService service = new GraphIngestionService(null, null, new ObjectMapper());
        assertFalse(service.isAvailable());

        FaqItem faq = new FaqItem();
        faq.setId(UUID.randomUUID());
        faq.setTenantId(UUID.randomUUID());
        faq.setQuestion("Q?");
        faq.setAnswer("A");
        // Must not throw
        service.ingestFaq(faq);
    }

    @Test
    void nodeIdIsStableAndTenantScoped() {
        UUID tenant = UUID.randomUUID();
        String id = GraphIngestionService.nodeId(tenant.toString(), "Faq", "abc");
        assertEquals(tenant + ":Faq:abc", id);
        assertNotEquals(
                GraphIngestionService.nodeId(UUID.randomUUID().toString(), "Faq", "abc"),
                id
        );
    }

    @Test
    void invalidExtractionJsonDoesNotThrow() {
        GraphIngestionService service = new GraphIngestionService(null, null, new ObjectMapper());
        assertDoesNotThrow(() ->
                service.applyExtractionJson(
                        UUID.randomUUID().toString(),
                        "FAQ",
                        "src",
                        "anchor",
                        "not-json at all"));
    }
}
