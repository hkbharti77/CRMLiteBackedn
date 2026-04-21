package com.chatcrmlite.backend.dto;

import lombok.Data;

/**
 * Request body for adding or updating an enquiry on a lead.
 */
@Data
public class EnquiryRequest {
    /** WHATSAPP | MANUAL | AI | FLOW */
    private String type;

    /** The enquiry message */
    private String message;

    /** Source label — e.g. "WhatsApp", "Manual Entry" */
    private String source;

    /** OPEN | RESOLVED | FOLLOW_UP  (optional on create, required on update) */
    private String status;
}
