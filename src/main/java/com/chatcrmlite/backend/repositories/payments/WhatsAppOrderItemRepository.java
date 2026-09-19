package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.payments.WhatsAppOrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface WhatsAppOrderItemRepository extends JpaRepository<WhatsAppOrderItem, UUID> {

    @Query("SELECT i FROM WhatsAppOrderItem i WHERE i.order.id = :orderId AND i.tenant.id = :tenantId")
    List<WhatsAppOrderItem> findAllByOrderIdAndTenantId(@Param("orderId") UUID orderId, @Param("tenantId") UUID tenantId);
}
