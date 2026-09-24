package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.CustomerJourneyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerJourneyVersionRepository extends JpaRepository<CustomerJourneyVersion, UUID> {

    List<CustomerJourneyVersion> findByJourneyId(UUID journeyId);

    Optional<CustomerJourneyVersion> findByJourneyIdAndVersionNumber(UUID journeyId, Integer versionNumber);

    Optional<CustomerJourneyVersion> findByIdAndBusinessId(UUID id, String businessId);
}
