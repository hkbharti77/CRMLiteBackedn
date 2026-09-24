package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SmsProviderRepository extends JpaRepository<SmsProvider, String> {
    List<SmsProvider> findByBusinessId(String businessId);
    Optional<SmsProvider> findByBusinessIdAndIsDefaultTrue(String businessId);
    Optional<SmsProvider> findByIdAndBusinessId(String id, String businessId);
}
