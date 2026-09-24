package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.BulkUploadBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BulkUploadBatchRepository extends JpaRepository<BulkUploadBatch, UUID> {

    List<BulkUploadBatch> findByBusinessId(String businessId);

    Optional<BulkUploadBatch> findByBusinessIdAndFileChecksum(String businessId, String fileChecksum);

    Optional<BulkUploadBatch> findByIdAndBusinessId(UUID id, String businessId);
}
