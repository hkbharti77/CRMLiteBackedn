package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsProviderRepository extends JpaRepository<SmsProvider, String> {
    List<SmsProvider> findByBusinessId(String businessId);
    Optional<SmsProvider> findByBusinessIdAndIsDefaultTrue(String businessId);
    Optional<SmsProvider> findByIdAndBusinessId(String id, String businessId);
    List<SmsProvider> findByTenant_Id(UUID tenantId);
    Optional<SmsProvider> findByTenant_IdAndIsDefaultTrue(UUID tenantId);
    Optional<SmsProvider> findByIdAndTenant_Id(String id, UUID tenantId);
}
