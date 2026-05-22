package com.chatcrmlite.backend.governance;

import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ensures user privacy by redacting PII before sending data to cloud AI providers.
 */
@Service
public class PiiScanner {

    private static final Map<String, Pattern> PII_PATTERNS = new HashMap<>();

    static {
        PII_PATTERNS.put("EMAIL", Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}"));
        PII_PATTERNS.put("PHONE", Pattern.compile("\\+?\\d{10,12}"));
        PII_PATTERNS.put("CREDIT_CARD", Pattern.compile("\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}[-\\s]?\\d{4}"));
    }

    /**
     * Redacts PII from the input string and returns the sanitized version.
     */
    public String redact(String input) {
        if (input == null) return null;
        
        String sanitized = input;
        for (Map.Entry<String, Pattern> entry : PII_PATTERNS.entrySet()) {
            Matcher matcher = entry.getValue().matcher(sanitized);
            sanitized = matcher.replaceAll("[REDACTED_" + entry.getKey() + "]");
        }
        return sanitized;
    }

    /**
     * Checks if the input contains any sensitive patterns.
     */
    public boolean containsPii(String input) {
        return PII_PATTERNS.values().stream().anyMatch(p -> p.matcher(input).find());
    }
}
