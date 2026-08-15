package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.EmailProvider;
import com.chatcrmlite.backend.services.email.providers.AwsSesProvider;
import com.chatcrmlite.backend.services.email.providers.BrevoProvider;
import com.chatcrmlite.backend.services.email.providers.SmtpProvider;
import com.chatcrmlite.backend.services.email.providers.ZohoProvider;
import org.springframework.stereotype.Component;

@Component
public class ProviderFactory {
    
    public EmailSenderProvider getProvider(EmailProvider config) {
        if (config == null || config.getProviderType() == null) {
            throw new IllegalArgumentException("Invalid EmailProvider config");
        }
        
        return switch (config.getProviderType().toUpperCase()) {
            case "AWS_SES" -> new AwsSesProvider(config.getCredentialsPayload());
            case "BREVO" -> new BrevoProvider(config.getCredentialsPayload());
            case "ZOHO" -> new ZohoProvider(config.getCredentialsPayload());
            case "SMTP" -> new SmtpProvider(config.getCredentialsPayload());
            default -> throw new IllegalArgumentException("Unsupported provider type: " + config.getProviderType());
        };
    }
}
