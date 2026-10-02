package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.google.GoogleConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface GoogleConnectionRepository extends JpaRepository<GoogleConnection, UUID> {

    /** Find an active (non-revoked) connection for a user. */
    @Query("SELECT c FROM GoogleConnection c WHERE c.userId = :userId AND c.revokedAt IS NULL ORDER BY c.createdAt DESC")
    Optional<GoogleConnection> findActiveByUserId(@Param("userId") UUID userId);

    /** Find connection by Google subject ID. */
    Optional<GoogleConnection> findByGoogleSubjectId(String googleSubjectId);

    /** Find active connection that has the given scope in its grantedScopes. */
    @Query("SELECT c FROM GoogleConnection c WHERE c.userId = :userId AND c.revokedAt IS NULL AND c.grantedScopes LIKE %:scope%")
    Optional<GoogleConnection> findActiveByUserIdAndScope(@Param("userId") UUID userId, @Param("scope") String scope);
}
