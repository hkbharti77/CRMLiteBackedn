package com.chatcrmlite.backend.services.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TenantKnowledgeCatalogServiceTest {

    @Test
    void matchCatalogSplitsColumnsFromEntities() {
        TenantKnowledgeCatalogService svc = new TenantKnowledgeCatalogService(null);
        var catalog = new TenantKnowledgeCatalogService.CatalogSnapshot(
                List.of("Users", "Price_INR", "Product_Name"),
                List.of("Galaxy S24", "AI CRM Pro"),
                true);

        var matches = svc.matchCatalog(
                "how many products have more than 20 users like galaxy s24",
                catalog);

        assertTrue(matches.columns().stream().anyMatch(h -> h.equalsIgnoreCase("Users")));
        assertTrue(matches.entities().stream().anyMatch(h -> h.equalsIgnoreCase("Galaxy S24")));
        assertFalse(matches.entities().stream().anyMatch(h -> h.equalsIgnoreCase("Users")));
    }

    @Test
    void unionColumnsMergesAcrossUploadsCaseInsensitive() {
        List<String> merged = TenantKnowledgeCatalogService.unionColumns(
                List.of("Brand", "Users"),
                List.of("users", "SKU", "Warehouse"),
                100);
        assertEquals(List.of("Brand", "Users", "SKU", "Warehouse"), merged);
    }

    @Test
    void unionColumnsRespectsMaxCap() {
        List<String> merged = TenantKnowledgeCatalogService.unionColumns(
                List.of("A", "B", "C"),
                List.of("D", "E"),
                3);
        assertEquals(3, merged.size());
        assertEquals(List.of("A", "B", "C"), merged);
    }

    @Test
    void emptyWithoutNeo4j() {
        TenantKnowledgeCatalogService svc = new TenantKnowledgeCatalogService(null);
        assertFalse(svc.isAvailable());
        assertFalse(svc.load(null).hasStructuredData());
    }
}
