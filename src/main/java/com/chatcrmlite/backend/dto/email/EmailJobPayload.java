package com.chatcrmlite.backend.dto.email;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailJobPayload implements Serializable {
    private static final long serialVersionUID = 1L;

    private String toEmail;
    private String subject;
    private String templateName;
    private Map<String, Object> contextVariables;
    
    @Builder.Default
    private int retryCount = 0;
    
    @Builder.Default
    private Instant createdAt = Instant.now();
    
    private String jobType; // e.g. "LEAD_CREATED", "TICKET_UPDATE"
}
