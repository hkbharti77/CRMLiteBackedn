package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.DocumentChunk;
import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.DocumentChunkRepository;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = com.chatcrmlite.backend.ChatCrmBackendApplication.class)
@ActiveProfiles("test")
@Transactional
public class RagMultiTenantFilterTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private DocumentChunkRepository documentChunkRepository;

    @Autowired
    private FaqItemRepository faqItemRepository;

    private Tenant tenantA;
    private Tenant tenantB;

    private DocumentChunk chunkA;
    private DocumentChunk chunkB;

    private FaqItem faqA;
    private FaqItem faqB;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        TenantContext.setAdminMode(true);

        documentChunkRepository.deleteAll();
        faqItemRepository.deleteAll();
        tenantRepository.deleteAll();

        tenantA = Tenant.builder().businessName("Tenant A Corp").businessType("retail").build();
        tenantB = Tenant.builder().businessName("Tenant B Corp").businessType("tech").build();
        tenantA = tenantRepository.save(tenantA);
        tenantB = tenantRepository.save(tenantB);

        // Document Chunks
        chunkA = DocumentChunk.builder()
                .documentId(UUID.randomUUID())
                .tenant(tenantA)
                .tenantId(tenantA.getId())
                .content("Proprietary internal document content for Tenant A.")
                .contentHash("hash-tenant-a-123")
                .embedding("[0.1, 0.2, 0.3]")
                .metadata(null)
                .build();
        chunkA = documentChunkRepository.save(chunkA);

        chunkB = DocumentChunk.builder()
                .documentId(UUID.randomUUID())
                .tenant(tenantB)
                .tenantId(tenantB.getId())
                .content("Proprietary confidential business plans for Tenant B.")
                .contentHash("hash-tenant-b-456")
                .embedding("[0.4, 0.5, 0.6]")
                .metadata(null)
                .build();
        chunkB = documentChunkRepository.save(chunkB);

        // FAQ Items
        faqA = new FaqItem(null, tenantA.getId(), "What are Tenant A working hours?", "9 AM to 6 PM", "Support", "hours,time", "[0.1, 0.2]", true, 0L, null, null);
        faqA.setTenant(tenantA);
        faqA = faqItemRepository.save(faqA);

        faqB = new FaqItem(null, tenantB.getId(), "What are Tenant B pricing packages?", "$99/mo premium tier", "Billing", "pricing,tier", "[0.3, 0.4]", true, 0L, null, null);
        faqB.setTenant(tenantB);
        faqB = faqItemRepository.save(faqB);

        entityManager.flush();
        entityManager.clear();

        TenantContext.setAdminMode(false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("1. DocumentChunk findAll() is automatically isolated by Hibernate tenantFilter")
    void testDocumentChunkFindAllIsolated() {
        TenantContext.setTenantId(tenantA.getId());

        List<DocumentChunk> chunks = documentChunkRepository.findAll();
        assertEquals(1, chunks.size());
        assertEquals("Proprietary internal document content for Tenant A.", chunks.get(0).getContent());
        assertEquals(chunkA.getId(), chunks.get(0).getId());
    }

    @Test
    @DisplayName("2. DocumentChunk findById() across tenants returns empty (cross-tenant IDOR impossible)")
    void testDocumentChunkFindByIdCrossTenantBlocked() {
        TenantContext.setTenantId(tenantA.getId());

        Optional<DocumentChunk> ownChunk = documentChunkRepository.findById(chunkA.getId());
        assertTrue(ownChunk.isPresent());

        Optional<DocumentChunk> foreignChunk = documentChunkRepository.findById(chunkB.getId());
        assertTrue(foreignChunk.isEmpty(), "Cross-tenant chunk access via findById must return empty");
    }

    @Test
    @DisplayName("3. FaqItem findAll() is automatically isolated by Hibernate tenantFilter")
    void testFaqItemFindAllIsolated() {
        TenantContext.setTenantId(tenantA.getId());

        List<FaqItem> faqs = faqItemRepository.findAll();
        assertEquals(1, faqs.size());
        assertEquals("What are Tenant A working hours?", faqs.get(0).getQuestion());
        assertEquals(faqA.getId(), faqs.get(0).getId());
    }

    @Test
    @DisplayName("4. FaqItem findById() across tenants returns empty (cross-tenant IDOR impossible)")
    void testFaqItemFindByIdCrossTenantBlocked() {
        TenantContext.setTenantId(tenantA.getId());

        Optional<FaqItem> ownFaq = faqItemRepository.findById(faqA.getId());
        assertTrue(ownFaq.isPresent());

        Optional<FaqItem> foreignFaq = faqItemRepository.findById(faqB.getId());
        assertTrue(foreignFaq.isEmpty(), "Cross-tenant FAQ access via findById must return empty");
    }

    @Test
    @DisplayName("5. Tenant B context only observes Tenant B DocumentChunks and FAQs")
    void testTenantBIsolation() {
        TenantContext.setTenantId(tenantB.getId());

        List<DocumentChunk> chunks = documentChunkRepository.findAll();
        assertEquals(1, chunks.size());
        assertEquals(chunkB.getId(), chunks.get(0).getId());

        List<FaqItem> faqs = faqItemRepository.findAll();
        assertEquals(1, faqs.size());
        assertEquals(faqB.getId(), faqs.get(0).getId());
    }

    @Test
    @DisplayName("6. Platform Admin mode allows cross-tenant maintenance queries")
    void testAdminModeAllowsAll() {
        TenantContext.setAdminMode(true);

        List<DocumentChunk> chunks = documentChunkRepository.findAll();
        assertEquals(2, chunks.size(), "Admin mode should view chunks from all tenants");

        List<FaqItem> faqs = faqItemRepository.findAll();
        assertEquals(2, faqs.size(), "Admin mode should view FAQs from all tenants");
    }
}
