package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.sms.SmsCampaign;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SmsCampaignRepository extends JpaRepository<SmsCampaign, UUID> {
    List<SmsCampaign> findByBusinessId(String businessId);
    Optional<SmsCampaign> findByIdAndBusinessId(UUID id, String businessId);
    List<SmsCampaign> findByTenant_Id(UUID tenantId);
    Optional<SmsCampaign> findByIdAndTenant_Id(UUID id, UUID tenantId);
}
