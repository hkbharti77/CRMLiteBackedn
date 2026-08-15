package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.livechat.LiveChatAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LiveChatAssignmentRepository extends JpaRepository<LiveChatAssignment, UUID> {

    @Query("SELECT COUNT(a) FROM LiveChatAssignment a WHERE a.assignedTo = :user AND a.status = 'ACTIVE' AND a.tenant = :tenant")
    long countActiveAssignmentsByUserAndTenant(@Param("user") User user, @Param("tenant") Tenant tenant);

    Optional<LiveChatAssignment> findByContactAndTenantAndStatus(Contact contact, Tenant tenant, LiveChatAssignment.AssignmentStatus status);

    List<LiveChatAssignment> findAllByTenantAndStatus(Tenant tenant, LiveChatAssignment.AssignmentStatus status);

    List<LiveChatAssignment> findAllByAssignedToAndTenantAndStatus(User user, Tenant tenant, LiveChatAssignment.AssignmentStatus status);
}
