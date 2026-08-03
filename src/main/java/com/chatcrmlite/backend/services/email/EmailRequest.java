package com.chatcrmlite.backend.services.email;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailRequest {
    private String toEmail;
    private String subject;
    private String htmlBody;
    private String textBody;
}
