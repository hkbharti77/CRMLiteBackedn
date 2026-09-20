package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "commerce_products", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"catalog_id", "product_retailer_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommerceProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "catalog_id", nullable = false)
    private CommerceCatalog catalog;

    @Column(name = "product_retailer_id", nullable = false)
    private String productRetailerId;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(nullable = false, length = 10)
    private String currency;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "category")
    private String category;

    @Column(name = "availability")
    private String availability;

    @Column(name = "product_condition")
    private String condition;

    @Column(name = "url")
    private String url;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "meta_product_id")
    private String metaProductId;

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
