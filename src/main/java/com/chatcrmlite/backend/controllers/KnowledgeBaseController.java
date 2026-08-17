package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.RagIngestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/knowledge-base")
@com.chatcrmlite.backend.security.RequiresPage("PAGE_KNOWLEDGE_BASE")
public class KnowledgeBaseController {

    @Autowired
    private RagIngestionService ragIngestionService;

    @Autowired
    private UserRepository userRepository;

    /**
     * Train the AI knowledge base for the current tenant.
     * Takes a "content" field in the request body.
     */
    @PostMapping("/train")
    public ResponseEntity<String> train(@RequestBody Map<String, String> request, @AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return ResponseEntity.badRequest().body("Content cannot be empty");
        }

        try {
            // Updated to use the new RagIngestionService
            ragIngestionService.ingestText(content, user.getId(), "Manual Training");
            return ResponseEntity.ok("AI training started in background! You can now ask questions via WhatsApp.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Training failed: " + e.getMessage());
        }
    }
}
