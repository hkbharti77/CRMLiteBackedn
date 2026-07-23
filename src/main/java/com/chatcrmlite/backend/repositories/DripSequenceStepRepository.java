package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.DripSequence;
import com.chatcrmlite.backend.models.DripSequenceStep;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DripSequenceStepRepository extends JpaRepository<DripSequenceStep, UUID> {
    List<DripSequenceStep> findBySequenceOrderByStepOrderAsc(DripSequence sequence);
    Optional<DripSequenceStep> findBySequenceAndStepOrder(DripSequence sequence, Integer stepOrder);
}
