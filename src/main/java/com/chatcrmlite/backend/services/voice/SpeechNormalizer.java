package com.chatcrmlite.backend.services.voice;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enterprise Speech Normalizer for Conversational Voice Assistants.
 * Normalizes CRM text, RAG output, currency, URLs, phone numbers,
 * markdown, HTML, and emojis into natural human speech phrasing.
 */
@Component
public class SpeechNormalizer {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`([^`]+)`");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern.compile("\\[([^\\]]+)\\]\\([^)]+\\)");
    private static final Pattern RAW_URL_PATTERN = Pattern.compile("https?://(?:www\\.)?([^/\\s]+)(?:/(\\S*))?");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})");
    private static final Pattern RUPEE_PATTERN = Pattern.compile("(?:₹|Rs\\.?\\s*)(\\d+(?:,\\d+)*(?:\\.\\d+)?)");
    private static final Pattern DOLLAR_PATTERN = Pattern.compile("\\$(\\d+(?:,\\d+)*(?:\\.\\d+)?)");
    private static final Pattern EURO_PATTERN = Pattern.compile("(?:€|EUR\\s*)(\\d+(?:,\\d+)*(?:\\.\\d+)?)");
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?:\\+91[-.\\s]?)?([6-9]\\d{9})");
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?m)^#{1,6}\\s+");
    private static final Pattern BOLD_ITALIC_PATTERN = Pattern.compile("[*_]{1,3}(.*?)[*_]{1,3}");
    private static final Pattern STRIKETHROUGH_PATTERN = Pattern.compile("~~(.*?)~~");
    private static final Pattern BLOCKQUOTE_PATTERN = Pattern.compile("(?m)^>\\s*");
    private static final Pattern BULLET_PATTERN = Pattern.compile("(?m)^\\s*[*\\-+]\\s+");
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile("(?m)^\\s*\\d+\\.\\s+");
    private static final Pattern TABLE_PIPE_PATTERN = Pattern.compile("\\|");
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\p{So}\\p{Cn}\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F700}-\\x{1F77F}\\x{1F780}-\\x{1F7FF}\\x{1F800}-\\x{1F8FF}\\x{1F900}-\\x{1F9FF}\\x{1FA00}-\\x{1FA6F}\\x{1FA70}-\\x{1FAFF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]");
    private static final Pattern DANGLING_SYMBOLS = Pattern.compile("[*~_#^<>\\\\\\[\\]{}]");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("(?<![.?!])\\n+");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * Normalizes text into clean, speakable text optimized for speech synthesis.
     *
     * @param text Raw LLM or CRM text
     * @return Natural, speakable prose
     */
    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text;

        // 1. Remove code blocks
        cleaned = CODE_BLOCK_PATTERN.matcher(cleaned).replaceAll(" The code details are shown in the chat window. ");

        // 2. Remove HTML tags
        cleaned = HTML_TAG_PATTERN.matcher(cleaned).replaceAll(" ");

        // 3. Normalize inline code
        cleaned = INLINE_CODE_PATTERN.matcher(cleaned).replaceAll("$1");

        // 4. Normalize Markdown links: [Link Text](url) -> Link Text
        cleaned = MARKDOWN_LINK_PATTERN.matcher(cleaned).replaceAll("$1");

        // 5. Normalize raw URLs: https://gyanvaniai.online/contact -> our website at gyan vani a i online contact
        Matcher urlMatcher = RAW_URL_PATTERN.matcher(cleaned);
        StringBuffer urlBuffer = new StringBuffer();
        while (urlMatcher.find()) {
            String domain = urlMatcher.group(1).replace(".", " dot ");
            String path = urlMatcher.group(2) != null ? (" " + urlMatcher.group(2).replace("/", " ")) : "";
            urlMatcher.appendReplacement(urlBuffer, "our website at " + domain + path);
        }
        urlMatcher.appendTail(urlBuffer);
        cleaned = urlBuffer.toString();

        // 6. Normalize emails: support@domain.com -> support at domain dot com
        cleaned = EMAIL_PATTERN.matcher(cleaned).replaceAll("$1 at $2");

        // 7. Normalize Currencies
        // Indian Rupee: ₹1,299 or Rs. 1,299 -> 1,299 rupees
        cleaned = RUPEE_PATTERN.matcher(cleaned).replaceAll("$1 rupees");
        // Dollar: $49.99 -> 49 dollars
        cleaned = DOLLAR_PATTERN.matcher(cleaned).replaceAll("$1 dollars");
        // Euro: €50 -> 50 euros
        cleaned = EURO_PATTERN.matcher(cleaned).replaceAll("$1 euros");

        // 8. Normalize phone numbers into digit-spaced cadence
        Matcher phoneMatcher = PHONE_PATTERN.matcher(cleaned);
        StringBuffer phoneBuffer = new StringBuffer();
        while (phoneMatcher.find()) {
            String digits = phoneMatcher.group(1);
            String spaced = String.join(" ", digits.split(""));
            phoneMatcher.appendReplacement(phoneBuffer, spaced);
        }
        phoneMatcher.appendTail(phoneBuffer);
        cleaned = phoneBuffer.toString();

        // 9. Clean markdown formatting
        cleaned = HEADER_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = BOLD_ITALIC_PATTERN.matcher(cleaned).replaceAll("$1");
        cleaned = STRIKETHROUGH_PATTERN.matcher(cleaned).replaceAll("$1");
        cleaned = BLOCKQUOTE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = BULLET_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = NUMBERED_LIST_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = TABLE_PIPE_PATTERN.matcher(cleaned).replaceAll(" ");

        // 10. Strip emojis
        cleaned = EMOJI_PATTERN.matcher(cleaned).replaceAll("");

        // 11. Remove dangling formatting symbols
        cleaned = DANGLING_SYMBOLS.matcher(cleaned).replaceAll(" ");

        // 12. Normalize newlines to sentence pauses (. )
        cleaned = MULTI_NEWLINE_PATTERN.matcher(cleaned).replaceAll(". ");
        cleaned = cleaned.replace('\n', ' ');

        // 13. Collapse multiple spaces
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();

        return cleaned;
    }
}
