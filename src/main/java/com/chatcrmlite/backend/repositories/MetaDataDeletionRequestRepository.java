package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.MetaDataDeletionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MetaDataDeletionRequestRepository extends JpaRepository<MetaDataDeletionRequest, UUID> {

    Optional<MetaDataDeletionRequest> findByConfirmationCode(String confirmationCode);

    List<MetaDataDeletionRequest> findAllByMetaUserIdOrderByRequestedAtDesc(String metaUserId);

    Optional<MetaDataDeletionRequest> findFirstByMetaUserIdAndStatus(String metaUserId, String status);
}
