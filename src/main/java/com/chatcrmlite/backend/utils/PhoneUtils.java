package com.chatcrmlite.backend.utils;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import org.springframework.stereotype.Component;

@Component
public class PhoneUtils {

    private final PhoneNumberUtil phoneUtil = PhoneNumberUtil.getInstance();

    /**
     * Normalizes a phone number to E.164 format and removes the '+' sign.
     * This matches how wa_id is typically stored (e.g. 919876543210).
     *
     * @param phoneNumber The input phone number string
     * @param defaultRegion The default region (e.g., "IN", "US") to use if no country code is provided
     * @return The normalized WhatsApp ID (E.164 without '+')
     * @throws IllegalArgumentException if the phone number is invalid
     */
    public String normalizeToWaId(String phoneNumber, String defaultRegion) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Phone number cannot be empty");
        }

        try {
            String sanitized = phoneNumber.trim();
            // Prefix with + if it starts with country code length digits (heuristic for libphonenumber parsing)
            // Actually, if the frontend provides E164, it will have the +.
            if (!sanitized.startsWith("+") && sanitized.length() >= 10 && sanitized.matches("\\d+")) {
                sanitized = "+" + sanitized;
            }

            Phonenumber.PhoneNumber parsedNumber = phoneUtil.parse(sanitized, defaultRegion);
            
            if (!phoneUtil.isValidNumber(parsedNumber)) {
                throw new IllegalArgumentException("Invalid phone number format");
            }

            String e164 = phoneUtil.format(parsedNumber, PhoneNumberUtil.PhoneNumberFormat.E164);
            return e164.replace("+", "");
            
        } catch (NumberParseException e) {
            throw new IllegalArgumentException("Failed to parse phone number: " + e.getMessage());
        }
    }
}
