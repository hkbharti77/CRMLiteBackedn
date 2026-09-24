package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.journey.ContactChannelPreference;
import com.chatcrmlite.backend.repositories.journey.ContactChannelPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppSendEligibilityService {

    private final ContactChannelPreferenceRepository channelPreferenceRepository;

    public record EligibilityResult(boolean allowed, String reason) {
        public static EligibilityResult ok() {
            return new EligibilityResult(true, "ALLOWED");
        }
        public static EligibilityResult denied(String reason) {
            return new EligibilityResult(false, reason);
        }
    }

    /**
     * Determines whether a WhatsApp message can be sent to the given contact
     * adhering strictly to Meta Business Platform guidelines and international opt-out compliance.
     */
    public EligibilityResult canSendMessage(Contact contact, WhatsAppConfig config, boolean isMarketingMessage) {
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

        // Meta Opt-Out Compliance Check
        if (contact.isMarketingOptedOut()) {
            log.warn("⛔ [WhatsAppEligibility] Denied outbound WhatsApp message to contactId={}: Contact is marketing opted out.", contact.getId());
            return EligibilityResult.denied("Contact has opted out of WhatsApp messages.");
        }

        if (contact.getTenant() != null) {
            Optional<ContactChannelPreference> prefOpt = channelPreferenceRepository.findByBusinessIdAndContactId(
                    contact.getTenant().getId().toString(), contact.getId());
            if (prefOpt.isPresent()) {
                ContactChannelPreference pref = prefOpt.get();
                if (Boolean.TRUE.equals(pref.getIsGloballySuppressed())) {
                    return EligibilityResult.denied("Contact is globally suppressed across all channels.");
                }
                if ("OPTED_OUT".equalsIgnoreCase(pref.getWhatsappConsentStatus())) {
                    return EligibilityResult.denied("Contact has explicitly opted out of WhatsApp messages.");
                }
            }
        }

        if (contact.isBotPaused()) {
            return EligibilityResult.denied("Bot is paused for this contact.");
        }

        if (contact.isEscalated()) {
            return EligibilityResult.denied("Conversation is escalated to human agent.");
        }

        return EligibilityResult.ok();
    }

    public EligibilityResult canSendDocument(Contact contact, WhatsAppConfig config) {
        return canSendMessage(contact, config, false);
    }
}
