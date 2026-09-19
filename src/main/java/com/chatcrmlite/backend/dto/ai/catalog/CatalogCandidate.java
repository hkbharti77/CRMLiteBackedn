package com.chatcrmlite.backend.dto.ai.catalog;

import java.util.UUID;

public record CatalogCandidate(
        UUID catalogId,
        String title,
        String description,
        String aiTriggerInstruction,
        double relevanceScore
) {}
