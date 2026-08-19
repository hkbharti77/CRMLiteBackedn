package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.FlowConfigDTO;
import com.chatcrmlite.backend.dto.PublicFlowSubmissionRequest;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.BusinessServiceRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.FlowConfigService;
import com.chatcrmlite.backend.services.PublicSubmissionService;
import com.chatcrmlite.backend.services.WebChatService;
import com.chatcrmlite.backend.models.WebChatMessage;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Public (unauthenticated) endpoints for the niche chat widget flow engine.
 *
 * All paths are under /api/v1/public/ which is already permitted in SecurityConfig.
 *
 * Endpoints:
 *   GET  /api/v1/public/flow/{businessId}          — returns FlowConfigDTO
 *   GET  /api/v1/public/services/{businessId}       — returns List<String> of service names
 *   POST /api/v1/public/lead/{businessId}           — creates a Lead
 *   POST /api/v1/public/enquiry/{businessId}        — creates an Enquiry (Lead with FOLLOW_UP)
 *   POST /api/v1/public/appointment/{businessId}    — creates an Appointment
 *   POST /api/v1/public/booking/{businessId}        — creates a Booking
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/public")
public class PublicFlowController {

    // Simple email pattern — local@domain.tld
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    @Autowired private UserRepository userRepository;
    @Autowired private FlowConfigService flowConfigService;
    @Autowired private PublicSubmissionService submissionService;
    @Autowired private BusinessServiceRepository businessServiceRepository;
    @Autowired private WebChatService webChatService;

    // ── Flow Config ────────────────────────────────────────────────────────

    @GetMapping("/flow/{businessId}")
    public ResponseEntity<FlowConfigDTO> getFlowConfig(
            @PathVariable UUID businessId,
            @RequestParam(required = false) String type) {
        return userRepository.findById(businessId)
                .map(user -> ResponseEntity.ok(flowConfigService.getFlowConfig(user, type)))
                .orElse(ResponseEntity.notFound().build());
    }


    // ── Trigger Config (for widget smart form suggestion) ─────────────────

