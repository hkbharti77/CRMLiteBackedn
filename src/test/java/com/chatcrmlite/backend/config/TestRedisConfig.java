package com.chatcrmlite.backend.config;

import org.mockito.Mockito;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@Configuration
@Profile("test")
public class TestRedisConfig {

    @Bean
    @Primary
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager();
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        RedisConnectionFactory factory = Mockito.mock(RedisConnectionFactory.class);
        RedisConnection connection = Mockito.mock(RedisConnection.class);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.isSubscribed()).thenReturn(false);
        return factory;
    }

    @Bean
    @Primary
    public RedisMessageListenerContainer redisContainer() {
        return Mockito.mock(RedisMessageListenerContainer.class);
    }

    @Bean
    @Primary
    public ChannelTopic webSocketTopic() {
        return new ChannelTopic("ws:events:broadcast:test");
    }

    @Bean
    @Primary
    public MessageListenerAdapter listenerAdapter() {
        return Mockito.mock(MessageListenerAdapter.class);
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = Mockito.mock(ValueOperations.class);
        StreamOperations<String, Object, Object> streamOperations = Mockito.mock(StreamOperations.class);
        
        when(template.opsForValue()).thenReturn(valueOperations);
        when(template.opsForStream()).thenReturn(streamOperations);
        when(valueOperations.setIfAbsent(any(), any(), any())).thenReturn(true);
        when(valueOperations.increment(anyString())).thenReturn(1L);
        
        return template;
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = Mockito.mock(RedisTemplate.class);
        ValueOperations<String, Object> valueOperations = Mockito.mock(ValueOperations.class);
        when(template.opsForValue()).thenReturn(valueOperations);
        return template;
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public RedissonClient redissonClient() {
        RedissonClient redissonClient = Mockito.mock(RedissonClient.class);
        RBlockingQueue<Object> blockingQueue = Mockito.mock(RBlockingQueue.class);
        RDelayedQueue<Object> delayedQueue = Mockito.mock(RDelayedQueue.class);
        RLock lock = Mockito.mock(RLock.class);

        when(redissonClient.getBlockingQueue(anyString())).thenReturn((RBlockingQueue) blockingQueue);
        when(redissonClient.getDelayedQueue(any())).thenReturn((RDelayedQueue) delayedQueue);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(redissonClient.isShutdown()).thenReturn(false);

        return redissonClient;
    }
}
