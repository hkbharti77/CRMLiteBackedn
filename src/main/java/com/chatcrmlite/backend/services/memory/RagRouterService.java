package com.chatcrmlite.backend.services.memory;

import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 4-Stage Ordered RAG Classification Service.
 * Determines if a query requires Retrieval-Augmented Generation based on strict ordering.
 */
@Service
public class RagRouterService {

    // Stage 1: Pure Acknowledgement (Strict full-match or very short phrases)
    private static final List<String> ACK_PHRASES = Arrays.asList(
            "ok", "okay", "thanks", "thank you", "cool", "got it", "yes", "no", "yep", "nope", 
            "sure", "great", "awesome", "hello", "hi", "hey"
    );

    // Stage 2: Explicit Factual / Business Query (Keywords indicating intent for business facts)
    private static final List<String> FACTUAL_KEYWORDS = Arrays.asList(
            "price", "cost", "fee", "refund", "policy", "hours", "timing", "open", "close",
            "address", "location", "implant", "dental", "premium", "standard", "offer",
            "discount", "service", "appointment", "book", "schedule", "dr", "doctor"
    );

    // Stage 3: Context-Dependent Query (Pronouns or references to previous context)
    private static final List<String> CONTEXT_KEYWORDS = Arrays.asList(
            "it", "that", "this", "they", "them", "he", "she", 
            "second", "third", "another", "more", "explain", "which", "what one"
    );

    public boolean requiresRag(String query) {
        if (query == null || query.trim().isEmpty()) {
            return false;
        }

        String normalizedQuery = query.toLowerCase().trim()
                .replaceAll("[^a-z0-9\\s]", ""); // Remove punctuation

        // 1. Pure Acknowledgement? -> NO_RAG
        if (ACK_PHRASES.contains(normalizedQuery) || normalizedQuery.length() < 3) {
            return false;
        }

        // 2. Explicit factual / business query? -> RAG_REQUIRED
        if (containsAnyWord(normalizedQuery, FACTUAL_KEYWORDS)) {
            return true;
        }

        // 3. Context-dependent query? -> RAG_LIKELY
        // RAG_LIKELY still translates to true for the retriever, but logically
        // it means we rely heavily on the ConversationContext to resolve the reference.
        if (containsAnyWord(normalizedQuery, CONTEXT_KEYWORDS)) {
            return true;
        }

        // 4. Everything else? -> RAG_REQUIRED (Conservative Default)
        return true; 
    }

    private boolean containsAnyWord(String text, List<String> words) {
        String[] tokens = text.split("\\s+");
        for (String token : tokens) {
            if (words.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
