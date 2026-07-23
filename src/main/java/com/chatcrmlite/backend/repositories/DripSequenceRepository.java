package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.DripSequence;
import com.chatcrmlite.backend.models.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DripSequenceRepository extends JpaRepository<DripSequence, UUID> {
    List<DripSequence> findByOwnerAndActiveTrue(User owner);
    List<DripSequence> findByTriggerEventAndActiveTrue(DripSequence.TriggerEvent triggerEvent);
}
