package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Manages database table growth by moving old records to archive tables.
 * Implements a "Hot/Cold" storage strategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseRetentionService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Archive records older than 90 days.
     * Runs every week.
     */
    @Scheduled(cron = "0 0 2 * * SUN") // Sunday at 2 AM
    @SchedulerLock(name = "DatabaseRetentionService_archiveOldData", lockAtMostFor = "2h", lockAtLeastFor = "1h")
    @Transactional
    public void archiveOldData() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(90);
        log.info("[Retention] Starting data archival (cutoff: {})", cutoff);

        ensureArchiveTablesExist();
        archiveActivityLogs(cutoff);
        archiveChatMessages(cutoff);
        archiveProcessedMessages(cutoff);

        log.info("[Retention] Archival process completed.");
    }

    private void ensureArchiveTablesExist() {
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS activity_logs_archive (LIKE activity_logs INCLUDING ALL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS chat_messages_archive (LIKE chat_messages INCLUDING ALL)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS processed_messages_archive (LIKE processed_messages INCLUDING ALL)");
    }

    private void archiveActivityLogs(LocalDateTime cutoff) {
        String insertSql = "INSERT INTO activity_logs_archive SELECT * FROM activity_logs WHERE created_at < ?";
        String deleteSql = "DELETE FROM activity_logs WHERE created_at < ?";

        int moved = jdbcTemplate.update(insertSql, cutoff);
        if (moved > 0) {
            jdbcTemplate.update(deleteSql, cutoff);
            log.info("[Retention] Moved {} activity logs to archive.", moved);
        }
    }

    private void archiveChatMessages(LocalDateTime cutoff) {
        String insertSql = "INSERT INTO chat_messages_archive SELECT * FROM chat_messages WHERE timestamp < ?";
        String deleteSql = "DELETE FROM chat_messages WHERE timestamp < ?";

        int moved = jdbcTemplate.update(insertSql, cutoff);
        if (moved > 0) {
            jdbcTemplate.update(deleteSql, cutoff);
            log.info("[Retention] Moved {} chat messages to archive.", moved);
        }
    }

    private void archiveProcessedMessages(LocalDateTime cutoff) {
        String insertSql = "INSERT INTO processed_messages_archive SELECT * FROM processed_messages WHERE processed_at < ?";
        String deleteSql = "DELETE FROM processed_messages WHERE processed_at < ?";

        int moved = jdbcTemplate.update(insertSql, cutoff);
        if (moved > 0) {
            jdbcTemplate.update(deleteSql, cutoff);
            log.info("[Retention] Moved {} processed messages to archive.", moved);
        }
    }
}
