package com.chatcrmlite.backend.models.payments;

import com.chatcrmlite.backend.models.BaseTenantEntity;
import com.chatcrmlite.backend.models.enums.PaymentIntegrationType;
import com.chatcrmlite.backend.models.enums.PaymentMode;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderFulfillmentStatus;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderPaymentStatus;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_orders", uniqueConstraints = {
    @UniqueConstraint(name = "uk_tenant_order_ref", columnNames = {"tenant_id", "reference_id"}),
    @UniqueConstraint(name = "uk_orders_composite", columnNames = {"id", "tenant_id"})
}, indexes = {
    @Index(name = "idx_orders_tenant_ref", columnList = "tenant_id, reference_id"),
    @Index(name = "idx_orders_tenant_wa", columnList = "tenant_id, customer_wa_id"),
    @Index(name = "idx_orders_tenant_status", columnList = "tenant_id, payment_status, order_status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class WhatsAppOrder extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reference_id", nullable = false, length = 100)
    private String referenceId;

    @Column(name = "external_reference_id", length = 100)
    private String externalReferenceId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "customer_wa_id", nullable = false, length = 50)
    private String customerWaId;

    @Column(name = "customer_name", length = 150)
    private String customerName;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "subtotal_minor", nullable = false)
    private Long subtotalMinor;

    @Column(name = "discount_minor", nullable = false)
    @Builder.Default
    private Long discountMinor = 0L;

    @Column(name = "tax_minor", nullable = false)
    @Builder.Default
    private Long taxMinor = 0L;

    @Column(name = "shipping_minor", nullable = false)
    @Builder.Default
    private Long shippingMinor = 0L;

    @Column(name = "total_minor", nullable = false)
    private Long totalMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 50)
    @Builder.Default
    private WhatsAppOrderStatus orderStatus = WhatsAppOrderStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 50)
    @Builder.Default
    private WhatsAppOrderPaymentStatus paymentStatus = WhatsAppOrderPaymentStatus.CREATED;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", nullable = false, length = 50)
    @Builder.Default
    private WhatsAppOrderFulfillmentStatus fulfillmentStatus = WhatsAppOrderFulfillmentStatus.UNFULFILLED;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_payment_mode", length = 50)
    private PaymentMode preferredPaymentMode;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_payment_provider", length = 50)
    private PaymentIntegrationType preferredPaymentProvider;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<WhatsAppOrderItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PaymentTransaction> transactions = new ArrayList<>();

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expired_at")
    private Instant expiredAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    public void addItem(WhatsAppOrderItem item) {
        items.add(item);
        item.setOrder(this);
        if (this.getTenant() != null) {
            item.setTenant(this.getTenant());
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
