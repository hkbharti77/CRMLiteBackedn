package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.config.RateLimitConfig;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppLinkTrackingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/t/w")
@RequiredArgsConstructor
public class WhatsAppTrackingController {

    private final WhatsAppLinkTrackingService linkTrackingService;
    private final RateLimitConfig rateLimitConfig;

    /**
     * Public redirect endpoint for WhatsApp campaign CTA links and body URLs.
     * Records recipient click attribution and returns HTTP 302 Found redirect to target website.
     */
    @GetMapping("/{token}")
    public ResponseEntity<Void> trackClickAndRedirect(@PathVariable String token, HttpServletRequest request) {
        String clientIp = getClientIp(request);
        if (!rateLimitConfig.tryConsume(clientIp, RateLimitConfig.Tier.PUBLIC)) {
            log.warn("[WhatsAppTracking] Rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        Optional<String> optDestination = linkTrackingService.resolveAndRecordClick(token, clientIp);
        if (optDestination.isEmpty()) {
            log.warn("[WhatsAppTracking] Tracking token not found or invalid: {}", token);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String destinationUrl = optDestination.get();
        HttpHeaders headers = new HttpHeaders();
        try {
            headers.setLocation(URI.create(destinationUrl));
        } catch (Exception e) {
            log.error("[WhatsAppTracking] Failed to format redirect URI {}: {}", destinationUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        headers.setCacheControl("no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0L);

        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String getClientIp(HttpServletRequest request) {
        String xf = request.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isEmpty()) {
            return xf.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
