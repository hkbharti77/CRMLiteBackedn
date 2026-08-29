package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findAllByOwnerAndIsCompletedFalse(User owner);
    List<Reminder> findAllByDueDateBeforeAndIsCompletedFalse(LocalDateTime now);
    List<Reminder> findAllByLeadAndOwner(Lead lead, User owner);

    @Query("SELECT r FROM Reminder r WHERE r.id = :id AND r.owner.tenant.id = :tenantId")
    Optional<Reminder> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
