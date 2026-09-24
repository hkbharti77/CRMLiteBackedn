package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsDeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsDeliveryEventRepository extends JpaRepository<SmsDeliveryEvent, UUID> {
    Optional<SmsDeliveryEvent> findByProviderTypeAndProviderEventId(String providerType, String providerEventId);
}
