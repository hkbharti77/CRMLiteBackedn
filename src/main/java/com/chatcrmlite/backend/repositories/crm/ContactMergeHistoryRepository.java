package com.chatcrmlite.backend.repositories.crm;

import com.chatcrmlite.backend.models.crm.ContactMergeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ContactMergeHistoryRepository extends JpaRepository<ContactMergeHistory, UUID> {

    @Query("SELECT h FROM ContactMergeHistory h WHERE h.tenant.id = :tenantId AND (h.primaryContact.id = :contactId OR h.secondaryContact.id = :contactId) ORDER BY h.mergedAt DESC")
    List<ContactMergeHistory> findHistoryForContact(@Param("tenantId") UUID tenantId, @Param("contactId") UUID contactId);

    @Query("SELECT h FROM ContactMergeHistory h WHERE h.tenant.id = :tenantId AND h.primaryContact.id = :primaryContactId ORDER BY h.mergedAt DESC")
    List<ContactMergeHistory> findByPrimaryContact(@Param("tenantId") UUID tenantId, @Param("primaryContactId") UUID primaryContactId);
}
