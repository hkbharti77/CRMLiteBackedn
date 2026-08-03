package com.chatcrmlite.backend.services.email.providers;

import com.chatcrmlite.backend.services.email.EmailRequest;
import com.chatcrmlite.backend.services.email.EmailSenderProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ZohoProvider implements EmailSenderProvider {
    
    private final SmtpProvider delegate;

    public ZohoProvider(String credentialsPayload) {
        this.delegate = new SmtpProvider(credentialsPayload);
    }

    @Override
    public void sendTestEmail(String toEmail, String fromEmail) throws Exception {
        log.info("Sending Zoho (SMTP) Test Email to {} from {}", toEmail, fromEmail);
        delegate.sendTestEmail(toEmail, fromEmail);
    }

    @Override
    public void sendBatch(List<EmailRequest> requests, String fromEmail) throws Exception {
        log.info("Sending Zoho (SMTP) Batch of size {}", requests.size());
        delegate.sendBatch(requests, fromEmail);
    }
}
