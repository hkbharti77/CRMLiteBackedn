package com.chatcrmlite.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Optional Neo4j wiring. Always exposes {@link Neo4jRuntime} when credentials exist;
 * runtime may be disabled if Aura auth/connectivity fails at startup.
 */
@Slf4j
@Configuration
public class GraphConfig {

    @Bean(destroyMethod = "close")
    @Conditional(Neo4jEnabledCondition.class)
    public Neo4jRuntime neo4jRuntime(
            @Value("${neo4j.uri}") String uri,
            @Value("${neo4j.username:}") String username,
            @Value("${neo4j.password:}") String password,
            @Value("${neo4j.database:neo4j}") String database,
            @Value("${neo4j.connection-timeout-seconds:5}") int connectionTimeoutSeconds,
            @Value("${neo4j.max-connection-pool-size:10}") int maxPoolSize) {

        String user = username != null ? username.trim() : "";
        String pass = password != null ? password.trim() : "";
        String neo4jUri = uri != null ? uri.trim() : "";

        Config config = Config.builder()
                .withConnectionTimeout(connectionTimeoutSeconds, TimeUnit.SECONDS)
                .withMaxConnectionPoolSize(maxPoolSize)
                .withMaxConnectionLifetime(30, TimeUnit.MINUTES)
                .build();

        Driver driver = GraphDatabase.driver(neo4jUri, AuthTokens.basic(user, pass), config);

        try {
            driver.verifyConnectivity();
            log.info("[GraphConfig] Neo4j connected at {} (user={}, database={})",
                    neo4jUri, user, database);
            return Neo4jRuntime.ready(driver, database);
        } catch (Exception e) {
            try {
                driver.close();
            } catch (Exception closeEx) {
                log.debug("[GraphConfig] Failed closing Neo4j driver after connectivity error: {}", closeEx.getMessage());
            }
            log.warn("[GraphConfig] Neo4j unavailable — graph RAG disabled (vector unaffected): {}. "
                    + "Reset Aura password and update NEO4J_PASSWORD (username usually 'neo4j').",
                    e.getMessage());
            return Neo4jRuntime.disabled();
        }
    }

    @Bean("neo4jHealthIndicator")
    @Conditional(Neo4jEnabledCondition.class)
    public HealthIndicator neo4jHealthIndicator(Neo4jRuntime neo4jRuntime) {
        return () -> {
            if (!neo4jRuntime.isAvailable()) {
                return Health.down().withDetail("neo4j", "disabled-auth-or-unreachable").build();
            }
            try (var session = neo4jRuntime.openSession()) {
                session.run("RETURN 1").consume();
                return Health.up().withDetail("neo4j", "up").build();
            } catch (Exception e) {
                if (neo4jRuntime.looksLikeAuthFailure(e)) {
                    neo4jRuntime.disable(e.getMessage());
                }
                return Health.down(e).withDetail("neo4j", "down").build();
            }
        };
    }
}
