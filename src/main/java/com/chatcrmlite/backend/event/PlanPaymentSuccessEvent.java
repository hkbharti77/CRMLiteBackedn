package com.chatcrmlite.backend.event;

import org.springframework.context.ApplicationEvent;
import java.util.UUID;

public class PlanPaymentSuccessEvent extends ApplicationEvent {
    private final UUID tenantId;
    private final UUID transactionId;
    private final String userEmail;

    public PlanPaymentSuccessEvent(Object source, UUID tenantId, UUID transactionId, String userEmail) {
        super(source);
        this.tenantId = tenantId;
        this.transactionId = transactionId;
        this.userEmail = userEmail;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public String getUserEmail() {
        return userEmail;
    }
}
