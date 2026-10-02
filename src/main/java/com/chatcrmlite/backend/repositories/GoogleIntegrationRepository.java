package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.google.GoogleIntegration;
import com.chatcrmlite.backend.models.google.GoogleIntegrationStatus;
import com.chatcrmlite.backend.models.google.GoogleIntegrationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GoogleIntegrationRepository extends JpaRepository<GoogleIntegration, UUID> {

    /** Find a specific feature integration for a connection. */
    Optional<GoogleIntegration> findByConnectionIdAndFeature(UUID connectionId, GoogleIntegrationType feature);

    /** Find all integrations for a connection. */
    List<GoogleIntegration> findAllByConnectionId(UUID connectionId);

    /** Count how many integrations are still CONNECTED for a connection. */
    @Query("SELECT COUNT(i) FROM GoogleIntegration i WHERE i.connectionId = :connectionId AND i.status = 'CONNECTED'")
    long countActiveByConnectionId(@Param("connectionId") UUID connectionId);

    /** Update status for a specific feature of a connection. */
    @Modifying
    @Query("UPDATE GoogleIntegration i SET i.status = :status WHERE i.connectionId = :connectionId AND i.feature = :feature")
    void updateStatus(@Param("connectionId") UUID connectionId,
                      @Param("feature") GoogleIntegrationType feature,
                      @Param("status") GoogleIntegrationStatus status);

    /** Find all integrations for all connections belonging to a user. */
    @Query("SELECT i FROM GoogleIntegration i JOIN GoogleConnection c ON c.id = i.connectionId WHERE c.userId = :userId AND c.revokedAt IS NULL")
    List<GoogleIntegration> findAllActiveByUserId(@Param("userId") UUID userId);
}
