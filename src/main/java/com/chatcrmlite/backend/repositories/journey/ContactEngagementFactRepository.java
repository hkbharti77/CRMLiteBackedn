package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.ContactEngagementFact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactEngagementFactRepository extends JpaRepository<ContactEngagementFact, UUID> {

    Optional<ContactEngagementFact> findByBusinessIdAndContactId(String businessId, UUID contactId);
}
