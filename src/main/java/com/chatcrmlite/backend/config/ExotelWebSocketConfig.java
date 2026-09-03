package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.websocket.ExotelMediaWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ExotelWebSocketConfig implements WebSocketConfigurer {

    private final ExotelMediaWebSocketHandler exotelMediaWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Exotel will connect to this endpoint for bidirectional audio streaming
        registry.addHandler(exotelMediaWebSocketHandler, "/ws/exotel/stream")
                .setAllowedOrigins("*"); 
    }
}
