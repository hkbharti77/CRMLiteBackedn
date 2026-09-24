package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsSuppression;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsSuppressionRepository extends JpaRepository<SmsSuppression, UUID> {
    Optional<SmsSuppression> findByBusinessIdAndPhoneNumber(String businessId, String phoneNumber);
    boolean existsByBusinessIdAndPhoneNumber(String businessId, String phoneNumber);
}
