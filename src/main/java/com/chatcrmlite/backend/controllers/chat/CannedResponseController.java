package com.chatcrmlite.backend.controllers.chat;

import com.chatcrmlite.backend.dto.chat.CannedResponseDto;
import com.chatcrmlite.backend.dto.chat.CreateCannedResponseRequest;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.chat.CannedResponseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/canned-responses")
@RequiredArgsConstructor
public class CannedResponseController {

    private final CannedResponseService service;
    private final UserRepository userRepository;
    private final ContactRepository contactRepository;

    private User resolveUser(String email) {
        if (email == null) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @GetMapping
    public ResponseEntity<List<CannedResponseDto>> getAll(
            @RequestParam(value = "category", required = false) String category,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        if (user.getTenant() == null) {
            return ResponseEntity.ok(List.of());
        }
        if (category != null && !category.isBlank()) {
            return ResponseEntity.ok(service.getByCategory(user.getTenant().getId(), category));
        }
        return ResponseEntity.ok(service.getAll(user.getTenant().getId()));
    }

    @GetMapping("/search")
    public ResponseEntity<List<CannedResponseDto>> search(
            @RequestParam("q") String query,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        if (user.getTenant() == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(service.search(user.getTenant().getId(), query));
    }

    @PostMapping
    public ResponseEntity<?> create(
            @Valid @RequestBody CreateCannedResponseRequest request,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        try {
            CannedResponseDto dto = service.create(request, user);
            return ResponseEntity.status(HttpStatus.CREATED).body(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable UUID id,
            @Valid @RequestBody CreateCannedResponseRequest request,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        try {
            CannedResponseDto dto = service.update(id, request, user);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        try {
            service.delete(id, user);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/use")
    public ResponseEntity<Void> recordUsage(
            @PathVariable UUID id,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        if (user.getTenant() != null) {
            service.recordUsage(id, user.getTenant().getId());
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/interpolate")
    public ResponseEntity<Map<String, String>> interpolate(
            @RequestParam(value = "contactId", required = false) UUID contactId,
            @RequestBody Map<String, String> payload,
            @AuthenticationPrincipal String email) {
        User user = resolveUser(email);
        String template = payload.getOrDefault("content", "");
        Contact contact = null;
        if (contactId != null) {
            contact = contactRepository.findById(contactId).orElse(null);
        }
        String interpolated = service.interpolatePlaceholders(template, contact, user);
        return ResponseEntity.ok(Map.of("interpolated", interpolated));
    }
}
