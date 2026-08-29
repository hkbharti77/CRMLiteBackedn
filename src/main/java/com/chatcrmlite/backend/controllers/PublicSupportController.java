package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.dto.SupportFormConfigDTO;
import com.chatcrmlite.backend.dto.SupportRequest;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.SupportFormConfigService;
import com.chatcrmlite.backend.services.TicketService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/public")
public class PublicSupportController {
    private static final Logger log = LoggerFactory.getLogger(PublicSupportController.class);

    @Autowired private UserRepository userRepository;
    @Autowired private TicketService ticketService;
    @Autowired private RateLimitConfig rateLimitConfig;
    @Autowired private SupportFormConfigService configService;

    @GetMapping("/support/config/{businessId}")
    public ResponseEntity<SupportFormConfigDTO> getSupportFormConfig(@PathVariable UUID businessId) {
        User owner = userRepository.findById(businessId)
                .orElseThrow(() -> new BusinessNotFoundException(businessId));

        SupportFormConfigDTO config = configService.getPublicConfig(businessId, owner);
        return ResponseEntity.ok(config);
    }

    @PostMapping("/support/{businessId}")
    public ResponseEntity<Map<String, String>> submitSupport(
            @PathVariable UUID businessId,
            @Valid @RequestBody SupportRequest req,
            jakarta.servlet.http.HttpServletRequest request) {

        String ipAddress = getClientIP(request);
        io.github.bucket4j.Bucket bucket = rateLimitConfig.resolveBucket(ipAddress);
        
        if (!bucket.tryConsume(1)) {
            log.warn("[PublicSupport] Rate limit exceeded for IP: {}", ipAddress);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "Too many requests. Please try again later."));
        }

        User owner = userRepository.findById(businessId)
                .orElseThrow(() -> new BusinessNotFoundException(businessId));

        com.chatcrmlite.backend.models.Ticket ticket = ticketService.submitSupportRequest(owner, req);

        log.info("[PublicSupport] Support request submitted for business={} from email={} IP={}",
                businessId, req.getEmail(), ipAddress);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of(
                        "message", "✅ Thank you for contacting support! We've received your request and will get back to you shortly.",
                        "ticketNumber", ticket.getTicketNumber()
                ));
    }

    private String getClientIP(jakarta.servlet.http.HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    public static class BusinessNotFoundException extends RuntimeException {
        public BusinessNotFoundException(UUID id) {
            super("Business not found: " + id);
        }
    }
}
