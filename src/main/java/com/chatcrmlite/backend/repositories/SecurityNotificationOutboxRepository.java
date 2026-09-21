package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.SecurityNotificationOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

@Repository
public interface SecurityNotificationOutboxRepository extends JpaRepository<SecurityNotificationOutbox, UUID> {
    List<SecurityNotificationOutbox> findTop50ByStatusOrderByCreatedAtAsc(String status);
    List<SecurityNotificationOutbox> findTop50ByStatusInOrderByCreatedAtAsc(List<String> statuses);

    @Query("SELECT COUNT(s) > 0 FROM SecurityNotificationOutbox s WHERE s.tenant.id = :tenantId AND s.eventType = :eventType AND (:phoneNumberId IS NULL OR s.phoneNumberId = :phoneNumberId) AND s.sourceEventId = :sourceEventId")
    boolean existsByTenantIdAndEventTypeAndPhoneNumberIdAndSourceEventId(
            @Param("tenantId") UUID tenantId,
            @Param("eventType") String eventType,
            @Param("phoneNumberId") String phoneNumberId,
            @Param("sourceEventId") String sourceEventId);
}
