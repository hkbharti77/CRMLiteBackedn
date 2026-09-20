package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "commerce_orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "whatsapp_message_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommerceOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "waba_id", nullable = false)
    private String wabaId;

    @Column(name = "phone_number_id", nullable = false)
    private String phoneNumberId;

    @Column(name = "catalog_id", nullable = false)
    private String catalogId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "conversation_id")
    private UUID conversationId;

    @Column(name = "whatsapp_message_id", nullable = false)
    private String whatsappMessageId;

    @Column(name = "customer_wa_id", nullable = false)
    private String customerWaId;

    @Column(name = "customer_phone")
    private String customerPhone;

    @Column(name = "customer_name")
    private String customerName;

    @Column(name = "customer_note", columnDefinition = "TEXT")
    private String customerNote;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal tax = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal shipping = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal total = BigDecimal.ZERO;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = "RECEIVED";

    @Column(name = "checkout_status", nullable = false, length = 50)
    @Builder.Default
    private String checkoutStatus = "AWAITING_CUSTOMER_DETAILS";

    @Column(name = "payment_status", nullable = false, length = 40)
    @Builder.Default
    private String paymentStatus = "UNPAID";

    @Column(name = "online_payment_enabled_at_order", nullable = false)
    @Builder.Default
    private boolean onlinePaymentEnabledAtOrder = true;

    @Column(name = "cod_enabled_at_order", nullable = false)
    @Builder.Default
    private boolean codEnabledAtOrder = false;

    @Column(name = "shipping_name")
    private String shippingName;

    @Column(name = "address_line1")
    private String addressLine1;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "state", length = 100)
    private String state;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(name = "country", length = 10)
    @Builder.Default
    private String country = "IN";

    @Column(name = "shipping_address", columnDefinition = "TEXT")
    private String shippingAddress;

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "payment_method", length = 30)
    private String paymentMethod; // e.g. ONLINE, COD

    @Column(name = "payment_provider", length = 50)
    private String paymentProvider; // e.g. RAZORPAY_DIRECT

    @Column(name = "payment_link_id", length = 100)
    private String paymentLinkId;

    @Column(name = "payment_link_url", columnDefinition = "TEXT")
    private String paymentLinkUrl;

    @Column(name = "payment_reference_id", length = 100)
    private String paymentReferenceId;

    @Column(name = "payment_amount", precision = 12, scale = 2)
    private BigDecimal paymentAmount;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<CommerceOrderItem> items = new ArrayList<>();

    @Builder.Default
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Builder.Default
    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
