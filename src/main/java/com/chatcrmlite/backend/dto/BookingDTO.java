package com.chatcrmlite.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class BookingDTO {
    private UUID id;

    // Lead + Contact info
    private UUID leadId;
    private String leadStatus;
    private String contactName;
    private String contactWaId;
    private UUID contactId;

    // Booking fields
    private String service;
    private String preferredSlot;

    /** Parsed JSON map of all collected flow data */
    private Map<String, String> collectedData;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
