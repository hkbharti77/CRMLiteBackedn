package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.AppointmentDTO;
import com.chatcrmlite.backend.dto.AppointmentRequest;
import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.AppointmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {

    @Autowired private AppointmentService appointmentService;
    @Autowired private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @PostMapping
    public ResponseEntity<AppointmentDTO> book(@RequestBody AppointmentRequest req) {
        return ResponseEntity.ok(toDTO(appointmentService.bookAppointment(req, getAuthenticatedUser())));
    }

    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentDTO>> getAll() {
        return ResponseEntity.ok(appointmentService.getAllAppointments(getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    @GetMapping("/today")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentDTO>> getToday() {
        return ResponseEntity.ok(appointmentService.getTodayAppointments(getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    @GetMapping("/today/count")
    public ResponseEntity<Long> getTodayCount() {
        return ResponseEntity.ok(appointmentService.countTodayAppointments(getAuthenticatedUser()));
    }

    @GetMapping("/contact/{contactId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<AppointmentDTO>> getForContact(@PathVariable UUID contactId) {
        return ResponseEntity.ok(appointmentService.getAppointmentsForContact(contactId, getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    @PatchMapping("/{id}/complete")
    public ResponseEntity<AppointmentDTO> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(appointmentService.completeAppointment(id, getAuthenticatedUser())));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<AppointmentDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(appointmentService.cancelAppointment(id, getAuthenticatedUser())));
    }

    @PatchMapping("/{id}/noshow")
    public ResponseEntity<AppointmentDTO> noShow(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(appointmentService.markNoShow(id, getAuthenticatedUser())));
    }

    private AppointmentDTO toDTO(Appointment a) {
        return AppointmentDTO.builder()
                .id(a.getId())
                .contactName(a.getContact().getName())
                .contactWaId(a.getContact().getWaId())
                .contactId(a.getContact().getId())
                .appointmentDateTime(a.getAppointmentDateTime())
                .title(a.getTitle())
                .collectedData(appointmentService.parseCollectedData(a.getCollectedData()))
                .meetingLink(a.getMeetingLink())
                .status(a.getStatus().name())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .ownerName(a.getOwner() != null ? 
                        (a.getOwner().getDisplayName() != null && !a.getOwner().getDisplayName().isBlank() 
                            ? a.getOwner().getDisplayName() 
                            : a.getOwner().getEmail()) 
                        : "Unknown")
                .build();
    }
}
