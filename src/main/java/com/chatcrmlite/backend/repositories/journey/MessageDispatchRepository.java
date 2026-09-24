package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.MessageDispatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MessageDispatchRepository extends JpaRepository<MessageDispatch, UUID> {

    Optional<MessageDispatch> findByOperationId(UUID operationId);

    Optional<MessageDispatch> findByNodeExecutionId(UUID nodeExecutionId);

    Optional<MessageDispatch> findByIdAndBusinessId(UUID id, String businessId);
}
