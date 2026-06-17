package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.DashboardAggregateResponse;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.DashboardAggregateService;
import com.chatcrmlite.backend.services.DashboardExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardAggregateController {

    private final DashboardAggregateService dashboardAggregateService;
    private final DashboardExportService dashboardExportService;
    private final UserRepository userRepository;

    private User getAuthenticatedUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @GetMapping("/aggregate")
    public ResponseEntity<DashboardAggregateResponse> getDashboardAggregate() {
        User user = getAuthenticatedUser();
        return ResponseEntity.ok(dashboardAggregateService.getDashboardData(user));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportDashboardReport(
            @RequestParam(defaultValue = "csv") String format) {
        User user = getAuthenticatedUser();
        byte[] fileData = dashboardExportService.exportReport(user, format);
        
        String filename = "dashboard_report." + format.toLowerCase();
        MediaType mediaType = "pdf".equalsIgnoreCase(format) ? 
                MediaType.APPLICATION_PDF : MediaType.parseMediaType("text/csv");
                
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + filename)
                .contentType(mediaType)
                .body(fileData);
    }
}
