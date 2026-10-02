package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.google.GoogleSync;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GoogleSyncRepository extends JpaRepository<GoogleSync, UUID> {

    /** Find sync mapping by CRM resource — used before creating/updating Google resource (idempotency). */
    Optional<GoogleSync> findByConnectionIdAndResourceTypeAndCrmResourceId(
            UUID connectionId, String resourceType, UUID crmResourceId);

    /** Find sync mapping by Google resource ID — used to detect Google-side deletes. */
    Optional<GoogleSync> findByConnectionIdAndResourceTypeAndGoogleResourceId(
            UUID connectionId, String resourceType, String googleResourceId);

    /** Mark all sync rows for a connection+type as DISCONNECTED when user disconnects a feature. */
    @Modifying
    @Query("UPDATE GoogleSync s SET s.syncStatus = 'DISCONNECTED' WHERE s.connectionId = :connectionId AND s.resourceType = :resourceType")
    void markDisconnectedByConnectionAndType(@Param("connectionId") UUID connectionId,
                                             @Param("resourceType") String resourceType);

    /** Check if a CRM resource already has a Google sync mapping. */
    boolean existsByConnectionIdAndResourceTypeAndCrmResourceId(
            UUID connectionId, String resourceType, UUID crmResourceId);
}
