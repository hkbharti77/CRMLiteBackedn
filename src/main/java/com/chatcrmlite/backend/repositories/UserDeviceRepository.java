package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.UserDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {

    /** All active (non-revoked) devices for a user — used to send FCM notifications. */
    @Query("SELECT d FROM UserDevice d WHERE d.userId = :userId AND d.revokedAt IS NULL")
    List<UserDevice> findAllActiveByUserId(@Param("userId") UUID userId);

    /** Find device by Firebase Installation ID — used for upsert (no duplicates per device). */
    Optional<UserDevice> findByInstallationId(String installationId);

    /** Soft-revoke a stale device when FCM returns UNREGISTERED. */
    @Modifying
    @Query("UPDATE UserDevice d SET d.revokedAt = :revokedAt WHERE d.id = :id")
    void revokeById(@Param("id") UUID id, @Param("revokedAt") LocalDateTime revokedAt);
}