    @GetMapping("/triggers/{businessId}")
    public ResponseEntity<Map<String, Object>> getTriggerConfig(@PathVariable UUID businessId) {
        return userRepository.findById(businessId)
                .map(user -> {
                    String slug = com.chatcrmlite.backend.services.FlowTriggerEngine.toSlug(user.getBusinessSubType());
                    String path = "/triggers/" + slug + ".json";
                    try (java.io.InputStream is = getClass().getResourceAsStream(path)) {
                        if (is == null) {
                            return ResponseEntity.ok(Map.<String, Object>of(
                                "direct_triggers", java.util.List.of(),
                                "intent_triggers", Map.of(),
                                "entity_keywords", Map.of(),
                                "config", Map.of("similarity_threshold", 80, "min_words_for_scoring", 4, "entity_boost", 15)
                            ));
                        }
                        @SuppressWarnings("unchecked")
                        Map<String, Object> triggerConfig = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(is, Map.class);
                        return ResponseEntity.ok(triggerConfig);
                    } catch (Exception e) {
                        return ResponseEntity.ok(Map.<String, Object>of(
                            "direct_triggers", java.util.List.of(),
                            "intent_triggers", Map.of(),
                            "entity_keywords", Map.of()
                        ));
                    }
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/services/{businessId}")
    public ResponseEntity<List<String>> getServices(@PathVariable UUID businessId) {
        return userRepository.findById(businessId)
                .map(user -> {
                    List<String> names = businessServiceRepository.findByOwner(user)
                            .stream()
                            .map(s -> s.getName())
                            .toList();
                    return ResponseEntity.ok(names);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Catalog (for Widget Services Menu) ──────────────────────────────────
    @GetMapping("/catalog/{businessId}")
    public ResponseEntity<List<com.chatcrmlite.backend.dto.BusinessServiceDTO>> getCatalog(@PathVariable UUID businessId) {
        return userRepository.findById(businessId)
                .map(user -> {
                    List<com.chatcrmlite.backend.dto.BusinessServiceDTO> dtos = businessServiceRepository.findByOwner(user)
                            .stream()
                            .map(s -> {
                                String imgUrl = s.getImageUrl();
                                boolean hasImg = (s.getImageData() != null && s.getImageData().length > 0) || (imgUrl != null && !imgUrl.isBlank());
                                if ((imgUrl == null || imgUrl.isBlank()) && s.getImageData() != null && s.getImageData().length > 0) {
                                    imgUrl = "/public/images/" + s.getId();
                                }
                                return com.chatcrmlite.backend.dto.BusinessServiceDTO.builder()
                                        .id(s.getId())
                                        .name(s.getName())
                                        .description(s.getDescription())
                                        .hasImage(hasImg)
                                        .imageUrl(imgUrl)
                                        .build();
                            })
                            .toList();
                    return ResponseEntity.ok(dtos);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Submission Endpoints ───────────────────────────────────────────────

    @PostMapping("/lead/{businessId}")
    public ResponseEntity<Map<String, String>> submitLead(
            @PathVariable UUID businessId,
            @Valid @RequestBody PublicFlowSubmissionRequest request) {

        User owner = resolveOwner(businessId);
        validateEmail(request);
        submissionService.submitLead(owner, request.getData());
        
        String sessionId = request.getData().getOrDefault("sessionId", "anonymous");
        webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, "📝 User submitted a Lead Form with email: " + request.getData().get("email"));
        
        return created("✅ Thank you! We've received your enquiry and will be in touch shortly.");
    }

    @PostMapping("/enquiry/{businessId}")
    public ResponseEntity<Map<String, String>> submitEnquiry(
            @PathVariable UUID businessId,
            @Valid @RequestBody PublicFlowSubmissionRequest request) {

        User owner = resolveOwner(businessId);
        validateEmail(request);
        submissionService.submitEnquiry(owner, request.getData());
        
        String sessionId = request.getData().getOrDefault("sessionId", "anonymous");
        webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, "📝 User submitted an Enquiry Form with email: " + request.getData().get("email"));
        
        return created("✅ Thank you! Your enquiry has been received. Our team will contact you soon.");
    }

    @PostMapping("/appointment/{businessId}")
    public ResponseEntity<Map<String, String>> submitAppointment(
            @PathVariable UUID businessId,
            @Valid @RequestBody PublicFlowSubmissionRequest request) {

        User owner = resolveOwner(businessId);
        validateEmail(request);
        submissionService.submitAppointment(owner, request.getData());
        
        String sessionId = request.getData().getOrDefault("sessionId", "anonymous");
        webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, "📅 User booked an Appointment with email: " + request.getData().get("email"));
        
        return created("✅ Your appointment has been booked! Our team will confirm the exact time shortly.");
    }

    @PostMapping("/booking/{businessId}")
    public ResponseEntity<Map<String, String>> submitBooking(
            @PathVariable UUID businessId,
            @Valid @RequestBody PublicFlowSubmissionRequest request) {

        User owner = resolveOwner(businessId);
        validateEmail(request);
        submissionService.submitBooking(owner, request.getData());
        
        String sessionId = request.getData().getOrDefault("sessionId", "anonymous");
        webChatService.saveMessage(owner, sessionId, WebChatMessage.Sender.BOT, "🔖 User confirmed a Booking with email: " + request.getData().get("email"));
        
        return created("✅ Your booking is confirmed! We'll send you the details on your email.");
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private User resolveOwner(UUID businessId) {
        return userRepository.findById(businessId)
                .orElseThrow(() -> new BusinessNotFoundException(businessId));
    }

    private void validateEmail(PublicFlowSubmissionRequest request) {
        Map<String, String> data = request.getData();
        if (data == null) {
            throw new InvalidSubmissionException("Email is required");
        }
        String email = data.get("email");
        if (email == null || email.isBlank()) {
            throw new InvalidSubmissionException("Email is required");
        }
        if (!EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new InvalidSubmissionException("Invalid email format");
        }
    }

    private ResponseEntity<Map<String, String>> created(String message) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("message", message));
    }

    // ── Inline exception classes (handled by GlobalExceptionHandler) ───────

    public static class BusinessNotFoundException extends RuntimeException {
        public BusinessNotFoundException(UUID id) {
            super("Business not found: " + id);
        }
    }

    public static class InvalidSubmissionException extends RuntimeException {
        public InvalidSubmissionException(String message) {
            super(message);
        }
    }
}
