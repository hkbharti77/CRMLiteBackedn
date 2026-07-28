package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.PlatformSetting;
import com.chatcrmlite.backend.repositories.PlatformSettingRepository;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/platform/settings")
public class PlatformSettingsController {

    private final PlatformSettingRepository settingRepository;
    private final PlatformAuditService auditService;

    public PlatformSettingsController(PlatformSettingRepository settingRepository,
                                      PlatformAuditService auditService) {
        this.settingRepository = settingRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getSettings(HttpServletRequest request) {
        auditService.record("VIEW_SETTINGS", "SUCCESS", "Platform", null, "{}", request);

        List<PlatformSetting> settings = settingRepository.findAll();
        Map<String, String> map = settings.stream()
                .collect(Collectors.toMap(PlatformSetting::getSettingKey, PlatformSetting::getSettingValue, (v1, v2) -> v2));

        return ResponseEntity.ok(map);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> updateSettings(@RequestBody Map<String, String> updates,
                                                              HttpServletRequest request) {
        auditService.record("UPDATE_SETTINGS", "SUCCESS", "Platform", null,
                "Updated keys: " + updates.keySet(), request);

        List<PlatformSetting> toSave = new ArrayList<>();
        updates.forEach((key, val) -> {
            PlatformSetting ps = settingRepository.findById(key)
                    .orElse(new PlatformSetting(key, val));
            ps.setSettingValue(val);
            ps.setUpdatedBy(request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "admin");
            toSave.add(ps);
        });

        settingRepository.saveAll(toSave);

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "updatedCount", toSave.size()
        ));
    }
}
