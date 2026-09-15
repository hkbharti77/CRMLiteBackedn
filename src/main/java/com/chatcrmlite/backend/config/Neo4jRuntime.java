package com.chatcrmlite.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-scoped Neo4j gate. Auth/network failure disables graph for the JVM lifetime
 * without failing vector RAG or spamming per-document WARN logs.
 */
@Slf4j
public final class Neo4jRuntime implements AutoCloseable {

    private final Driver driver;
    private final SessionConfig sessionConfig;
    private final AtomicBoolean available;

    private Neo4jRuntime(Driver driver, SessionConfig sessionConfig, boolean available) {
        this.driver = driver;
        this.sessionConfig = sessionConfig;
        this.available = new AtomicBoolean(available);
    }

    public static Neo4jRuntime ready(Driver driver, String database) {
        String db = (database == null || database.isBlank()) ? "neo4j" : database.trim();
        SessionConfig config = SessionConfig.builder().withDatabase(db).build();
        return new Neo4jRuntime(driver, config, true);
    }

    public static Neo4jRuntime disabled() {
        return new Neo4jRuntime(null, null, false);
    }

    public boolean isAvailable() {
        return available.get() && driver != null;
    }

    public Session openSession() {
        if (!isAvailable()) {
            throw new IllegalStateException("Neo4j is not available");
        }
        return driver.session(sessionConfig);
    }

    /**
     * First auth/connectivity failure flips graph off for this process.
     */
    public void disable(String reason) {
        if (available.compareAndSet(true, false)) {
            log.warn("[Neo4jRuntime] Graph RAG disabled for this process (vector unaffected): {}", reason);
            closeQuietly();
        }
    }

    public boolean looksLikeAuthFailure(Throwable t) {
        String msg = t != null && t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        return msg.contains("unauthorized") || msg.contains("authentication failure") || msg.contains("auth");
    }

    private void closeQuietly() {
        if (driver == null) {
            return;
        }
        try {
            driver.close();
        } catch (Exception e) {
            log.debug("[Neo4jRuntime] Driver close after disable: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        available.set(false);
        closeQuietly();
    }
}
