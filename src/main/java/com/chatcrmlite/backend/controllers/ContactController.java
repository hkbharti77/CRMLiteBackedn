package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ContactDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ContactService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/contacts")
public class ContactController {

    @Autowired
    private ContactService contactService;

    @Autowired
    private com.chatcrmlite.backend.services.TagService tagService;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping
    public ResponseEntity<List<ContactDTO>> getContacts() {
        return ResponseEntity.ok(contactService.getContactsByUser(getAuthenticatedUser()));
    }

    @PostMapping
    public ResponseEntity<ContactDTO> createContact(
            @jakarta.validation.Valid @RequestBody com.chatcrmlite.backend.dto.ContactCreateRequestDTO request) {
        return ResponseEntity.ok(contactService.createContact(request, getAuthenticatedUser()));
    }

    @GetMapping("/tags/all")
    public ResponseEntity<List<String>> getAllContactTags() {
        return ResponseEntity.ok(tagService.getAllContactTags(getAuthenticatedUser()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ContactDTO> getContact(@PathVariable UUID id) {
        return ResponseEntity.ok(contactService.getContactById(id, getAuthenticatedUser()));
    }

    @PatchMapping("/{id}/tags")
    public ResponseEntity<Void> updateTags(@PathVariable UUID id, @RequestBody List<String> tags) {
        contactService.updateTags(id, tags, getAuthenticatedUser());
        return ResponseEntity.ok().build();
    }

    @RequestMapping(value = {"/{id}/toggle-bot", "/{id}/bot-paused"}, method = {RequestMethod.PATCH, RequestMethod.PUT})
    public ResponseEntity<Void> toggleBotPaused(@PathVariable UUID id, @RequestBody java.util.Map<String, Boolean> payload) {
        Boolean botPaused = payload.get("botPaused");
        if (botPaused != null) {
            contactService.toggleBotPaused(id, botPaused, getAuthenticatedUser());
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteContact(@PathVariable UUID id) {
        contactService.deleteContact(id, getAuthenticatedUser());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/import")
    public ResponseEntity<com.chatcrmlite.backend.dto.ImportResultDTO> importContacts(
            @RequestBody com.chatcrmlite.backend.dto.ContactImportBatchRequestDTO request) {
        return ResponseEntity.ok(contactService.importContacts(request, getAuthenticatedUser()));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportContacts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String botStatus) {
        String csv = contactService.exportContacts(search, source, botStatus, getAuthenticatedUser());
        
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "contacts.csv");
        
        return new ResponseEntity<>(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), headers, org.springframework.http.HttpStatus.OK);
    }
}
