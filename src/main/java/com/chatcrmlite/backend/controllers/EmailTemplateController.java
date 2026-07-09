package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.EmailTemplate;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.services.EmailTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/email-templates")
@RequiredArgsConstructor
public class EmailTemplateController {

    private final EmailTemplateService emailTemplateService;

    @GetMapping
    public ResponseEntity<List<EmailTemplate>> getAllTemplates(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(emailTemplateService.getTemplatesByTenant(user.getTenant()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<EmailTemplate> getTemplateById(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(emailTemplateService.getTemplateById(id, user.getTenant()));
    }

    @PostMapping
    public ResponseEntity<EmailTemplate> createTemplate(@RequestBody EmailTemplate template, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(emailTemplateService.createTemplate(template, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<EmailTemplate> updateTemplate(@PathVariable UUID id, @RequestBody EmailTemplate template, @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(emailTemplateService.updateTemplate(id, template, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        emailTemplateService.deleteTemplate(id, user.getTenant());
        return ResponseEntity.ok().build();
    }
}
