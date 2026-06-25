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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM SecurityLog s WHERE s.user = :user")
    void deleteByUser(@org.springframework.data.repository.query.Param("user") User user);
}
