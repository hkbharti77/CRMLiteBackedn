package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.voice.VoiceAssistantConfigDTO;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.voice.VoiceConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/tenant/voice-config")
@RequiredArgsConstructor
public class VoiceConfigController {

    private final VoiceConfigService voiceConfigService;
    private final UserRepository userRepository;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VoiceAssistantConfigDTO> getVoiceConfig(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        VoiceAssistantConfigDTO dto = voiceConfigService.getVoiceConfigForTenant(user);
        return ResponseEntity.ok(dto);
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VoiceAssistantConfigDTO> updateVoiceConfig(
            @AuthenticationPrincipal String email,
            @Valid @RequestBody VoiceAssistantConfigDTO dto
    ) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        VoiceAssistantConfigDTO updated = voiceConfigService.updateVoiceConfigForTenant(user, dto);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/reset")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<VoiceAssistantConfigDTO> resetVoiceConfig(@AuthenticationPrincipal String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found: " + email));
        VoiceAssistantConfigDTO resetDto = voiceConfigService.resetVoiceConfigToDefaults(user);
        return ResponseEntity.ok(resetDto);
    }
}
