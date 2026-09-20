package com.chatcrmlite.backend.dto.catalog;

public record CatalogVerificationResult(
    boolean accessible,
    String catalogId,
    String catalogName,
    String failureReason,
    String fbtraceId
) {}
