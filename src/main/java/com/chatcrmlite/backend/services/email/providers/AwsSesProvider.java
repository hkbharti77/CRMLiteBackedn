package com.chatcrmlite.backend.services.email.providers;

import com.chatcrmlite.backend.services.email.EmailRequest;
import com.chatcrmlite.backend.services.email.EmailSenderProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class AwsSesProvider implements EmailSenderProvider {
    
    private final String credentialsPayload;

    public AwsSesProvider(String credentialsPayload) {
        this.credentialsPayload = credentialsPayload;
        // In a real app, parse the JSON to get accessKey, secretKey, region
    }

    @Override
    public void sendTestEmail(String toEmail, String fromEmail) throws Exception {
        log.info("Sending AWS SES Test Email to {} from {}", toEmail, fromEmail);
        // Implement AWS SES SDK call here
        // If credentials are bad, throw exception
    }

    @Override
    public void sendBatch(List<EmailRequest> requests, String fromEmail) throws Exception {
        log.info("Sending AWS SES Batch of size {}", requests.size());
        // Implement AWS SES Batch send
    }
}
