package com.chatcrmlite.backend.services.rag;

/**
 * Feature-flag modes for Hybrid Graph RAG.
 */
public enum RagMode {
    VECTOR,
    GRAPH,
    HYBRID;

    public static RagMode from(String value) {
        if (value == null || value.isBlank()) {
            return VECTOR;
        }
        try {
            return RagMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return VECTOR;
        }
    }
}
