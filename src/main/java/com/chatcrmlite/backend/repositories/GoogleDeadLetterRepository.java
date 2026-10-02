package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.google.GoogleDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GoogleDeadLetterRepository extends JpaRepository<GoogleDeadLetter, UUID> {

    List<GoogleDeadLetter> findByUserIdAndResolvedFalseOrderByCreatedAtDesc(UUID userId);

    long countByUserIdAndResolvedFalse(UUID userId);
}
