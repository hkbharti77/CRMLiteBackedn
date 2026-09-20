package com.chatcrmlite.backend.services.whatsapp.checkout;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class CheckoutAddressParser {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PINCODE_PATTERN = Pattern.compile("\\b([1-9][0-9]{5})\\b");

    private static final Pattern LABEL_NAME = Pattern.compile("(?i)^(?:name|customer\\s*name)\\s*[:=-]\\s*(.+)$");
    private static final Pattern LABEL_ADDRESS = Pattern.compile("(?i)^(?:address|street|house|flat|add)\\s*[:=-]\\s*(.+)$");
    private static final Pattern LABEL_CITY = Pattern.compile("(?i)^(?:city|town|district)\\s*[:=-]\\s*(.+)$");
    private static final Pattern LABEL_STATE = Pattern.compile("(?i)^(?:state|province)\\s*[:=-]\\s*(.+)$");
    private static final Pattern LABEL_PIN = Pattern.compile("(?i)^(?:pin|pincode|postal|zip|zipcode)\\s*[:=-]\\s*(.+)$");
    private static final Pattern LABEL_EMAIL = Pattern.compile("(?i)^(?:email|mail|e-mail)\\s*[:=-]\\s*(.+)$");

    public ParsedAddress parse(String rawText, String fallbackCustomerName) {
        if (rawText == null || rawText.isBlank()) {
            ParsedAddress empty = ParsedAddress.builder()
                    .valid(false)
                    .shippingName(fallbackCustomerName)
                    .build();
            empty.getMissingFields().add("Shipping Address");
            empty.getMissingFields().add("Email Address");
            return empty;
        }

        String text = rawText.trim();
        ParsedAddress address = new ParsedAddress();
        address.setCountry("IN");

        // 1. Extract Email if present
        Matcher emailMatcher = EMAIL_PATTERN.matcher(text);
        if (emailMatcher.find()) {
            address.setCustomerEmail(emailMatcher.group().trim().toLowerCase());
        }

        // 2. Extract PIN code if present
        Matcher pinMatcher = PINCODE_PATTERN.matcher(text);
        if (pinMatcher.find()) {
            address.setPostalCode(pinMatcher.group(1).trim());
        }

        // 3. Try parsing structured line-by-line labels
        String[] lines = text.split("\\r?\\n");
        StringBuilder addressBuilder = new StringBuilder();
        boolean foundLabeledField = false;

        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.isBlank()) continue;

            Matcher mName = LABEL_NAME.matcher(trimmedLine);
            Matcher mAddr = LABEL_ADDRESS.matcher(trimmedLine);
            Matcher mCity = LABEL_CITY.matcher(trimmedLine);
            Matcher mState = LABEL_STATE.matcher(trimmedLine);
            Matcher mPin = LABEL_PIN.matcher(trimmedLine);
            Matcher mEmail = LABEL_EMAIL.matcher(trimmedLine);

            if (mName.matches()) {
                address.setShippingName(mName.group(1).trim());
                foundLabeledField = true;
            } else if (mAddr.matches()) {
                if (address.getAddressLine1() == null) {
                    address.setAddressLine1(mAddr.group(1).trim());
                } else {
                    address.setAddressLine2(mAddr.group(1).trim());
                }
                foundLabeledField = true;
            } else if (mCity.matches()) {
                address.setCity(mCity.group(1).trim());
                foundLabeledField = true;
            } else if (mState.matches()) {
                address.setState(mState.group(1).trim());
                foundLabeledField = true;
            } else if (mPin.matches()) {
                String pinVal = mPin.group(1).trim();
                Matcher subPin = PINCODE_PATTERN.matcher(pinVal);
                if (subPin.find()) {
                    address.setPostalCode(subPin.group(1));
                } else {
                    address.setPostalCode(pinVal);
                }
                foundLabeledField = true;
            } else if (mEmail.matches()) {
                String emailVal = mEmail.group(1).trim();
                Matcher subEmail = EMAIL_PATTERN.matcher(emailVal);
                if (subEmail.find()) {
                    address.setCustomerEmail(subEmail.group().toLowerCase());
                }
                foundLabeledField = true;
            } else {
                // Not labeled - collect in addressBuilder if not already an email or pin alone
                String clean = trimmedLine;
                if (address.getCustomerEmail() != null) {
                    clean = clean.replace(address.getCustomerEmail(), "");
                }
                if (address.getPostalCode() != null) {
                    clean = clean.replace(address.getPostalCode(), "");
                }
                clean = clean.replaceAll("(?i)\\b(my|email|e-mail|mail|is|id|here|please|send|to|the|at)\\b", "").trim();
                if (!clean.isBlank() && clean.length() >= 4) {
                    if (addressBuilder.length() > 0) addressBuilder.append(", ");
                    addressBuilder.append(clean);
                }
            }
        }


        // Fallback customer name if none found in text
        if (address.getShippingName() == null || address.getShippingName().isBlank()) {
            address.setShippingName(fallbackCustomerName != null ? fallbackCustomerName.trim() : "Valued Customer");
        }

        // Free-form extraction fallback if labels were not used
        if (address.getAddressLine1() == null || address.getAddressLine1().isBlank()) {
            String remaining = text;
            if (address.getCustomerEmail() != null) {
                remaining = remaining.replace(address.getCustomerEmail(), "");
            }
            if (address.getPostalCode() != null) {
                remaining = remaining.replace(address.getPostalCode(), "");
            }
            remaining = remaining.replaceAll("(?i)\\b(my|email|e-mail|mail|is|id|here|please|send|to|the|at|pin|pincode|postal|zip|zipcode|address|add|city|state)\\b", "")
                    .replaceAll("[:=-]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();

            if (!remaining.isBlank() && remaining.length() >= 4) {
                // Try to split comma-separated tokens
                String[] tokens = remaining.split("[,\\n]+");
                List<String> validTokens = new ArrayList<>();
                for (String t : tokens) {
                    String clr = t.trim();
                    if (!clr.isBlank() && clr.length() > 1) {
                        validTokens.add(clr);
                    }
                }

                if (!validTokens.isEmpty()) {
                    if (validTokens.size() == 1) {
                        address.setAddressLine1(validTokens.get(0));
                    } else if (validTokens.size() == 2) {
                        address.setAddressLine1(validTokens.get(0));
                        address.setCity(validTokens.get(1));
                    } else {
                        address.setAddressLine1(validTokens.get(0));
                        address.setAddressLine2(validTokens.get(1));
                        address.setCity(validTokens.get(validTokens.size() - 1));
                    }
                }
            } else if (addressBuilder.length() > 0) {
                String built = addressBuilder.toString().replaceAll("(?i)\\b(my|email|e-mail|mail|is|here)\\b", "").trim();
                if (!built.isBlank() && built.length() >= 4) {
                    address.setAddressLine1(built);
                }
            }
        }


        // Build full formatted address string
        StringBuilder full = new StringBuilder();
        if (address.getAddressLine1() != null) full.append(address.getAddressLine1());
        if (address.getAddressLine2() != null && !address.getAddressLine2().isBlank()) {
            if (full.length() > 0) full.append(", ");
            full.append(address.getAddressLine2());
        }
        if (address.getCity() != null && !address.getCity().isBlank()) {
            if (full.length() > 0) full.append(", ");
            full.append(address.getCity());
        }
        if (address.getState() != null && !address.getState().isBlank()) {
            if (full.length() > 0) full.append(", ");
            full.append(address.getState());
        }
        if (address.getPostalCode() != null && !address.getPostalCode().isBlank()) {
            if (full.length() > 0) full.append(" - ");
            full.append(address.getPostalCode());
        }
        address.setFullFormattedAddress(full.toString());

        // Completeness & Validation Checks
        boolean hasEmail = address.getCustomerEmail() != null && !address.getCustomerEmail().isBlank();
        boolean hasAddressText = address.getAddressLine1() != null && address.getAddressLine1().trim().length() >= 5;
        boolean hasPostalCode = address.getPostalCode() != null && !address.getPostalCode().isBlank();
        boolean hasCompleteAddress = hasAddressText && hasPostalCode;

        List<String> missing = new ArrayList<>();
        if (!hasEmail) {
            missing.add("Email address (e.g. name@example.com)");
        }
        if (!hasAddressText) {
            missing.add("Street / House address");
        }
        if (!hasPostalCode) {
            missing.add("6-digit Pincode");
        }

        address.setMissingFields(missing);
        address.setHasOnlyEmail(hasEmail && !hasAddressText && !hasPostalCode);
        address.setHasOnlyAddress(!hasEmail && (hasAddressText || hasPostalCode));
        address.setValid(hasEmail && hasCompleteAddress);

        return address;
    }
}
