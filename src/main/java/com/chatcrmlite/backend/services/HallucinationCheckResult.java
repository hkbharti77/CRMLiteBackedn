package com.chatcrmlite.backend.services;

/**
 * Result of the HallucinationDetector evaluation.
 */
public enum HallucinationCheckResult {
    GROUNDED,
    GROUNDED_REFUSAL,
    UNSUPPORTED_CLAIM,
    CONTACT_MISMATCH,
    NUMERIC_MISMATCH,
    TEMPORAL_MISMATCH,
    ENTITY_MISMATCH,
    EMPTY_RESPONSE,
    UNCERTAIN
}
