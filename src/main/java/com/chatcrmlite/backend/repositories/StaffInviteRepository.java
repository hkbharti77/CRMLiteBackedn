package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.StaffInvite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffInviteRepository extends JpaRepository<StaffInvite, UUID> {
    Optional<StaffInvite> findByInviteCode(String inviteCode);
    List<StaffInvite> findByTenantId(UUID tenantId);
    Optional<StaffInvite> findByEmailAndStatus(String email, StaffInvite.InviteStatus status);
}
