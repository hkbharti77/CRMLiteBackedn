package com.chatcrmlite.backend.event;

import org.springframework.context.ApplicationEvent;
import java.util.UUID;

public class TenantSubscriptionUpdatedEvent extends ApplicationEvent {
    private final UUID tenantId;

    public TenantSubscriptionUpdatedEvent(Object source, UUID tenantId) {
        super(source);
        this.tenantId = tenantId;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
