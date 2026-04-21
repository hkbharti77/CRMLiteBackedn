package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;

public interface ReminderRepository extends JpaRepository<Reminder, UUID> {
    List<Reminder> findAllByOwnerAndIsCompletedFalse(User owner);
    List<Reminder> findAllByDueDateBeforeAndIsCompletedFalse(LocalDateTime now);
    List<Reminder> findAllByLeadAndOwner(Lead lead, User owner);
}
