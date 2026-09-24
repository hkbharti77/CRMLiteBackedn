package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.CustomerJourney;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomerJourneyRepository extends JpaRepository<CustomerJourney, UUID> {

    List<CustomerJourney> findByBusinessId(String businessId);

    List<CustomerJourney> findByBusinessIdAndStatus(String businessId, String status);

    List<CustomerJourney> findByBusinessIdAndTriggerEventAndStatus(String businessId, String triggerEvent, String status);

    Optional<CustomerJourney> findByIdAndBusinessId(UUID id, String businessId);
}
