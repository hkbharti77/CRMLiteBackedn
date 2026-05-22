# Runbook: Database Exhaustion

**Severity:** P0 (Critical)
**Component:** PostgreSQL / HikariCP
**On-Call:** Database Admin / SRE

## Symptoms
- `Connection is not available, request timed out after 30000ms.`
- CPU on PostgreSQL node > 90%.
- Disk usage on `/var/lib/postgresql/data` > 95%.
- Slow query logs filling up.

## Investigation Steps
1. **Connection Pool:**
   - Check Hikari metrics: `hikaricp_connections_active` vs `hikaricp_connections_max`.
2. **Active Queries:**
   - Run `SELECT * FROM pg_stat_activity WHERE state = 'active';` to find long-running queries.
3. **Bloat Check:**
   - Check `vector_store` and `chat_messages` tables for fragmentation.
4. **Disk Space:**
   - Run `df -h` on the DB node.

## Recovery Actions
- **Terminate Long Queries:** `SELECT pg_terminate_backend(pid);` for blocking queries.
- **Trigger Retention:** Manually trigger `DatabaseRetentionService.archiveOldData()` to free up space in hot tables.
- **Scale Hikari Pool:** Increase `spring.datasource.hikari.maximum-pool-size` (requires restart).
- **VACUUM ANALYZE:** Run `VACUUM ANALYZE;` on high-churn tables.

## Rollback Procedures
- Revert connection pool size if memory usage on the DB node becomes unstable.
