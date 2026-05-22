package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.security.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSocket Configuration with STOMP.
 *
 * Multi-tenant isolation is achieved via topic naming:
 *   Each tenant subscribes to: /topic/{tenantId}/messages
 *   Server pushes ONLY to that tenant's topic.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Autowired
    private JwtUtils jwtUtils;

    /**
     * Register the WebSocket endpoint at /ws.
     * SockJS fallback allows older browsers & React Native to connect.
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS();
    }

    /**
     * Configure the in-memory STOMP message broker.
     * /topic  → server-to-client broadcasts (e.g. incoming WhatsApp messages)
     * /app    → client-to-server destinations (not needed now, but wired in)
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * Authenticate WebSocket connections using JWT token.
     * Token can be passed as STOMP header: "Authorization: Bearer <token>"
     */
    @Autowired
    private com.chatcrmlite.backend.repositories.UserRepository userRepository;

    @Autowired
    private com.chatcrmlite.backend.security.TenantSubscriptionInterceptor subscriptionInterceptor;

    /**
     * Authenticate WebSocket connections and enforce subscription authorization.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization");
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7);
                        if (jwtUtils.validateJwtToken(token)) {
                            String email = jwtUtils.getEmailFromJwtToken(token);
                            
                            // Resolve the tenant context (Tenant ID) once during CONNECT
                            userRepository.findByEmail(email).ifPresent(user -> {
                                com.chatcrmlite.backend.security.UserPrincipal principal = 
                                        new com.chatcrmlite.backend.security.UserPrincipal(user.getEmail(), user.getTenant().getId());
                                accessor.setUser(principal);
                            });
                        }
                    }
                }
                return message;
            }
        }, subscriptionInterceptor);
    }
}
