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

    /**
     * Splits full conversational text into discrete natural sentences for progressive TTS pipelining.
     */
    public static List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        String clean = text.trim();
        // Responses <= 80 characters (typical voice greetings and confirmations) are best synthesized as a single unit
        // to avoid high-latency multi-request roundtrips and RTP queue under-runs.
        if (clean.length() <= 80) {
            return List.of(clean);
        }
        SentenceChunker chunker = new SentenceChunker();
        List<String> rawChunks = new ArrayList<>(chunker.accept(clean));
        rawChunks.addAll(chunker.flush());

        // Merge fragments shorter than 30 characters (e.g. "Hello!", "Sure.", "Yes.") with the next chunk
        List<String> merged = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String chunk : rawChunks) {
            if (cur.length() > 0) {
                cur.append(" ");
            }
            cur.append(chunk);
            if (cur.length() >= 30) {
                merged.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            if (!merged.isEmpty() && cur.length() < 25) {
                int lastIdx = merged.size() - 1;
                merged.set(lastIdx, merged.get(lastIdx) + " " + cur.toString());
            } else {
                merged.add(cur.toString());
            }
        }
        return merged.isEmpty() ? List.of(clean) : merged;
    }

    /**
     * Cleans raw LLM markdown and formatting so voice synthesis sounds natural and conversational.
     */
    public static String cleanForVoice(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("\\*\\*(.*?)\\*\\*", "$1")
                   .replaceAll("\\*(.*?)\\*", "$1")
                   .replaceAll("(?m)^\\s*[-*•]\\s+", "")
                   .replaceAll("(?m)^\\s*#+\\s+", "")
                   .replaceAll("https?://\\S+", "the link")
                   .replaceAll("[\\p{So}\\p{Cn}]", "")
                   .replaceAll("\\s+", " ")
                   .trim();
    }
}
