package com.chatcrmlite.backend.controllers.crm;

import com.chatcrmlite.backend.dto.crm.ContactMergeHistoryDTO;
import com.chatcrmlite.backend.dto.crm.DuplicateCandidateDTO;
import com.chatcrmlite.backend.dto.crm.MergeContactRequestDTO;
import com.chatcrmlite.backend.dto.crm.MergeResultDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.security.RequiresPage;
import com.chatcrmlite.backend.services.crm.ContactMergeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/contacts")
@RequiresPage("PAGE_CONTACTS")
@RequiredArgsConstructor
public class ContactMergeController {

    private final ContactMergeService mergeService;
    private final UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "User not authenticated"));
    }

    /**
     * Find potential duplicate contacts by phone, email, or name.
     */
    @GetMapping("/{id}/duplicate-candidates")
    public ResponseEntity<List<DuplicateCandidateDTO>> getDuplicateCandidates(@PathVariable("id") UUID id) {
        User user = getAuthenticatedUser();
        List<DuplicateCandidateDTO> candidates = mergeService.findDuplicateCandidates(id, user);
        return ResponseEntity.ok(candidates);
    }

    /**
     * Atomically merge a secondary contact into the primary contact record.
     */
    @PostMapping("/{id}/merge")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'ADMIN', 'OWNER', 'AGENT')")
    public ResponseEntity<MergeResultDTO> mergeContact(
            @PathVariable("id") UUID id,
            @Valid @RequestBody MergeContactRequestDTO request) {
        User user = getAuthenticatedUser();
        MergeResultDTO result = mergeService.executeContactMerge(id, request, user);
        return ResponseEntity.ok(result);
    }

    /**
     * Retrieve merge history and audit snapshots for a contact.
     */
    @GetMapping("/{id}/merge-history")
    public ResponseEntity<List<ContactMergeHistoryDTO>> getMergeHistory(@PathVariable("id") UUID id) {
        User user = getAuthenticatedUser();
        List<ContactMergeHistoryDTO> history = mergeService.getMergeHistory(id, user);
        return ResponseEntity.ok(history);
    }
}
