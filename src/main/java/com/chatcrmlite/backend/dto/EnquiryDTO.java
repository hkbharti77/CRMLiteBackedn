package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single enquiry entry stored inside Lead.enquiries JSON array.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnquiryDTO {

    /** UUID string — auto-generated on creation */
    private String id;

    /** WHATSAPP | MANUAL | AI | FLOW */
    private String type;

    /** The actual enquiry message / question */
    private String message;

    /** Where it came from — e.g. "WhatsApp", "AI Chat", "Manual Entry" */
    private String source;

    /** OPEN | RESOLVED | FOLLOW_UP */
    private String status;

    /** ISO-8601 datetime string */
    private String createdAt;
}
