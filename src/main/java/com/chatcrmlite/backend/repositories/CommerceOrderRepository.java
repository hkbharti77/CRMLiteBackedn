package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.CommerceOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CommerceOrderRepository extends JpaRepository<CommerceOrder, UUID> {
    Optional<CommerceOrder> findByTenantIdAndWhatsappMessageId(UUID tenantId, String whatsappMessageId);
    List<CommerceOrder> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    org.springframework.data.domain.Page<CommerceOrder> findAllByTenantId(UUID tenantId, org.springframework.data.domain.Pageable pageable);
    Optional<CommerceOrder> findByIdAndTenantId(UUID id, UUID tenantId);
    Optional<CommerceOrder> findByPaymentLinkId(String paymentLinkId);
    Optional<CommerceOrder> findByTenantIdAndPaymentLinkId(UUID tenantId, String paymentLinkId);
    Optional<CommerceOrder> findByPaymentReferenceId(String paymentReferenceId);

    /**
     * Aggregate summary: orderCount + uniqueBuyerCount for a product SKU.
     * Excludes CANCELLED / PAYMENT_FAILED orders. Uses native SQL for efficiency.
     */
    @Query(value = "SELECT COUNT(DISTINCT o.id) AS orderCount, " +
                   "COUNT(DISTINCT o.customer_wa_id) AS uniqueBuyerCount " +
                   "FROM commerce_orders o " +
                   "JOIN commerce_order_items i ON i.order_id = o.id " +
                   "WHERE o.tenant_id = :tenantId " +
                   "AND i.product_retailer_id = :sku " +
                   "AND o.status NOT IN ('CANCELLED', 'PAYMENT_FAILED') " +
                   "AND o.payment_status NOT IN ('CANCELLED', 'PAYMENT_FAILED')",
           nativeQuery = true)
    Map<String, Object> getProductBuyersSummary(@Param("tenantId") UUID tenantId,
                                                @Param("sku") String sku);

    /**
     * Paginated lightweight buyer projection — only 7 fields, newest first.
     * Excludes CANCELLED / PAYMENT_FAILED. Uses DISTINCT to avoid item-join duplicates.
     */
    @Query("SELECT DISTINCT o.id AS orderId, o.customerName AS customerName, " +
           "o.customerWaId AS waId, o.total AS total, " +
           "o.status AS orderStatus, o.paymentStatus AS paymentStatus, " +
           "o.createdAt AS createdAt " +
           "FROM CommerceOrder o JOIN o.items i " +
           "WHERE o.tenant.id = :tenantId AND i.productRetailerId = :sku " +
           "AND o.status NOT IN ('CANCELLED', 'PAYMENT_FAILED') " +
           "AND o.paymentStatus NOT IN ('CANCELLED', 'PAYMENT_FAILED') " +
           "ORDER BY o.createdAt DESC")
    Page<Map<String, Object>> findBuyersByProductSku(@Param("tenantId") UUID tenantId,
                                                     @Param("sku") String sku,
                                                     Pageable pageable);
}



