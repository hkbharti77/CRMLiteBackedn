package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.WhatsAppTemplateDto;
import com.chatcrmlite.backend.dto.WhatsAppAiTemplateResponse;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/whatsapp/templates")
@RequiredArgsConstructor
public class WhatsAppTemplateController {

    private final WhatsAppTemplateService templateService;
    private final UserRepository userRepository;

    private User getAuthenticatedUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Authenticated user not found"));
    }

    @GetMapping
    public ResponseEntity<List<WhatsAppTemplateDto>> getTemplates(
            @RequestParam(required = false, defaultValue = "false") boolean forceSync,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(templateService.getTemplatesForTenant(user, forceSync));
    }

    @PostMapping
    public ResponseEntity<WhatsAppTemplateDto> createTemplate(
            @RequestBody WhatsAppTemplateDto dto,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        return ResponseEntity.ok(templateService.createAndSubmitTemplate(dto, user));
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> deleteTemplate(
            @PathVariable String name,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        templateService.deleteTemplate(name, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ai/generate")
    public ResponseEntity<WhatsAppAiTemplateResponse> generateAiTemplate(
            @RequestBody Map<String, String> request,
            @AuthenticationPrincipal String email) {
        User user = getAuthenticatedUser(email);
        String prompt = request.getOrDefault("prompt", "");
        if (prompt.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(templateService.generateAiTemplate(user, prompt));
    }
}
