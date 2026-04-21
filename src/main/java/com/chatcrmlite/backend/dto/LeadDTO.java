package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Lead;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class LeadDTO {
    private UUID id;
    private ContactDTO contact;
    private Lead.LeadStatus status;

    /** Parsed list of enquiries from the JSON column */
    private List<EnquiryDTO> enquiries;

    private LocalDateTime createdAt;
    private LocalDateTime lastActivity;

    // Deal / Payment fields
    private BigDecimal dealValue;
    private Lead.PaymentStatus paymentStatus;
    private String currency;
    private String dealLabel;
}
