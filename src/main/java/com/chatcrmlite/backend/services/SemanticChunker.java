package com.chatcrmlite.backend.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Semantic chunker that splits text on sentence and paragraph boundaries
 * instead of a naive word-count sliding window.
 *
 * Strategy:
 * 1. Split text into paragraphs (double newline)
 * 2. Within each paragraph, use Java's BreakIterator for sentence boundaries
 * 3. Accumulate sentences until the CHAR_LIMIT is reached, then flush to a chunk
 * 4. Add overlap by repeating the last sentence of the previous chunk
 *
 * Why characters not words?
 *   AllMiniLM-L6-v2 has a 256 WORD-PIECE token limit. At ~4 chars/token,
 *   ~900 chars ≈ 225 tokens — safely within the model's context window.
 *   The previous 550-word limit could produce 700+ tokens, causing silent truncation.
 */
@Slf4j
@Component
public class SemanticChunker {

    // ~900 chars ≈ 225 subword tokens for AllMiniLM-L6-v2 (max 256)
    private static final int CHUNK_CHAR_LIMIT = 900;

    // Overlap: repeat the last sentence of the previous chunk at the start of the next
    private static final int OVERLAP_SENTENCES = 1;

    // Skip chunks shorter than this — likely headers or noise
    private static final int MIN_CHUNK_LENGTH = 40;

    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n{2,}");

        for (String paragraph : paragraphs) {
            paragraph = paragraph.trim();
            if (paragraph.length() < MIN_CHUNK_LENGTH) continue;

            List<String> sentences = splitIntoSentences(paragraph);
            accumulate(sentences, chunks);
        }

        log.debug("[SemanticChunker] Produced {} chunks from {} paragraphs", chunks.size(), paragraphs.length);
        return chunks;
    }

    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        BreakIterator boundary = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        boundary.setText(text);

        int start = boundary.first();
        for (int end = boundary.next(); end != BreakIterator.DONE; start = end, end = boundary.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private void accumulate(List<String> sentences, List<String> outputChunks) {
        StringBuilder current = new StringBuilder();
        String lastSentence = null;

        for (String sentence : sentences) {
            // If this single sentence is already over the limit, add as its own chunk
            if (sentence.length() > CHUNK_CHAR_LIMIT) {
                if (current.length() > 0) {
                    flush(current, outputChunks);
                }
                outputChunks.add(sentence.substring(0, CHUNK_CHAR_LIMIT));
                lastSentence = null;
                continue;
            }

            if (current.length() + sentence.length() > CHUNK_CHAR_LIMIT) {
                flush(current, outputChunks);

                // Overlap: begin next chunk with the previous chunk's last sentence
                if (lastSentence != null) {
                    current.append(lastSentence).append(" ");
                }
            }

            lastSentence = sentence;
            current.append(sentence).append(" ");
        }

        if (current.length() >= MIN_CHUNK_LENGTH) {
            flush(current, outputChunks);
        }
    }

    private void flush(StringBuilder sb, List<String> output) {
        String chunk = sb.toString().trim();
        if (chunk.length() >= MIN_CHUNK_LENGTH) {
            output.add(chunk);
        }
        sb.setLength(0);
    }
}
