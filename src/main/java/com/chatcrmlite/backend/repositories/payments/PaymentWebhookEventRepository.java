package com.chatcrmlite.backend.repositories.payments;

import com.chatcrmlite.backend.models.payments.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, UUID> {

    @Query("SELECT e FROM PaymentWebhookEvent e WHERE e.paymentIntegration.id = :integrationId AND e.providerEventKey = :eventKey")
    Optional<PaymentWebhookEvent> findByIntegrationIdAndEventKey(
        @Param("integrationId") UUID integrationId,
        @Param("eventKey") String eventKey
    );
}
