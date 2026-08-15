package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.FaqItem;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.FaqItemRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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

    @GetMapping
    public ResponseEntity<List<FaqItem>> getAllFaqs(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        List<FaqItem> items = faqItemRepository.findByTenantId(user.getId());
        return ResponseEntity.ok(items);
    }

    @PostMapping
    public ResponseEntity<FaqItem> createFaq(@RequestBody FaqItem dto, @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        dto.setTenantId(user.getId());
        if (dto.getQuestion() != null && !dto.getQuestion().isBlank()) {
            dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(dto.getQuestion()).content();
            dto.setEmbedding(Arrays.toString(embedding.vector()));
        }

        FaqItem saved = faqItemRepository.save(dto);
        log.info("[FAQ-API] Created FAQ Item ID: {} for tenant: {}", saved.getId(), user.getId());
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FaqItem> updateFaq(@PathVariable UUID id, @RequestBody FaqItem dto, @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        FaqItem existing = faqItemRepository.findByIdAndTenantId(id, user.getId())
                .orElseThrow(() -> new RuntimeException("FAQ item not found"));

        existing.setAnswer(dto.getAnswer());
        existing.setCategory(dto.getCategory() != null ? dto.getCategory() : "General");
        existing.setKeywords(dto.getKeywords());
        if (dto.getIsActive() != null) {
            existing.setIsActive(dto.getIsActive());
        }

        if (dto.getQuestion() != null && !dto.getQuestion().equalsIgnoreCase(existing.getQuestion())) {
            existing.setQuestion(dto.getQuestion());
            dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(dto.getQuestion()).content();
            existing.setEmbedding(Arrays.toString(embedding.vector()));
        }

        FaqItem updated = faqItemRepository.save(existing);
        log.info("[FAQ-API] Updated FAQ Item ID: {} for tenant: {}", updated.getId(), user.getId());
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFaq(@PathVariable UUID id, @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        FaqItem existing = faqItemRepository.findByIdAndTenantId(id, user.getId())
                .orElseThrow(() -> new RuntimeException("FAQ item not found"));

        faqItemRepository.delete(existing);
        log.info("[FAQ-API] Deleted FAQ Item ID: {} for tenant: {}", id, user.getId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch")
    public ResponseEntity<List<FaqItem>> createBatchFaqs(@RequestBody List<FaqItem> items, @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (items == null || items.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        for (FaqItem item : items) {
            item.setTenantId(user.getId());
            if (item.getQuestion() != null && !item.getQuestion().isBlank()) {
                dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(item.getQuestion()).content();
                item.setEmbedding(Arrays.toString(embedding.vector()));
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
        log.info("[FAQ-API] Batch imported {} FAQ Items for tenant: {}", saved.size(), user.getId());
        return ResponseEntity.ok(saved);
    }
}
