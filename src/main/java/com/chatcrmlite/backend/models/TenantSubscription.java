package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tenant_subscriptions")
public class TenantSubscription extends BaseTenantEntity {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private SubscriptionStatus status = SubscriptionStatus.FREE_TRIAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle", nullable = false, length = 20)
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    @Column(name = "current_period_start", nullable = false)
    private LocalDateTime currentPeriodStart = LocalDateTime.now();

    @Column(name = "current_period_end", nullable = false)
    private LocalDateTime currentPeriodEnd = LocalDateTime.now().plusMonths(1);

    @Column(name = "stripe_subscription_id", length = 100)
    private String stripeSubscriptionId;

    @Column(name = "razorpay_subscription_id", length = 100)
    private String razorpaySubscriptionId;

    public TenantSubscription() {}

    public TenantSubscription(UUID id, SubscriptionPlan plan, SubscriptionStatus status, BillingCycle billingCycle, LocalDateTime currentPeriodStart, LocalDateTime currentPeriodEnd, String stripeSubscriptionId, String razorpaySubscriptionId) {
        this.id = id;
        this.plan = plan;
        this.status = status != null ? status : SubscriptionStatus.FREE_TRIAL;
        this.billingCycle = billingCycle != null ? billingCycle : BillingCycle.MONTHLY;
        this.currentPeriodStart = currentPeriodStart != null ? currentPeriodStart : LocalDateTime.now();
        this.currentPeriodEnd = currentPeriodEnd != null ? currentPeriodEnd : LocalDateTime.now().plusMonths(1);
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.razorpaySubscriptionId = razorpaySubscriptionId;
    }

    public enum SubscriptionStatus {
        ACTIVE, PAST_DUE, CANCELLED, FREE_TRIAL, INACTIVE
    }

    public enum BillingCycle {
        MONTHLY, YEARLY
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public SubscriptionPlan getPlan() { return plan; }
    public void setPlan(SubscriptionPlan plan) { this.plan = plan; }

    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }

    public BillingCycle getBillingCycle() { return billingCycle; }
    public void setBillingCycle(BillingCycle billingCycle) { this.billingCycle = billingCycle; }

    public LocalDateTime getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public LocalDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(LocalDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public String getStripeSubscriptionId() { return stripeSubscriptionId; }
    public void setStripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; }

    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; }

    public static TenantSubscriptionBuilder builder() {
        return new TenantSubscriptionBuilder();
    }

    public static class TenantSubscriptionBuilder {
        private UUID id;
        private SubscriptionPlan plan;
        private SubscriptionStatus status = SubscriptionStatus.FREE_TRIAL;
        private BillingCycle billingCycle = BillingCycle.MONTHLY;
        private LocalDateTime currentPeriodStart = LocalDateTime.now();
        private LocalDateTime currentPeriodEnd = LocalDateTime.now().plusMonths(1);
        private String stripeSubscriptionId;
        private String razorpaySubscriptionId;
        private Tenant tenant;

        public TenantSubscriptionBuilder id(UUID id) { this.id = id; return this; }
        public TenantSubscriptionBuilder plan(SubscriptionPlan plan) { this.plan = plan; return this; }
        public TenantSubscriptionBuilder status(SubscriptionStatus status) { this.status = status; return this; }
        public TenantSubscriptionBuilder billingCycle(BillingCycle billingCycle) { this.billingCycle = billingCycle; return this; }
        public TenantSubscriptionBuilder currentPeriodStart(LocalDateTime currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; return this; }
        public TenantSubscriptionBuilder currentPeriodEnd(LocalDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; return this; }
        public TenantSubscriptionBuilder stripeSubscriptionId(String stripeSubscriptionId) { this.stripeSubscriptionId = stripeSubscriptionId; return this; }
        public TenantSubscriptionBuilder razorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; return this; }
        public TenantSubscriptionBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }

        public TenantSubscription build() {
            TenantSubscription ts = new TenantSubscription(id, plan, status, billingCycle, currentPeriodStart, currentPeriodEnd, stripeSubscriptionId, razorpaySubscriptionId);
            if (tenant != null) {
                ts.setTenant(tenant);
            }
            return ts;
        }
    }
}
