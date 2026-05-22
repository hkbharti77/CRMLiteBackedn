package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ThemeConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.NicheThemeService;
import com.chatcrmlite.backend.services.RagRetrievalService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
public class PublicChatController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private NicheThemeService themeService;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @GetMapping("/config/{businessId}")
    public ResponseEntity<ThemeConfigDTO> getPublicConfig(@PathVariable UUID businessId) {
        return userRepository.findById(businessId)
                .map(user -> ResponseEntity.ok(themeService.getThemeForUser(user)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/chat/{businessId}")
    public ResponseEntity<Map<String, String>> handlePublicChat(
            @PathVariable UUID businessId,
            @RequestBody Map<String, String> request) {
        
        String message = request.get("message");
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is required"));
        }

        String response = ragRetrievalService.getAiResponse(message, businessId);
        
        if (response == null || response.isBlank()) {
            response = "I'm sorry, I don't have information about that. How else can I help you?";
        }

        return ResponseEntity.ok(Map.of("response", response));
    }
}
