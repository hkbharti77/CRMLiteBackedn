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

@RestController
@RequestMapping("/api/v1/whatsapp-config")
public class WhatsAppConfigController {

    @Autowired
    private WhatsAppConfigRepository whatsappConfigRepository;

    @Autowired
    private com.chatcrmlite.backend.services.WhatsAppService whatsappService;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private MenuMediaRepository menuMediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Value("${app.public.url:}")
    private String publicAppUrl;

    @GetMapping("/feature-labels")
    public ResponseEntity<Map<String, String>> getFeatureLabels() {
        Map<String, String> labels = new HashMap<>();
        labels.put("SOS", com.chatcrmlite.backend.services.WhatsAppService.SOS_LABEL);
        labels.put("TRUST", com.chatcrmlite.backend.services.WhatsAppService.TRUST_LABEL);
        labels.put("OFFER", com.chatcrmlite.backend.services.WhatsAppService.OFFER_LABEL);
        labels.put("ABOUT", com.chatcrmlite.backend.services.WhatsAppService.ABOUT_LABEL);
        return ResponseEntity.ok(labels);
    }

    @GetMapping
    public ResponseEntity<WhatsAppConfig> getConfig(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
        
        return whatsappConfigRepository.findByUserId(user.getId())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> saveConfig(
            @AuthenticationPrincipal String email,
            @RequestBody Map<String, Object> body) {
        
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        String phoneNumberId = (String) body.get("phoneNumberId");
        String wabaId = (String) body.get("wabaId");
        String accessToken = (String) body.get("accessToken");
        String verifyToken = (String) body.get("verifyToken");
        String interactiveMenuJson = (String) body.get("interactiveMenuJson");
        String welcomeMessage = (String) body.get("welcomeMessage");
        String returningMessage = (String) body.get("returningMessage");
        Boolean showAboutContact= (Boolean) body.get("showAboutContact");
        String reviewUrl        = (String) body.get("reviewUrl");
        String portfolioUrl     = (String) body.get("portfolioUrl");
        String offerText        = (String) body.get("offerText");
        String sosNote          = (String) body.get("sosNote");
        String thirdButtonType   = (String) body.get("thirdButtonType");
        Boolean showTrustButton  = (Boolean) body.get("showTrustButton");
        Boolean showOfferButton  = (Boolean) body.get("showOfferButton");
        Boolean showSosButton    = (Boolean) body.get("showSosButton");
        String customSubMenusJson = (String) body.get("customSubMenusJson");
        String customMessagesJson = (String) body.get("customMessagesJson");

        if (interactiveMenuJson != null && !interactiveMenuJson.isBlank()) {
            try {
                com.chatcrmlite.backend.dto.MenuDto menu = objectMapper.readValue(
                        interactiveMenuJson, 
                        com.chatcrmlite.backend.dto.MenuDto.class);
                whatsappService.validateMenu(menu);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body("Menu validation failed: " + e.getMessage());
            }
        }

        WhatsAppConfig config = whatsappConfigRepository.findByUserId(user.getId())
                .orElse(new WhatsAppConfig());

        config.setUser(user);
        config.setPhoneNumberId(phoneNumberId != null ? phoneNumberId.trim() : null);
        config.setWabaId(wabaId != null ? wabaId.trim() : null);
        config.setAccessToken(accessToken != null ? accessToken.trim() : null);
        config.setVerifyToken(verifyToken != null ? verifyToken.trim() : null);
        config.setInteractiveMenuJson(interactiveMenuJson);
        config.setWelcomeMessage(welcomeMessage);
        config.setReturningMessage(returningMessage);
        config.setCustomSubMenusJson(customSubMenusJson);
        config.setCustomMessagesJson(customMessagesJson);
        
        // Dynamic Buttons Data
        config.setReviewUrl(reviewUrl);
        config.setPortfolioUrl(portfolioUrl);
        config.setOfferText(offerText);
        config.setSosNote(sosNote);
        config.setThirdButtonType(thirdButtonType);

        if (showAboutContact != null) {
            config.setShowAboutContact(showAboutContact);
        }
        if (showTrustButton != null) {
            config.setShowTrustButton(showTrustButton);
        }
        if (showOfferButton != null) {
            config.setShowOfferButton(showOfferButton);
        }
        if (showSosButton != null) {
            config.setShowSosButton(showSosButton);
        }

        whatsappConfigRepository.save(config);
        return ResponseEntity.ok("Config saved");
    }

    @PostMapping(value = "/upload-media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadMedia(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal String email) {
        if (email == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (file.isEmpty()) return ResponseEntity.badRequest().body("File is empty");
        if (file.getSize() > 5 * 1024 * 1024) return ResponseEntity.badRequest().body("File too large (max 5MB)");

        try {
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
