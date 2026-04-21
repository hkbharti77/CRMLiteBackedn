package com.chatcrmlite.backend.repositories;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.SecurityLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SecurityLogRepository extends JpaRepository<SecurityLog, UUID> {
    List<SecurityLog> findByUserOrderByTimestampDesc(User user);
    List<SecurityLog> findByUserAndActionOrderByTimestampDesc(User user, SecurityLog.LogAction action);
}
