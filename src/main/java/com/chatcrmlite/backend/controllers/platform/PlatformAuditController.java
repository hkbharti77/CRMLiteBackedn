package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.models.PlatformAuditLog;
import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/audit")
@Transactional(readOnly = true)
public class PlatformAuditController {

    private final PlatformAuditService auditService;

    public PlatformAuditController(PlatformAuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listAuditLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        Page<PlatformAuditLog> result = auditService.findFiltered(action, targetType, from, to,
            PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, "timestamp")));

        return ResponseEntity.ok(Map.of(
            "content", result.getContent(),
            "page", page,
            "size", size,
            "totalElements", result.getTotalElements(),
            "totalPages", result.getTotalPages(),
            "hasNext", result.hasNext()
        ));
    }
}
