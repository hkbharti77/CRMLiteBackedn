package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.security.TenantContext;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/faq")
public class FaqController {

    @Autowired
    private FaqItemRepository faqItemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EmbeddingModel embeddingModel;

    @Autowired(required = false)
    private com.chatcrmlite.backend.services.rag.GraphIngestionService graphIngestionService;

    @Autowired
    private com.chatcrmlite.backend.services.FaqMatchingService faqMatchingService;

    private User getAuthenticatedUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private Tenant resolveTenant(User user) {
        if (user.getTenant() != null && user.getTenant().getId() != null) {
            return user.getTenant();
        }
        UUID contextTenantId = TenantContext.getTenantId();
        if (contextTenantId != null) {
            Tenant t = new Tenant();
            t.setId(contextTenantId);
            return t;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User does not belong to an active organization");
    }

    private UUID resolveTenantId(User user) {
        return resolveTenant(user).getId();
    }

    @GetMapping
    public ResponseEntity<List<FaqItem>> getAllFaqs(@AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        UUID tenantId = resolveTenantId(user);
        List<FaqItem> items = faqItemRepository.findByTenantId(tenantId);
        return ResponseEntity.ok(items);
    }

    @PostMapping
    public ResponseEntity<FaqItem> createFaq(@RequestBody FaqItem dto, @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = resolveTenant(user);
        UUID tenantId = tenant.getId();

        dto.setTenant(tenant);
        dto.setTenantId(tenantId);
        if (dto.getQuestion() != null && !dto.getQuestion().isBlank() && embeddingModel != null) {
            try {
                dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(dto.getQuestion()).content();
                dto.setEmbedding(Arrays.toString(embedding.vector()));
            } catch (Exception e) {
                log.warn("[FAQ-API] Embedding generation failed for question '{}': {}", dto.getQuestion(), e.getMessage());
            }
        }

        FaqItem saved = faqItemRepository.save(dto);
        log.info("[FAQ-API] Created FAQ Item ID: {} for tenant: {}", saved.getId(), tenantId);
        if (graphIngestionService != null) {
            try {
                graphIngestionService.ingestFaq(saved);
            } catch (Exception e) {
                log.warn("[FAQ-API] Graph ingest skipped: {}", e.getMessage());
            }
        }
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FaqItem> updateFaq(@PathVariable UUID id, @RequestBody FaqItem dto, @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = resolveTenant(user);
        UUID tenantId = tenant.getId();

        FaqItem existing = faqItemRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FAQ item not found"));

        existing.setTenant(tenant);
        existing.setTenantId(tenantId);
        existing.setAnswer(dto.getAnswer());
        existing.setCategory(dto.getCategory() != null ? dto.getCategory() : "General");
        existing.setKeywords(dto.getKeywords());
        if (dto.getIsActive() != null) {
            existing.setIsActive(dto.getIsActive());
        }

        if (dto.getQuestion() != null && !dto.getQuestion().equalsIgnoreCase(existing.getQuestion())) {
            existing.setQuestion(dto.getQuestion());
            if (embeddingModel != null) {
                try {
                    dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(dto.getQuestion()).content();
                    existing.setEmbedding(Arrays.toString(embedding.vector()));
                } catch (Exception e) {
                    log.warn("[FAQ-API] Embedding generation failed for updated question '{}': {}", dto.getQuestion(), e.getMessage());
                }
            }
        }

        FaqItem updated = faqItemRepository.save(existing);
        log.info("[FAQ-API] Updated FAQ Item ID: {} for tenant: {}", updated.getId(), tenantId);
        if (graphIngestionService != null) {
            try {
                graphIngestionService.ingestFaq(updated);
            } catch (Exception e) {
                log.warn("[FAQ-API] Graph ingest skipped: {}", e.getMessage());
            }
        }
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFaq(@PathVariable UUID id, @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        UUID tenantId = resolveTenantId(user);

        int deletedCount = faqItemRepository.deleteByFaqIdAndTenantId(id, tenantId);
        log.info("[FAQ-API] Deleted FAQ Item ID: {} for tenant: {} (affected: {})", id, tenantId, deletedCount);
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/all")
    public ResponseEntity<?> deleteAllFaqs(@AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        UUID tenantId = resolveTenantId(user);

        int count = faqItemRepository.deleteAllByTenantId(tenantId);
        log.info("[FAQ-API] Deleted ALL FAQ Items for tenant: {} (total deleted: {})", tenantId, count);
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.ok(java.util.Map.of("success", true, "deletedCount", count, "message", "All FAQs deleted successfully."));
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<?> batchDeleteFaqs(@RequestBody java.util.Map<String, List<UUID>> payload, @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        UUID tenantId = resolveTenantId(user);

        List<UUID> ids = payload != null ? payload.get("ids") : null;
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "No FAQ IDs provided for deletion"));
        }

        int count = faqItemRepository.deleteByIdInAndTenantId(ids, tenantId);
        log.info("[FAQ-API] Batch deleted {} FAQ Items for tenant: {}", count, tenantId);
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.ok(java.util.Map.of("success", true, "deletedCount", count, "message", "Selected FAQs deleted successfully."));
    }

    @PostMapping("/batch")
    public ResponseEntity<?> createBatchFaqs(@RequestBody(required = false) Object payload, @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        Tenant tenant = resolveTenant(user);
        UUID tenantId = tenant.getId();

        if (payload == null) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "No FAQ items provided"));
        }

