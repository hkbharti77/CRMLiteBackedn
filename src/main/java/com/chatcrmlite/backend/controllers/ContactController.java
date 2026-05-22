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

    @GetMapping("/{id}")
    public ResponseEntity<ContactDTO> getContact(@PathVariable UUID id) {
        return ResponseEntity.ok(contactService.getContactById(id, getAuthenticatedUser()));
    }

    @PatchMapping("/{id}/tags")
    public ResponseEntity<Void> updateTags(@PathVariable UUID id, @RequestBody List<String> tags) {
        contactService.updateTags(id, tags, getAuthenticatedUser());
        return ResponseEntity.ok().build();
    }
}
