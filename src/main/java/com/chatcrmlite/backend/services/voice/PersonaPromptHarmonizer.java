package com.chatcrmlite.backend.services.voice;

import java.util.regex.Pattern;

/**
 * Enterprise Persona Prompt Harmonizer.
 * Enforces dynamic identity injection, resolving template placeholders and
 * shielding against hardcoded persona name mismatches in voice sessions.
 */
public final class PersonaPromptHarmonizer {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "\\{\\{\\s*(?:assistant_name|assistantName|name)\\s*\\}\\}|\\{\\s*(?:assistant_name|assistantName|name)\\s*\\}|\\[Assistant Name\\]",
            Pattern.CASE_INSENSITIVE
    );

    // Regex to match "I'm Priya", "I am Priya", "my name is Priya", "You are Priya", etc.
    // across default / legacy persona names (Priya, Simran, Riya, Ananya, Aryan, Rahul, Rohan, Amit, Assistant)
    private static final Pattern LEGACY_NAME_CONFLICT_PATTERN = Pattern.compile(
            "\\b(I['’]m|I\\s+am|my\\s+name\\s+is|myself|this\\s+is)\\s+(?:Priya|Simran|Riya|Ananya|Aryan|Rahul|Rohan|Amit|Assistant)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern YOU_ARE_NAME_PATTERN = Pattern.compile(
            "\\b(You\\s+are|You['’]re)\\s+(?:Priya|Simran|Riya|Ananya|Aryan|Rahul|Rohan|Amit|Assistant)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private PersonaPromptHarmonizer() {}

    /**
     * Harmonizes persona prompt with active assistant name and business name.
     *
     * @param rawPrompt The configured prompt
     * @param assistantName The active assistant name (e.g., Simran, Rahul, Priya)
     * @param businessName The optional business brand name
     * @return Clean, harmonized prompt guaranteed to align with the active voice persona
     */
    public static String harmonize(String rawPrompt, String assistantName, String businessName) {
        if (assistantName == null || assistantName.isBlank()) {
            assistantName = "Assistant";
        }
        String cleanAssistantName = assistantName.trim();
        if (Character.isLowerCase(cleanAssistantName.charAt(0))) {
            cleanAssistantName = Character.toUpperCase(cleanAssistantName.charAt(0)) + cleanAssistantName.substring(1);
        }

        if (rawPrompt == null || rawPrompt.isBlank()) {
            return "You are " + cleanAssistantName + ", a helpful, polite, and professional AI voice assistant"
                    + (businessName != null && !businessName.isBlank() ? " for " + businessName : "") + ". "
                    + "Keep spoken answers brief, natural, and under 35 words.";
        }

        String harmonized = rawPrompt;

        // 1. Replace explicit template placeholders
        harmonized = PLACEHOLDER_PATTERN.matcher(harmonized).replaceAll(cleanAssistantName);

        // 2. Harmonize legacy greetings ("I'm Priya" -> "I'm Simran")
        harmonized = LEGACY_NAME_CONFLICT_PATTERN.matcher(harmonized).replaceAll("$1 " + cleanAssistantName);

        // 3. Harmonize role definition ("You are Priya" -> "You are Simran")
        harmonized = YOU_ARE_NAME_PATTERN.matcher(harmonized).replaceAll("$1 " + cleanAssistantName);

        return harmonized;
    }
}
