package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.ReminderDTO;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    @Autowired
    private ReminderService reminderService;

    @Autowired
    private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<ReminderDTO>> getPendingReminders() {
        return ResponseEntity.ok(reminderService.getPendingReminders(getAuthenticatedUser()).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList()));
    }

    @PostMapping
    public ResponseEntity<ReminderDTO> createReminder(@RequestBody Reminder reminder) {
        reminder.setOwner(getAuthenticatedUser());
        return ResponseEntity.ok(convertToDTO(reminderService.createReminder(reminder)));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<ReminderDTO> completeReminder(@PathVariable UUID id) {
        return ResponseEntity.ok(convertToDTO(reminderService.completeReminder(id, getAuthenticatedUser())));
    }

    private ReminderDTO convertToDTO(Reminder reminder) {
        return ReminderDTO.builder()
                .id(reminder.getId())
                .leadId(reminder.getLead().getId())
                .message(reminder.getMessage())
                .dueDate(reminder.getDueDate())
                .isCompleted(reminder.isCompleted())
                .build();
    }
}
