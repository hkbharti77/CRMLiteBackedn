package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.dto.CustomEmailRequest;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/custom-emails")
public class CustomEmailController {

    private final CustomEmailService customEmailService;
    private final UserRepository     userRepository;

    @Autowired
    public CustomEmailController(CustomEmailService customEmailService, UserRepository userRepository) {
        this.customEmailService = customEmailService;
        this.userRepository = userRepository;
    }

    private User me() {
        String email = (String) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping("/send")
    public ResponseEntity<CustomEmailDTO> send(@Valid @RequestBody CustomEmailRequest req) {
        return ResponseEntity.ok(customEmailService.send(me(), req));
    }

    @PostMapping("/draft")
    public ResponseEntity<CustomEmailDTO> saveDraft(@Valid @RequestBody CustomEmailRequest req) {
        return ResponseEntity.ok(customEmailService.saveDraft(me(), req));
    }

    @GetMapping
    public ResponseEntity<Page<CustomEmailDTO>> history(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                customEmailService.getHistory(
                        me(),
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
                )
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomEmailDTO> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(customEmailService.getById(id, me()));
    }

    @PostMapping("/{id}/resend")
    public ResponseEntity<CustomEmailDTO> resend(@PathVariable UUID id) {
        CustomEmailDTO existing = customEmailService.getById(id, me());
        CustomEmailRequest req = new CustomEmailRequest();
        req.setSubject(existing.getSubject());
        req.setBody(existing.getBody());
        req.setCtaLabel(existing.getCtaLabel());
        req.setCtaUrl(existing.getCtaUrl());
        req.setRecipientMode(existing.getRecipientMode());
        req.setTagsFilter(existing.getTagsFilter());
        return ResponseEntity.ok(customEmailService.send(me(), req));
    }
}
