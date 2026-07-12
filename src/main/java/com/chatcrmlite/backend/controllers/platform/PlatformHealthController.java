package com.chatcrmlite.backend.controllers.platform;

import com.chatcrmlite.backend.services.platform.PlatformAuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/platform/health")
public class PlatformHealthController {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final PlatformAuditService auditService;
    
    // Uptime tracking
    private static final LocalDateTime START_TIME = LocalDateTime.now();

    public PlatformHealthController(DataSource dataSource,
                                    RedisConnectionFactory redisConnectionFactory,
                                    PlatformAuditService auditService) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.auditService = auditService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health(HttpServletRequest request) {
        auditService.record("VIEWED_HEALTH", "SUCCESS", "System", null, "{}", request);

        Map<String, Object> services = new LinkedHashMap<>();

        // Postgres
        long dbStart = System.currentTimeMillis();
        boolean dbUp = false;
        try (Connection c = dataSource.getConnection()) {
            dbUp = c.isValid(1);
        } catch (Exception ignored) {}
        long dbLatency = System.currentTimeMillis() - dbStart;
        
        services.put("database", Map.of(
            "status", dbUp ? "UP" : "DOWN",
            "latencyMs", dbLatency,
            "version", "PostgreSQL",
            "uptime", dbUp ? 99.9 : 0.0,
            "lastChecked", LocalDateTime.now().toString()
        ));

        // Redis
        long redisStart = System.currentTimeMillis();
        boolean redisUp = false;
        try {
            redisUp = "PONG".equals(redisConnectionFactory.getConnection().ping());
        } catch (Exception ignored) {}
        long redisLatency = System.currentTimeMillis() - redisStart;

        services.put("redis", Map.of(
            "status", redisUp ? "UP" : "DOWN",
            "latencyMs", redisLatency,
            "version", "Redis",
            "uptime", redisUp ? 99.9 : 0.0,
            "lastChecked", LocalDateTime.now().toString()
        ));

        // Application (Self)
        services.put("api", Map.of(
            "status", "UP",
            "latencyMs", 1, // Self is always fast if it can reply
            "version", "1.0.0",
            "uptime", 100.0,
            "lastChecked", LocalDateTime.now().toString(),
            "startedAt", START_TIME.toString()
        ));

        boolean allUp = dbUp && redisUp;
        
        return ResponseEntity.ok(Map.of(
            "status", allUp ? "UP" : "DEGRADED",
            "services", services,
            "timestamp", LocalDateTime.now().toString()
        ));
    }
}
