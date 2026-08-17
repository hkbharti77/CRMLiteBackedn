package com.chatcrmlite.backend.models.entitlements;

import java.io.Serializable;
import java.util.List;

public record EntitlementDefinition(
    String key,
    EntitlementType type,
    String label,
    String category,
    String description,
    List<String> dependencies,
    EntitlementMutability mutability
) implements Serializable {}
