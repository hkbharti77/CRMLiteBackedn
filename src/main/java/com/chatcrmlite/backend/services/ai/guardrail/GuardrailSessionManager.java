package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.dto.ai.UserSession;
import com.chatcrmlite.backend.services.RedisStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class GuardrailSessionManager {

    private final RedisStateService redisStateService;

    public UserSession getSession(UUID tenantId, String userId) {
        String sessionKey = redisStateService.buildKey(tenantId, "session", userId);
        UserSession session = redisStateService.get(sessionKey, UserSession.class);
        if (session == null) {
            session = new UserSession();
        }
        return session;
    }

    public void saveSession(UUID tenantId, String userId, UserSession session) {
        String sessionKey = redisStateService.buildKey(tenantId, "session", userId);
        redisStateService.set(sessionKey, session, Duration.ofMinutes(10));
    }
}
