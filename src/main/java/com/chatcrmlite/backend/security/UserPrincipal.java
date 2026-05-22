package com.chatcrmlite.backend.security;

import java.security.Principal;
import java.util.UUID;

public class UserPrincipal implements Principal {
    private final String name; // email
    private final UUID tenantId;

    public UserPrincipal(String name, UUID tenantId) {
        this.name = name;
        this.tenantId = tenantId;
    }

    @Override
    public String getName() {
        return name;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
