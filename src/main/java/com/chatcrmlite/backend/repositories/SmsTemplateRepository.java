package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsTemplateRepository extends JpaRepository<SmsTemplate, UUID> {
    List<SmsTemplate> findByBusinessId(String businessId);
    Optional<SmsTemplate> findByIdAndBusinessId(UUID id, String businessId);
}
