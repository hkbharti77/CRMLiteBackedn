package com.chatcrmlite.backend.services.email.providers;

import com.chatcrmlite.backend.services.email.EmailRequest;
import com.chatcrmlite.backend.services.email.EmailSenderProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class BrevoProvider implements EmailSenderProvider {
    
    private final String credentialsPayload;

    public BrevoProvider(String credentialsPayload) {
        this.credentialsPayload = credentialsPayload;
    }

    @Override
    public void sendTestEmail(String toEmail, String fromEmail) throws Exception {
        log.info("Sending Brevo Test Email to {} from {}", toEmail, fromEmail);
    }

    @Override
    public void sendBatch(List<EmailRequest> requests, String fromEmail) throws Exception {
        log.info("Sending Brevo Batch of size {}", requests.size());
    }
}
