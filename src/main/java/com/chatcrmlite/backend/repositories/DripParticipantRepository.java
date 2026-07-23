package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.DripParticipant;
import com.chatcrmlite.backend.models.DripSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DripParticipantRepository extends JpaRepository<DripParticipant, UUID> {
    List<DripParticipant> findByStatusAndNextRunAtBefore(DripParticipant.ParticipantStatus status, LocalDateTime now);
    Optional<DripParticipant> findBySequenceAndContact(DripSequence sequence, Contact contact);
    List<DripParticipant> findByContactAndStatus(Contact contact, DripParticipant.ParticipantStatus status);
}
