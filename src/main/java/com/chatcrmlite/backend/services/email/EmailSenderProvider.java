package com.chatcrmlite.backend.services.email;

import java.util.List;

public interface EmailSenderProvider {
    void sendTestEmail(String toEmail, String fromEmail) throws Exception;
    void sendBatch(List<EmailRequest> requests, String fromEmail) throws Exception;
}
