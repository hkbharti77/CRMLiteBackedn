package com.chatcrmlite.backend.models;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "commerce_catalogs", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "meta_catalog_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CommerceCatalog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", nullable = false)
    private Tenant tenant;

    @Column(name = "waba_id", nullable = false)
    private String wabaId;

    @Column(name = "meta_catalog_id", nullable = false)
    private String metaCatalogId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 30)
    private String status; // e.g. ACTIVE, INACTIVE, DISCONNECTED

    @Column(name = "is_visible", nullable = false)
    @Builder.Default
    private boolean isVisible = false;

    @Column(name = "cart_enabled", nullable = false)
    @Builder.Default
    private boolean cartEnabled = false;

    @Column(name = "online_payment_enabled", nullable = false)
    @Builder.Default
    private boolean onlinePaymentEnabled = true;

    @Column(name = "cod_enabled", nullable = false)
    @Builder.Default
    private boolean codEnabled = false;

    @Column(name = "last_synced_at")
    private LocalDateTime lastSyncedAt;

    @Column(name = "last_verified_at")
    private LocalDateTime lastVerifiedAt;

    @Column(name = "sync_status", length = 30)
    private String syncStatus; // e.g. SUCCESS, PENDING, FAILED

    @Column(name = "sync_error", columnDefinition = "TEXT")
    private String syncError;

    @Transient
    private Long productsCount;

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
