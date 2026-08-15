package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.lead.LeadScoringService;
import com.chatcrmlite.backend.services.ai.SentimentAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LeadScoringController {

    @Autowired
    private LeadScoringService leadScoringService;

    @Autowired
    private SentimentAnalysisService sentimentAnalysisService;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Authenticated user not found"));
    }

    @GetMapping("/leads/{id}/score")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<LeadScoringService.LeadScoreResult> getLeadScore(@PathVariable UUID id) {
        User user = getAuthenticatedUser();
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Lead not found with id: " + id));
        if (lead.getTenant() != null && user.getTenant() != null && !lead.getTenant().getId().equals(user.getTenant().getId())) {
            return ResponseEntity.status(403).build();
        }
        LeadScoringService.LeadScoreResult result = leadScoringService.calculateAndUpdateLeadScore(lead);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/leads/{id}/recalculate-score")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<LeadScoringService.LeadScoreResult> recalculateLeadScore(@PathVariable UUID id) {
        return getLeadScore(id);
    }

    @GetMapping("/contacts/escalated")
    public ResponseEntity<List<Contact>> getEscalatedContacts() {
        User user = getAuthenticatedUser();
        List<Contact> escalatedContacts = contactRepository.findAllByOwner(user).stream()
                .filter(Contact::isEscalated)
                .toList();
        return ResponseEntity.ok(escalatedContacts);
    }

    @PostMapping("/contacts/{id}/resolve-escalation")
    public ResponseEntity<Map<String, Object>> resolveEscalation(@PathVariable UUID id, @RequestBody(required = false) Map<String, Boolean> body) {
        User user = getAuthenticatedUser();
        Contact contact = contactRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Contact not found with id: " + id));
        if (!contact.getOwner().getId().equals(user.getId())) {
            return ResponseEntity.status(403).build();
        }

        boolean resumeBot = body != null && Boolean.TRUE.equals(body.get("resumeBot"));

        contact.setEscalated(false);
        contact.setEscalatedAt(null);
        if (resumeBot) {
            contact.setBotPaused(false);
        }
        contactRepository.save(contact);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Escalation resolved successfully.",
                "botPaused", contact.isBotPaused()
        ));
    }
}
