package com.chatcrmlite.backend.services.whatsapp.campaign;

import com.chatcrmlite.backend.models.Contact;
import org.springframework.stereotype.Component;

/**
 * Resolves destination identity according to 2026 Meta WhatsApp Business API specifications.
 * Phone recipients use "to", while BSUID & Parent BSUID recipients use "recipient".
 */
@Component
public class WhatsAppRecipientResolver {

    public enum RecipientIdentityType {
        PHONE,
        BSUID,
        PARENT_BSUID,
        UNSENDABLE
    }

    public record ResolvedRecipient(
        String phoneNumber,
        String bsuid,
        String parentBsuid,
        RecipientIdentityType identityType,
        String value
    ) {
        public ResolvedRecipient(String phoneNumber, String bsuid, String parentBsuid, RecipientIdentityType identityType) {
            this(
                phoneNumber,
                bsuid,
                parentBsuid,
                identityType,
                identityType == RecipientIdentityType.PHONE ? phoneNumber :
                (identityType == RecipientIdentityType.BSUID ? bsuid :
                (identityType == RecipientIdentityType.PARENT_BSUID ? parentBsuid : null))
            );
        }

        public boolean isSendable() {
            return identityType != RecipientIdentityType.UNSENDABLE;
        }

        public boolean isBsuid() {
            return identityType == RecipientIdentityType.BSUID;
        }

        public boolean isParentBsuid() {
            return identityType == RecipientIdentityType.PARENT_BSUID;
        }

        public boolean isPhone() {
            return identityType == RecipientIdentityType.PHONE;
        }

        public String getEffectiveBsuid() {
            return bsuid != null && !bsuid.isBlank() ? bsuid : parentBsuid;
        }
    }

    /**
     * Gate 0 Capability Validation:
     * Checks if a template is an OTP authentication template variant that Meta excludes from BSUID addressing.
     * Meta explicitly excludes one-tap, zero-tap, and copy-code OTP authentication templates
     * (OTP / ONE_TAP, OTP / ZERO_TAP, OTP / COPY_CODE) from BSUID addressing.
     */
    public boolean isAuthTemplateExcludingBsuid(String category, String buttonsJson) {
        if (buttonsJson != null) {
            String upper = buttonsJson.toUpperCase();
            if (upper.contains("ONE_TAP") || upper.contains("ZERO_TAP") || upper.contains("COPY_CODE")) {
                return true;
            }
        }
        return false;
    }

    public ResolvedRecipient resolve(Contact contact) {
        return resolve(contact, null, false);
    }

    public ResolvedRecipient resolve(Contact contact, String fallbackPhone) {
        return resolve(contact, fallbackPhone, false);
    }

    /**
     * Resolves recipient identity with Gate 0 capability validation.
     * If authTemplateExcludingBsuid is true, an available phone number is strictly required.
     * If only BSUID or Parent BSUID is present, the message is marked UNSENDABLE.
     */
    public ResolvedRecipient resolve(Contact contact, String fallbackPhone, boolean authTemplateExcludingBsuid) {
        if (authTemplateExcludingBsuid) {
            // Authentication templates with one-tap / zero-tap / copy-code require an available phone number
            if (contact != null && contact.getWaId() != null && !contact.getWaId().isBlank()) {
                return new ResolvedRecipient(contact.getWaId().trim(), null, null, RecipientIdentityType.PHONE);
            }
            if (fallbackPhone != null && !fallbackPhone.isBlank()) {
                return new ResolvedRecipient(fallbackPhone.trim(), null, null, RecipientIdentityType.PHONE);
            }
            // BSUID / Parent BSUID cannot be used for one-tap/zero-tap/copy-code auth templates
            return new ResolvedRecipient(null, null, null, RecipientIdentityType.UNSENDABLE);
        }

        if (contact != null) {
            // 1. If contact has a verified E.164 phone number (waId), address by phone
            if (contact.getWaId() != null && !contact.getWaId().isBlank()) {
                return new ResolvedRecipient(contact.getWaId().trim(), null, null, RecipientIdentityType.PHONE);
            }
            // 2. If contact has BSUID (username / business-scoped user ID), address by BSUID recipient
            if (contact.getBsuid() != null && !contact.getBsuid().isBlank()) {
                return new ResolvedRecipient(null, contact.getBsuid().trim(), null, RecipientIdentityType.BSUID);
            }
            // 3. If contact has Parent BSUID (portfolio-level user ID), address by Parent BSUID recipient
            if (contact.getParentBsuid() != null && !contact.getParentBsuid().isBlank()) {
                return new ResolvedRecipient(null, null, contact.getParentBsuid().trim(), RecipientIdentityType.PARENT_BSUID);
            }
        }
        if (fallbackPhone != null && !fallbackPhone.isBlank()) {
            return new ResolvedRecipient(fallbackPhone.trim(), null, null, RecipientIdentityType.PHONE);
        }
        return new ResolvedRecipient(null, null, null, RecipientIdentityType.UNSENDABLE);
    }
}
