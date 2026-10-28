package com.chatcrmlite.backend.dto.crm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateCandidateDTO {
    private UUID contactId;
    private String name;
    private String phone;
    private String email;
    private String source;
    private List<String> tags;
    private Instant createdAt;
    private String matchReason; // "PHONE_EXACT", "EMAIL_EXACT", "NAME_EXACT", "MULTIPLE_MATCHES"
    private int confidenceScore; // 0 - 100
    private long messageCount;
    private long leadCount;
    private long appointmentCount;
}
