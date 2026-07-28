package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "subscription_plans")
public class SubscriptionPlan implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    @Column(length = 50)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "price_monthly", nullable = false)
    private BigDecimal priceMonthly;

    @Column(name = "price_yearly", nullable = false)
    private BigDecimal priceYearly;

    @Column(name = "price_monthly_inr")
    private BigDecimal priceMonthlyInr;

    @Column(name = "price_yearly_inr")
    private BigDecimal priceYearlyInr;

    @Column(name = "price_monthly_usd")
    private BigDecimal priceMonthlyUsd;

    @Column(name = "price_yearly_usd")
    private BigDecimal priceYearlyUsd;

    @Column(name = "employee_limit", nullable = false)
    private int employeeLimit;

    @Column(name = "primary_resource_limit", nullable = false)
    private int primaryResourceLimit;

    @Column(name = "secondary_resource_limit", nullable = false)
    private int secondaryResourceLimit;

    @Column(name = "ticket_limit", nullable = false)
    private int ticketLimit;

    @Column(name = "email_limit", nullable = false)
    private int emailLimit;

    @Column(name = "has_whatsapp", nullable = false)
    private boolean hasWhatsapp = false;

    @Column(name = "has_custom_widget", nullable = false)
    private boolean hasCustomWidget = false;

    @Column(name = "has_rag_llm", nullable = false)
    private boolean hasRagLlm = true;

    @Column(name = "is_contact_us", nullable = false)
    private boolean isContactUs = false;

    public SubscriptionPlan() {}

    public SubscriptionPlan(String id, String name, BigDecimal priceMonthly, BigDecimal priceYearly, int employeeLimit, int primaryResourceLimit, int secondaryResourceLimit, int ticketLimit, int emailLimit, boolean hasWhatsapp, boolean hasCustomWidget, boolean hasRagLlm) {
        this.id = id;
        this.name = name;
        this.priceMonthly = priceMonthly;
        this.priceYearly = priceYearly;
        this.priceMonthlyUsd = priceMonthly;
        this.priceYearlyUsd = priceYearly;
        this.employeeLimit = employeeLimit;
        this.primaryResourceLimit = primaryResourceLimit;
        this.secondaryResourceLimit = secondaryResourceLimit;
        this.ticketLimit = ticketLimit;
        this.emailLimit = emailLimit;
        this.hasWhatsapp = hasWhatsapp;
        this.hasCustomWidget = hasCustomWidget;
        this.hasRagLlm = hasRagLlm;
        this.isContactUs = "ENTERPRISE".equalsIgnoreCase(id);
    }

    public SubscriptionPlan(String id, String name, BigDecimal priceMonthlyInr, BigDecimal priceYearlyInr, BigDecimal priceMonthlyUsd, BigDecimal priceYearlyUsd, int employeeLimit, int primaryResourceLimit, int secondaryResourceLimit, int ticketLimit, int emailLimit, boolean hasWhatsapp, boolean hasCustomWidget, boolean hasRagLlm) {
        this.id = id;
        this.name = name;
        this.priceMonthlyInr = priceMonthlyInr;
        this.priceYearlyInr = priceYearlyInr;
        this.priceMonthlyUsd = priceMonthlyUsd;
        this.priceYearlyUsd = priceYearlyUsd;
        this.priceMonthly = priceMonthlyUsd != null ? priceMonthlyUsd : priceMonthlyInr;
        this.priceYearly = priceYearlyUsd != null ? priceYearlyUsd : priceYearlyInr;
        this.employeeLimit = employeeLimit;
        this.primaryResourceLimit = primaryResourceLimit;
        this.secondaryResourceLimit = secondaryResourceLimit;
        this.ticketLimit = ticketLimit;
        this.emailLimit = emailLimit;
        this.hasWhatsapp = hasWhatsapp;
        this.hasCustomWidget = hasCustomWidget;
        this.hasRagLlm = hasRagLlm;
        this.isContactUs = "ENTERPRISE".equalsIgnoreCase(id);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public BigDecimal getPriceMonthly() { return priceMonthly != null ? priceMonthly : (priceMonthlyUsd != null ? priceMonthlyUsd : priceMonthlyInr); }
    public void setPriceMonthly(BigDecimal priceMonthly) { this.priceMonthly = priceMonthly; }

    public BigDecimal getPriceYearly() { return priceYearly != null ? priceYearly : (priceYearlyUsd != null ? priceYearlyUsd : priceYearlyInr); }
    public void setPriceYearly(BigDecimal priceYearly) { this.priceYearly = priceYearly; }

    public BigDecimal getPriceMonthlyInr() { return priceMonthlyInr; }
    public void setPriceMonthlyInr(BigDecimal priceMonthlyInr) { this.priceMonthlyInr = priceMonthlyInr; }

    public BigDecimal getPriceYearlyInr() { return priceYearlyInr; }
    public void setPriceYearlyInr(BigDecimal priceYearlyInr) { this.priceYearlyInr = priceYearlyInr; }

    public BigDecimal getPriceMonthlyUsd() { return priceMonthlyUsd; }
    public void setPriceMonthlyUsd(BigDecimal priceMonthlyUsd) { this.priceMonthlyUsd = priceMonthlyUsd; }

    public BigDecimal getPriceYearlyUsd() { return priceYearlyUsd; }
    public void setPriceYearlyUsd(BigDecimal priceYearlyUsd) { this.priceYearlyUsd = priceYearlyUsd; }

    public int getEmployeeLimit() { return employeeLimit; }
    public void setEmployeeLimit(int employeeLimit) { this.employeeLimit = employeeLimit; }

    public int getPrimaryResourceLimit() { return primaryResourceLimit; }
    public void setPrimaryResourceLimit(int primaryResourceLimit) { this.primaryResourceLimit = primaryResourceLimit; }

    public int getSecondaryResourceLimit() { return secondaryResourceLimit; }
    public void setSecondaryResourceLimit(int secondaryResourceLimit) { this.secondaryResourceLimit = secondaryResourceLimit; }

    public int getTicketLimit() { return ticketLimit; }
    public void setTicketLimit(int ticketLimit) { this.ticketLimit = ticketLimit; }

    public int getEmailLimit() { return emailLimit; }
    public void setEmailLimit(int emailLimit) { this.emailLimit = emailLimit; }

    public boolean isHasWhatsapp() { return hasWhatsapp; }
    public void setHasWhatsapp(boolean hasWhatsapp) { this.hasWhatsapp = hasWhatsapp; }

    public boolean isHasCustomWidget() { return hasCustomWidget; }
    public void setHasCustomWidget(boolean hasCustomWidget) { this.hasCustomWidget = hasCustomWidget; }

    public boolean isHasRagLlm() { return hasRagLlm; }
    public void setHasRagLlm(boolean hasRagLlm) { this.hasRagLlm = hasRagLlm; }

    public boolean isContactUs() { return isContactUs; }
    public void setContactUs(boolean contactUs) { this.isContactUs = contactUs; }

    public static SubscriptionPlanBuilder builder() {
        return new SubscriptionPlanBuilder();
    }

    public static class SubscriptionPlanBuilder {
        private String id;
        private String name;
        private BigDecimal priceMonthly;
        private BigDecimal priceYearly;
        private BigDecimal priceMonthlyInr;
        private BigDecimal priceYearlyInr;
        private BigDecimal priceMonthlyUsd;
        private BigDecimal priceYearlyUsd;
        private int employeeLimit;
        private int primaryResourceLimit;
        private int secondaryResourceLimit;
        private int ticketLimit;
        private int emailLimit;
        private boolean hasWhatsapp;
        private boolean hasCustomWidget;
        private boolean hasRagLlm = true;

        public SubscriptionPlanBuilder id(String id) { this.id = id; return this; }
        public SubscriptionPlanBuilder name(String name) { this.name = name; return this; }
        public SubscriptionPlanBuilder priceMonthly(BigDecimal priceMonthly) { this.priceMonthly = priceMonthly; return this; }
        public SubscriptionPlanBuilder priceYearly(BigDecimal priceYearly) { this.priceYearly = priceYearly; return this; }
        public SubscriptionPlanBuilder priceMonthlyInr(BigDecimal priceMonthlyInr) { this.priceMonthlyInr = priceMonthlyInr; return this; }
        public SubscriptionPlanBuilder priceYearlyInr(BigDecimal priceYearlyInr) { this.priceYearlyInr = priceYearlyInr; return this; }
        public SubscriptionPlanBuilder priceMonthlyUsd(BigDecimal priceMonthlyUsd) { this.priceMonthlyUsd = priceMonthlyUsd; return this; }
        public SubscriptionPlanBuilder priceYearlyUsd(BigDecimal priceYearlyUsd) { this.priceYearlyUsd = priceYearlyUsd; return this; }
        public SubscriptionPlanBuilder employeeLimit(int employeeLimit) { this.employeeLimit = employeeLimit; return this; }
        public SubscriptionPlanBuilder primaryResourceLimit(int primaryResourceLimit) { this.primaryResourceLimit = primaryResourceLimit; return this; }
        public SubscriptionPlanBuilder secondaryResourceLimit(int secondaryResourceLimit) { this.secondaryResourceLimit = secondaryResourceLimit; return this; }
        public SubscriptionPlanBuilder ticketLimit(int ticketLimit) { this.ticketLimit = ticketLimit; return this; }
        public SubscriptionPlanBuilder emailLimit(int emailLimit) { this.emailLimit = emailLimit; return this; }
        public SubscriptionPlanBuilder hasWhatsapp(boolean hasWhatsapp) { this.hasWhatsapp = hasWhatsapp; return this; }
        public SubscriptionPlanBuilder hasCustomWidget(boolean hasCustomWidget) { this.hasCustomWidget = hasCustomWidget; return this; }
        public SubscriptionPlanBuilder hasRagLlm(boolean hasRagLlm) { this.hasRagLlm = hasRagLlm; return this; }

        public SubscriptionPlan build() {
            SubscriptionPlan plan = new SubscriptionPlan(id, name, priceMonthlyInr, priceYearlyInr, priceMonthlyUsd, priceYearlyUsd, employeeLimit, primaryResourceLimit, secondaryResourceLimit, ticketLimit, emailLimit, hasWhatsapp, hasCustomWidget, hasRagLlm);
            if (priceMonthly != null) plan.setPriceMonthly(priceMonthly);
            if (priceYearly != null) plan.setPriceYearly(priceYearly);
            return plan;
        }
    }
}
