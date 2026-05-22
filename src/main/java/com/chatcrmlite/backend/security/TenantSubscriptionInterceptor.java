package com.chatcrmlite.backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.chatcrmlite.backend.services.RedisStateService;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class TenantSubscriptionInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(TenantSubscriptionInterceptor.class);

    private static final Pattern TOPIC_PATTERN = Pattern.compile("^/topic/([^/]+)(/.*)?$");
    
    @Autowired
    private RedisStateService redisStateService;

    private static final int MAX_SUBS_PER_MINUTE = 20;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            UserPrincipal user = (UserPrincipal) accessor.getUser();

            if (user == null) {
                log.debug("WebSocket SUBSCRIBE rejected: no authenticated user in session (destination={}).", destination);
                throw new AccessDeniedException("Unauthenticated subscription attempt.");
            }

            log.info("🔍 Authorizing subscription for user {} to destination {}", user.getName(), destination);

            try {
                TenantContext.setTenantId(user.getTenantId());
                if (destination == null || !isValidDestination(destination, user)) {
                    log.warn("🚨 SECURITY ALERT: Unauthorized subscription attempt! User: {}, Destination: {}, TenantID: {}", 
                            user.getName(), destination, user.getTenantId());
                    throw new AccessDeniedException("You are not authorized to subscribe to this destination.");
                }

                checkRateLimit(user.getName());
                log.info("✅ Subscription authorized for user {} to {}", user.getName(), destination);
            } finally {
                TenantContext.clear();
            }
        }


        return message;
    }

    private boolean isValidDestination(String destination, UserPrincipal user) {
        Matcher matcher = TOPIC_PATTERN.matcher(destination);
        if (matcher.matches()) {
            String tenantIdInTopic = matcher.group(1);
            
            if (tenantIdInTopic.contains("*") || tenantIdInTopic.contains("..")) {
                return false;
            }

            return tenantIdInTopic.equals(user.getTenantId().toString());
        }
        return false;
    }

    private void checkRateLimit(String username) {
        String key = "global:ws:sub_rate:" + username;
        Long count = redisStateService.increment(key, java.time.Duration.ofMinutes(1));
        if (count != null && count > MAX_SUBS_PER_MINUTE) {
            log.warn("⚠️ Subscription rate limit exceeded for user: {}", username);
            throw new AccessDeniedException("Too many subscription attempts. Please wait a minute.");
        }
    }
}
