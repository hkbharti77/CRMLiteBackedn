package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class WhatsAppSendEligibilityService {

    public record EligibilityResult(boolean allowed, String reason) {
        public static EligibilityResult ok() {
            return new EligibilityResult(true, "ALLOWED");
        }
        public static EligibilityResult denied(String reason) {
            return new EligibilityResult(false, reason);
        }
    }

    /**
     * Determines whether a document message can be sent to the given contact
     * in accordance with WhatsApp messaging rules, bot status, and config availability.
     */
    public EligibilityResult canSendDocument(Contact contact, WhatsAppConfig config) {
        if (config == null) {
            return EligibilityResult.denied("WhatsApp configuration is missing.");
        }

        if (config.getAccessToken() == null || config.getAccessToken().isBlank() ||
                config.getPhoneNumberId() == null || config.getPhoneNumberId().isBlank()) {
            return EligibilityResult.denied("WhatsApp credentials (token/phoneId) not configured.");
        }

        if (contact == null || contact.getWaId() == null || contact.getWaId().isBlank()) {
            return EligibilityResult.denied("Contact waId is missing.");
        }

        if (contact.isBotPaused()) {
            return EligibilityResult.denied("Bot is paused for this contact.");
        }

        if (contact.isEscalated()) {
            return EligibilityResult.denied("Conversation is escalated to human agent.");
        }

        // WhatsApp messaging window & eligibility passed
        return EligibilityResult.ok();
    }
}
