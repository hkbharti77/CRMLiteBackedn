package com.chatcrmlite.backend.services.rag;

import com.chatcrmlite.backend.models.FaqItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GraphIngestionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unavailableWithoutDriverIsSafe() {
        GraphIngestionService service = new GraphIngestionService(null, null, mapper, null);
        assertFalse(service.isAvailable());

        FaqItem faq = new FaqItem();
        faq.setId(UUID.randomUUID());
        faq.setTenantId(UUID.randomUUID());
        faq.setQuestion("Q?");
        faq.setAnswer("A");
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
        GraphIngestionService service = new GraphIngestionService(null, null, mapper, null);
        assertDoesNotThrow(() ->
                service.applyExtractionJson(
                        UUID.randomUUID().toString(),
                        "FAQ",
                        "src",
                        "anchor",
                        "not-json at all"));
    }

    @Test
    void repairExtractionJsonFixesUnquotedKeysAndSingleQuotes() throws Exception {
        String broken = "{entities:[{type:'Category',name:'Mobile',confidence:0.9}],relationships:[]}";
        String fixed = GraphIngestionService.repairExtractionJson(broken);
        JsonNode root = mapper.readTree(fixed);
        assertTrue(root.path("entities").isArray());
        assertEquals("Category", root.path("entities").get(0).path("type").asText());
        assertEquals("Mobile", root.path("entities").get(0).path("name").asText());
    }

    @Test
    void repairExtractionJsonStripsMarkdownFence() throws Exception {
        String fenced = """
                ```json
                {"entities":[{"type":"Tag","name":"Samsung","confidence":0.9}],"relationships":[]}
                ```
                """;
        String fixed = GraphIngestionService.repairExtractionJson(fenced);
        JsonNode root = mapper.readTree(fixed);
        assertEquals("Samsung", root.path("entities").get(0).path("name").asText());
    }

    @Test
    void parseNamedCellsFromExcelRowFormat() {
        Map<String, String> fields = GraphIngestionService.parseNamedCells(
                "Brand: Samsung | Category: Mobile | Product: Galaxy S24");
        assertEquals("Samsung", fields.get("brand"));
        assertEquals("Mobile", fields.get("category"));
        assertEquals("Galaxy S24", fields.get("product"));
    }

    @Test
    void structuredRowExtractionNoopsWithoutNeo4j() {
        GraphIngestionService service = new GraphIngestionService(null, null, mapper, null);
        int n = service.applyStructuredRowExtraction(
                UUID.randomUUID().toString(),
                "DOCUMENT",
                "doc1",
                "anchor",
                "Row 2: Brand: Samsung | Category: Mobile | Product: Galaxy\n");
        assertEquals(0, n);
    }
}
