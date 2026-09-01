package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import com.chatcrmlite.backend.models.MenuMedia;
import com.chatcrmlite.backend.repositories.MenuMediaRepository;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.http.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/whatsapp-config")
@PreAuthorize("@perm.has(authentication, 'SETTINGS_WHATSAPP')")
public class WhatsAppConfigController {

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.WhatsAppMenuService whatsappMenuService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private MenuMediaRepository menuMediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    @Autowired
    private com.chatcrmlite.backend.services.whatsapp.MetaOnboardingService metaOnboardingService;

    @Autowired
    private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;

    @Value("${app.public.url:}")
    private String publicAppUrl;

    @GetMapping("/feature-labels")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, String>> getFeatureLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("SOS", com.chatcrmlite.backend.services.whatsapp.WhatsAppMenuService.SOS_LABEL);
        labels.put("ABOUT", com.chatcrmlite.backend.services.whatsapp.WhatsAppMenuService.ABOUT_LABEL);
        labels.put("SUPPORT_FORM", com.chatcrmlite.backend.services.whatsapp.WhatsAppMenuService.SUPPORT_LABEL);
        return ResponseEntity.ok(labels);
    }

    @GetMapping
    public ResponseEntity<WhatsAppConfig> getConfig(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(user.getTenant().getId())
                .orElseGet(() -> {
                    WhatsAppConfig defaultConf = new WhatsAppConfig();
                    defaultConf.setTenant(user.getTenant());
                    defaultConf.setUser(user);
                    defaultConf.setWelcomeMessage("Hello {{name}}! Welcome to {{business}}. How can we assist you today?");
                    defaultConf.setReturningMessage("Welcome back {{name}}! Great to see you again at {{business}}. Choose an option below:");
                    defaultConf.setShowAboutContact(true);
                    defaultConf.setShowSosButton(true);
                    defaultConf.setShowSupportFormButton(true);
                    defaultConf.setThirdButtonType("ABOUT");
                    defaultConf.setConnectionType("LEGACY");
                    return defaultConf;
                });

        return ResponseEntity.ok(config);
    }

    @PostMapping
    public ResponseEntity<?> saveConfig(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only enforce WhatsApp integration quota when connecting a live phone number
        if (body.containsKey("phoneNumberId") && body.get("phoneNumberId") != null && !((String) body.get("phoneNumberId")).isBlank()) {
            quotaEnforcerService.verifyWhatsAppIntegrationAllowed(user.getTenant().getId());
        }

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(user.getTenant().getId())
                .orElseGet(() -> {
                    WhatsAppConfig nc = new WhatsAppConfig();
                    nc.setTenant(user.getTenant());
                    nc.setUser(user);
                    nc.setPhoneNumberId("pending_" + user.getTenant().getId());
                    nc.setWelcomeMessage("Hello {{name}}! Welcome to {{business}}. How can we assist you today?");
                    nc.setReturningMessage("Welcome back {{name}}! Great to see you again at {{business}}. Choose an option below:");
                    nc.setShowAboutContact(true);
                    nc.setShowSosButton(true);
                    nc.setShowSupportFormButton(true);
                    nc.setThirdButtonType("ABOUT");
                    nc.setConnectionType("LEGACY");
                    return nc;
                });

        config.setUser(user);

        // Safe partial updates — only overwrite properties that are explicitly present in the request body
        if (body.containsKey("welcomeMessage")) {
            config.setWelcomeMessage((String) body.get("welcomeMessage"));
        }
        if (body.containsKey("returningMessage")) {
            config.setReturningMessage((String) body.get("returningMessage"));
        }
        if (body.containsKey("showAboutContact")) {
            config.setShowAboutContact((Boolean) body.get("showAboutContact"));
        }
        if (body.containsKey("showSosButton")) {
            config.setShowSosButton((Boolean) body.get("showSosButton"));
        }
        if (body.containsKey("showSupportFormButton")) {
            config.setShowSupportFormButton((Boolean) body.get("showSupportFormButton"));
        }
        if (body.containsKey("sosNote")) {
            config.setSosNote((String) body.get("sosNote"));
        }
        if (body.containsKey("thirdButtonType")) {
            config.setThirdButtonType((String) body.get("thirdButtonType"));
        }
        if (body.containsKey("portfolioUrl")) {
            config.setPortfolioUrl((String) body.get("portfolioUrl"));
        }
        if (body.containsKey("customSubMenusJson")) {
            config.setCustomSubMenusJson((String) body.get("customSubMenusJson"));
        }
        if (body.containsKey("customMessagesJson")) {
            config.setCustomMessagesJson((String) body.get("customMessagesJson"));
        }
        if (body.containsKey("flowCancelMenuJson")) {
            config.setFlowCancelMenuJson((String) body.get("flowCancelMenuJson"));
        }
        if (body.containsKey("flowCompletionMenuJson")) {
            config.setFlowCompletionMenuJson((String) body.get("flowCompletionMenuJson"));
        }
        if (body.containsKey("aiResponseMenuJson")) {
            config.setAiResponseMenuJson((String) body.get("aiResponseMenuJson"));
        }
        if (body.containsKey("guardrailMessageAbuse")) {
            config.setGuardrailMessageAbuse((String) body.get("guardrailMessageAbuse"));
        }
        if (body.containsKey("guardrailMessageGibberish")) {
            config.setGuardrailMessageGibberish((String) body.get("guardrailMessageGibberish"));
        }
        if (body.containsKey("interactiveMenuJson")) {
            String menuJson = (String) body.get("interactiveMenuJson");
            config.setInteractiveMenuJson(menuJson != null && !menuJson.isBlank() ? menuJson.trim() : null);
        }
        if (body.containsKey("leadButtonLabel")) {
            String val = (String) body.get("leadButtonLabel");
            config.setLeadButtonLabel(val != null && !val.isBlank() ? val.trim() : null);
        }
        if (body.containsKey("appointmentButtonLabel")) {
            String val = (String) body.get("appointmentButtonLabel");
            config.setAppointmentButtonLabel(val != null && !val.isBlank() ? val.trim() : null);
        }
        if (body.containsKey("bookingButtonLabel")) {
            String val = (String) body.get("bookingButtonLabel");
            config.setBookingButtonLabel(val != null && !val.isBlank() ? val.trim() : null);
        }
        if (body.containsKey("phoneNumberId")) {
            String phoneNumberId = (String) body.get("phoneNumberId");
            if (phoneNumberId != null && !phoneNumberId.isBlank()) config.setPhoneNumberId(phoneNumberId.trim());
        }
        if (body.containsKey("wabaId")) {
            String wabaId = (String) body.get("wabaId");
            if (wabaId != null && !wabaId.isBlank()) config.setWabaId(wabaId.trim());
        }
        if (body.containsKey("accessToken")) {
            String accessToken = (String) body.get("accessToken");
            if (accessToken != null && !accessToken.isBlank()) config.setAccessToken(accessToken.trim());
        }
        if (body.containsKey("verifyToken")) {
            String verifyToken = (String) body.get("verifyToken");
            if (verifyToken != null && !verifyToken.isBlank()) config.setVerifyToken(verifyToken.trim());
        }
        if (body.containsKey("appSecret")) {
            String appSecret = (String) body.get("appSecret");
            if (appSecret != null && !appSecret.isBlank()) config.setAppSecret(appSecret.trim());
        }
        if (body.containsKey("connectionType")) {
            String connectionType = (String) body.get("connectionType");
            if (connectionType != null && !connectionType.isBlank()) config.setConnectionType(connectionType.trim());
        }
        if (body.containsKey("embeddedBusinessId")) config.setEmbeddedBusinessId((String) body.get("embeddedBusinessId"));
        if (body.containsKey("embeddedWabaId")) config.setEmbeddedWabaId((String) body.get("embeddedWabaId"));
        if (body.containsKey("embeddedPhoneId")) config.setEmbeddedPhoneId((String) body.get("embeddedPhoneId"));
        if (body.containsKey("flowsRoutingConfigJson")) {
            Object routing = body.get("flowsRoutingConfigJson");
            if (routing instanceof String str) {
                config.setFlowsRoutingConfigJson(str);
            } else if (routing != null) {
                try {
                    config.setFlowsRoutingConfigJson(objectMapper.writeValueAsString(routing));
                } catch (Exception ignored) {}
            }
        }

        WhatsAppConfig saved = whatsappConfigRepository.save(config);
        return ResponseEntity.ok(Map.of("message", "Configuration saved successfully", "config", saved));
    }

    @PostMapping("/embedded-signup/callback")
    public ResponseEntity<?> handleEmbeddedSignupCallback(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, String> body) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String code = body.get("code");
        String sessionId = body.get("sessionId");

        if (code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body("OAuth authorization code is required");
        }

        try {
            // Ensure session exists or create one if legacy caller
            if (sessionId == null || sessionId.isBlank()) {
                Map<String, Object> session = metaOnboardingService.createSession(user);
                sessionId = (String) session.get("sessionId");
            }
            Map<String, Object> result = metaOnboardingService.exchangeAndProvision(code, sessionId, user);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "Embedded signup exchange failed: " + e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<?> deleteConfig(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return whatsappConfigRepository.findByUserId(user.getId())
                .map(config -> {
                    whatsappConfigRepository.delete(config);
                    return ResponseEntity.ok().body("Configuration deleted successfully");
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping(value = "/upload-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadMedia(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (file.isEmpty()) return ResponseEntity.badRequest().body("File is empty");
        if (file.getSize() > 50L * 1024 * 1024) return ResponseEntity.badRequest().body("File too large (max 50MB)");

        try {
            if (cloudinaryStorageService.isConfigured()) {
                String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "whatsapp_media";
                String sanitized = originalFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
                java.util.UUID tenantId = (user.getTenant() != null ? user.getTenant().getId() : user.getId());
                String key = cloudinaryStorageService.buildTenantKey(tenantId, "whatsapp-media", sanitized);
                String mediaUrl = cloudinaryStorageService.uploadFile(key, file);

                Map<String, Object> resp = new HashMap<>();
                resp.put("url", mediaUrl);
                resp.put("key", key);
                resp.put("filename", originalFilename);
                return ResponseEntity.ok(resp);
            }

            MenuMedia media = MenuMedia.builder()
                    .owner(user)
                    .imageData(file.getBytes())
                    .contentType(file.getContentType())
                    .build();
            MenuMedia saved = menuMediaRepository.save(media);

            String baseUrl = (publicAppUrl != null && !publicAppUrl.isEmpty()) ? publicAppUrl : ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
            String url = baseUrl + "/public/images/menu/" + saved.getId();
            
            Map<String, String> resp = new HashMap<>();
            resp.put("url", url);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Upload failed: " + e.getMessage());
        }
    }
}
