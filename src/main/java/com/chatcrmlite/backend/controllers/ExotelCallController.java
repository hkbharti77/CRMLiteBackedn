package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.services.exotel.ExotelCallService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/exotel/calls")
@RequiredArgsConstructor
public class ExotelCallController {

    private final ExotelCallService exotelCallService;

    @PostMapping("/outbound")
    public ResponseEntity<Map<String, Object>> makeOutboundCall(@RequestBody Map<String, String> payload) {
        String toPhoneNumber = payload.get("phoneNumber");
        if (toPhoneNumber == null || toPhoneNumber.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "phoneNumber is required"));
        }

        boolean success = exotelCallService.initiateOutboundCall(toPhoneNumber, null);
        
        if (success) {
            return ResponseEntity.ok(Map.of("success", true, "message", "Outbound call initiated successfully"));
        } else {
            return ResponseEntity.internalServerError().body(Map.of("success", false, "error", "Failed to initiate outbound call"));
        }
    }
}
