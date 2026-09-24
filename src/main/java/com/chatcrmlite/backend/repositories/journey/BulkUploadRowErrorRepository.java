package com.chatcrmlite.backend.repositories.journey;

import com.chatcrmlite.backend.models.journey.BulkUploadRowError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BulkUploadRowErrorRepository extends JpaRepository<BulkUploadRowError, UUID> {

    List<BulkUploadRowError> findByBatchId(UUID batchId);

    List<BulkUploadRowError> findByBusinessIdAndBatchId(String businessId, UUID batchId);
}