        List<FaqItem> items = new java.util.ArrayList<>();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        try {
            if (payload instanceof List) {
                for (Object obj : (List<?>) payload) {
                    FaqItem item = mapper.convertValue(obj, FaqItem.class);
                    if (item != null && item.getQuestion() != null && !item.getQuestion().isBlank()) {
                        items.add(item);
                    }
                }
            } else if (payload instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) payload;
                Object listObj = map.containsKey("items") ? map.get("items") : (map.containsKey("faqs") ? map.get("faqs") : null);
                if (listObj instanceof List) {
                    for (Object obj : (List<?>) listObj) {
                        FaqItem item = mapper.convertValue(obj, FaqItem.class);
                        if (item != null && item.getQuestion() != null && !item.getQuestion().isBlank()) {
                            items.add(item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[FAQ-API] Deserialization error in batch FAQ import", e);
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "Invalid FAQ payload format: " + e.getMessage()));
        }

        if (items.isEmpty()) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "No valid FAQ questions found in payload. Ensure each item has a question and answer."));
        }

        for (FaqItem item : items) {
            item.setId(null);
            item.setTenant(tenant);
            item.setTenantId(tenantId);
            if (item.getQuestion() != null && !item.getQuestion().isBlank() && embeddingModel != null) {
                try {
                    dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(item.getQuestion()).content();
                    item.setEmbedding(Arrays.toString(embedding.vector()));
                } catch (Exception e) {
                    log.warn("[FAQ-API] Embedding generation failed for FAQ question '{}': {}", item.getQuestion(), e.getMessage());
                }
            }
            if (item.getAnswer() == null || item.getAnswer().isBlank()) {
                item.setAnswer("N/A");
            }
            if (item.getCategory() == null || item.getCategory().isBlank()) {
                item.setCategory("General");
            }
            if (item.getIsActive() == null) {
                item.setIsActive(true);
            }
            if (item.getHitCount() == null) {
                item.setHitCount(0L);
            }
        }

        List<FaqItem> saved = faqItemRepository.saveAll(items);
        log.info("[FAQ-API] Batch imported {} FAQ Items for tenant: {}", saved.size(), tenantId);
        if (graphIngestionService != null) {
            for (FaqItem item : saved) {
                try {
                    graphIngestionService.ingestFaq(item);
                } catch (Exception e) {
                    log.warn("[FAQ-API] Graph ingest skipped for FAQ {}: {}", item.getId(), e.getMessage());
                }
            }
        }
        faqMatchingService.invalidateTenantCache(tenantId);
        return ResponseEntity.ok(saved);
    }
}
