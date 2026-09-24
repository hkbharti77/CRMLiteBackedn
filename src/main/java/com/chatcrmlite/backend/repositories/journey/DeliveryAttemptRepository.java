package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, UUID> {

    List<DeliveryAttempt> findByDispatchId(UUID dispatchId);

    List<DeliveryAttempt> findByDispatchIdOrderByAttemptNoAsc(UUID dispatchId);
}
