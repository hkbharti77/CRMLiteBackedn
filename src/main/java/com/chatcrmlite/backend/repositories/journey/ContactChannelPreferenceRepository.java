package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.ContactChannelPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ContactChannelPreferenceRepository extends JpaRepository<ContactChannelPreference, UUID> {

    Optional<ContactChannelPreference> findByBusinessIdAndContactId(String businessId, UUID contactId);
}
