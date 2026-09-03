package com.chatcrmlite.backend.services.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SentenceChunker {

    private StringBuilder buffer = new StringBuilder();

    // Regex to match end of sentence punctuation (. ? ! ।)
    // Negative lookbehind to avoid matching abbreviations like Mr. Dr. Rs.
    private static final Pattern END_OF_SENTENCE = Pattern.compile("(?<!\\bMr)(?<!\\bDr)(?<!\\bRs)(?<!\\b[A-Z])[.?!।]+(?!\\d)");

    public List<String> accept(String token) {
        List<String> chunks = new ArrayList<>();
        if (token == null) return chunks;

        buffer.append(token);
        String currentText = buffer.toString();

        java.util.regex.Matcher matcher = END_OF_SENTENCE.matcher(currentText);
        int lastEnd = 0;

        while (matcher.find()) {
            int splitIndex = matcher.end();
            String chunk = currentText.substring(lastEnd, splitIndex).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            lastEnd = splitIndex;
        }

        if (lastEnd > 0) {
            buffer = new StringBuilder(currentText.substring(lastEnd));
        }

        return chunks;
    }

    public List<String> flush() {
        List<String> chunks = new ArrayList<>();
        String remaining = buffer.toString().trim();
        if (!remaining.isEmpty()) {
            chunks.add(remaining);
        }
        buffer.setLength(0);
        return chunks;
    }
}
