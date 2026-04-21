package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ConversationStateRepository extends JpaRepository<ConversationState, UUID> {

    Optional<ConversationState> findByContact(Contact contact);

    boolean existsByContact(Contact contact);

    void deleteByContact(Contact contact);

    /** Cleanup stale flows older than the given threshold (e.g., 24 hours). */
    @Modifying
    @Query("DELETE FROM ConversationState c WHERE c.lastUpdatedAt < :cutoff")
    void deleteStaleFlows(LocalDateTime cutoff);
}
