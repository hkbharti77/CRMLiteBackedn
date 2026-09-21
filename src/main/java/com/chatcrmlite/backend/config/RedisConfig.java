package com.chatcrmlite.backend.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@Profile("!test")
public class RedisConfig {

    // Bump this version prefix whenever the serialization format changes.
    // All keys stored under a different prefix are simply cache-misses (ignored).
    private static final String CACHE_KEY_PREFIX = "v2::";

    @Bean
    @org.springframework.context.annotation.Primary
    public RedisConnectionFactory redisConnectionFactory(
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.host:localhost}") String host,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.port:6379}") int port,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.password:}") String password,
            @org.springframework.beans.factory.annotation.Value("${spring.data.redis.timeout:10000}") long timeout) {

        org.springframework.data.redis.connection.RedisStandaloneConfiguration redisConfig =
                new org.springframework.data.redis.connection.RedisStandaloneConfiguration(host, port);
        if (password != null && !password.trim().isEmpty()) {
            redisConfig.setPassword(org.springframework.data.redis.connection.RedisPassword.of(password));
        }

        io.lettuce.core.SocketOptions socketOptions = io.lettuce.core.SocketOptions.builder()
                .connectTimeout(Duration.ofMillis(timeout))
                .keepAlive(io.lettuce.core.SocketOptions.KeepAliveOptions.builder()
                        .enable()
                        .idle(Duration.ofSeconds(15))
                        .interval(Duration.ofSeconds(5))
                        .count(3)
                        .build())
                .build();

        io.lettuce.core.ClientOptions clientOptions = io.lettuce.core.ClientOptions.builder()
                .socketOptions(socketOptions)
                .autoReconnect(true)
                .disconnectedBehavior(io.lettuce.core.ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .build();

        org.apache.commons.pool2.impl.GenericObjectPoolConfig<?> poolConfig = new org.apache.commons.pool2.impl.GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(50);
        poolConfig.setMaxIdle(25);
        poolConfig.setMinIdle(5);
        poolConfig.setMaxWait(Duration.ofMillis(5000));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration clientConfig =
                org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration.builder()
                        .clientOptions(clientOptions)
                        .commandTimeout(Duration.ofMillis(timeout))
                        .poolConfig(poolConfig)
                        .build();

        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                new org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory(redisConfig, clientConfig);
        // MUST be false: stream workers execute blocking commands (XREADGROUP ... BLOCK)
        // If true, all workers share ONE single connection, creating head-of-line blocking and 10s command timeouts.
        factory.setShareNativeConnection(false);
        factory.setValidateConnection(true);
        return factory;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.registerModule(new com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);
        
        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
        template.afterPropertiesSet();
        
        return template;
    }

    /**
     * Primary CacheManager for @Cacheable annotations.
     * Uses Jackson JSON serialization instead of JDK serialization,
     * which eliminates NotSerializableException for all cached DTOs.
     * Cache entries expire after 10 minutes by default.
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.registerModule(new com.fasterxml.jackson.datatype.hibernate6.Hibernate6Module());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        om.activateDefaultTyping(LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL);

        Jackson2JsonRedisSerializer<Object> serializer = new Jackson2JsonRedisSerializer<>(om, Object.class);

        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .prefixCacheNameWith(CACHE_KEY_PREFIX)           // <-- version prefix
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(config)
                .build();
    }

    @Bean
    public ChannelTopic webSocketTopic() {
        return new ChannelTopic("ws:events:broadcast");
    }

    @Bean
    public RedisMessageListenerContainer redisContainer(RedisConnectionFactory connectionFactory,
                                                        MessageListenerAdapter listenerAdapter,
                                                        ChannelTopic webSocketTopic) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, webSocketTopic);
        return container;
    }

    @Bean
    public MessageListenerAdapter listenerAdapter(com.chatcrmlite.backend.services.websocket.WebSocketEventBus eventBus) {
        return new MessageListenerAdapter(eventBus, "handleMessage");
    }
}
