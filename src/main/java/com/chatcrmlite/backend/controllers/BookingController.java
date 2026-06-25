package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.BookingDTO;
import com.chatcrmlite.backend.dto.BookingRequest;
import com.chatcrmlite.backend.models.Booking;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.BookingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    @Autowired private BookingService bookingService;
    @Autowired private UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    /** POST /api/v1/bookings — manual booking create */
    @PostMapping
    public ResponseEntity<BookingDTO> create(@RequestBody BookingRequest req) {
        return ResponseEntity.ok(toDTO(bookingService.createBooking(req, getAuthenticatedUser())));
    }

    /** GET /api/v1/bookings — all bookings */
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<BookingDTO>> getAll() {
        return ResponseEntity.ok(bookingService.getAllBookings(getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** GET /api/v1/bookings/contact/{contactId} — bookings for a contact */
    @GetMapping("/contact/{contactId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<BookingDTO>> getForContact(@PathVariable UUID contactId) {
        return ResponseEntity.ok(bookingService.getBookingsForContact(contactId, getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** GET /api/v1/bookings/status/{status} */
    @GetMapping("/status/{status}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<List<BookingDTO>> getByStatus(@PathVariable Booking.BookingStatus status) {
        return ResponseEntity.ok(bookingService.getBookingsByStatus(status, getAuthenticatedUser())
                .stream().map(this::toDTO).collect(Collectors.toList()));
    }

    /** PATCH /api/v1/bookings/{id}/complete */
    @PatchMapping("/{id}/complete")
    public ResponseEntity<BookingDTO> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(bookingService.completeBooking(id, getAuthenticatedUser())));
    }

    /** PATCH /api/v1/bookings/{id}/cancel */
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<BookingDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(bookingService.cancelBooking(id, getAuthenticatedUser())));
    }

    /** PATCH /api/v1/bookings/{id}/noshow */
    @PatchMapping("/{id}/noshow")
    public ResponseEntity<BookingDTO> noShow(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(bookingService.markNoShow(id, getAuthenticatedUser())));
    }

    private BookingDTO toDTO(Booking b) {
        return BookingDTO.builder()
                .id(b.getId())
                .contactName(b.getContact().getName())
                .contactWaId(b.getContact().getWaId())
                .contactId(b.getContact().getId())
                .service(b.getService())
                .preferredSlot(b.getPreferredSlot())
                .collectedData(bookingService.parseCollectedData(b.getCollectedData()))
                .status(b.getStatus().name())
                .createdAt(b.getCreatedAt())
                .updatedAt(b.getUpdatedAt())
                .ownerName(b.getOwner() != null ? 
                        (b.getOwner().getDisplayName() != null && !b.getOwner().getDisplayName().isBlank() 
                            ? b.getOwner().getDisplayName() 
                            : b.getOwner().getEmail()) 
                        : "Unknown")
                .build();
    }
}
