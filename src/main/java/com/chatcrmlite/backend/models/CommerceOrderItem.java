package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "commerce_order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommerceOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnore
    private CommerceOrder order;

    @Column(name = "product_retailer_id", nullable = false)
    private String productRetailerId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "item_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemPrice;

    @Column(nullable = false, length = 10)
    private String currency;
}
