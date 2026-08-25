package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "billing_transactions")
public class BillingTransaction extends BaseTenantEntity {
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    private String currency = "INR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_gateway", nullable = false, length = 50)
    private PaymentGateway paymentGateway = PaymentGateway.RAZORPAY;

    @Column(name = "gateway_transaction_id", nullable = false, length = 100)
    private String gatewayTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_email_status", nullable = false, length = 20)
    private InvoiceEmailStatus invoiceEmailStatus = InvoiceEmailStatus.PENDING;

    @Column(name = "invoice_email_sent_at")
    private LocalDateTime invoiceEmailSentAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public BillingTransaction() {}

    public BillingTransaction(UUID id, BigDecimal amount, String currency, TransactionStatus status, PaymentGateway paymentGateway, String gatewayTransactionId, LocalDateTime createdAt) {
        this.id = id;
        this.amount = amount;
        this.currency = currency != null ? currency : "INR";
        this.status = status != null ? status : TransactionStatus.PENDING;
        this.paymentGateway = paymentGateway != null ? paymentGateway : PaymentGateway.RAZORPAY;
        this.gatewayTransactionId = gatewayTransactionId;
        this.invoiceEmailStatus = InvoiceEmailStatus.PENDING;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    public enum TransactionStatus {
        SUCCESS, FAILED, PENDING
    }

    public enum InvoiceEmailStatus {
        PENDING, SENT, FAILED
    }

    public enum PaymentGateway {
        STRIPE, RAZORPAY
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public PaymentGateway getPaymentGateway() { return paymentGateway; }
    public void setPaymentGateway(PaymentGateway paymentGateway) { this.paymentGateway = paymentGateway; }

    public String getGatewayTransactionId() { return gatewayTransactionId; }
    public void setGatewayTransactionId(String gatewayTransactionId) { this.gatewayTransactionId = gatewayTransactionId; }

    public InvoiceEmailStatus getInvoiceEmailStatus() { return invoiceEmailStatus; }
    public void setInvoiceEmailStatus(InvoiceEmailStatus invoiceEmailStatus) { this.invoiceEmailStatus = invoiceEmailStatus; }

    public LocalDateTime getInvoiceEmailSentAt() { return invoiceEmailSentAt; }
    public void setInvoiceEmailSentAt(LocalDateTime invoiceEmailSentAt) { this.invoiceEmailSentAt = invoiceEmailSentAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static BillingTransactionBuilder builder() {
        return new BillingTransactionBuilder();
    }

    public static class BillingTransactionBuilder {
        private UUID id;
        private BigDecimal amount;
        private String currency = "INR";
        private TransactionStatus status = TransactionStatus.PENDING;
        private PaymentGateway paymentGateway = PaymentGateway.RAZORPAY;
        private String gatewayTransactionId;
        private InvoiceEmailStatus invoiceEmailStatus = InvoiceEmailStatus.PENDING;
        private LocalDateTime invoiceEmailSentAt;
        private LocalDateTime createdAt = LocalDateTime.now();
        private Tenant tenant;

        public BillingTransactionBuilder id(UUID id) { this.id = id; return this; }
        public BillingTransactionBuilder amount(BigDecimal amount) { this.amount = amount; return this; }
        public BillingTransactionBuilder currency(String currency) { this.currency = currency; return this; }
        public BillingTransactionBuilder status(TransactionStatus status) { this.status = status; return this; }
        public BillingTransactionBuilder paymentGateway(PaymentGateway paymentGateway) { this.paymentGateway = paymentGateway; return this; }
        public BillingTransactionBuilder gatewayTransactionId(String gatewayTransactionId) { this.gatewayTransactionId = gatewayTransactionId; return this; }
        public BillingTransactionBuilder invoiceEmailStatus(InvoiceEmailStatus invoiceEmailStatus) { this.invoiceEmailStatus = invoiceEmailStatus; return this; }
        public BillingTransactionBuilder invoiceEmailSentAt(LocalDateTime invoiceEmailSentAt) { this.invoiceEmailSentAt = invoiceEmailSentAt; return this; }
        public BillingTransactionBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public BillingTransactionBuilder tenant(Tenant tenant) { this.tenant = tenant; return this; }

        public BillingTransaction build() {
            BillingTransaction bt = new BillingTransaction(id, amount, currency, status, paymentGateway, gatewayTransactionId, createdAt);
            if (invoiceEmailStatus != null) bt.setInvoiceEmailStatus(invoiceEmailStatus);
            if (invoiceEmailSentAt != null) bt.setInvoiceEmailSentAt(invoiceEmailSentAt);
            if (tenant != null) {
                bt.setTenant(tenant);
            }
            return bt;
        }
    }
}
