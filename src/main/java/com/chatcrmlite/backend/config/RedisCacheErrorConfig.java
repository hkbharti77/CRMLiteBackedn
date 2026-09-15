package com.chatcrmlite.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Role;

/**
 * Separate from RedisConfig so meterRegistryPostProcessor does not eagerly init Redis wiring.
 */
@Configuration
@Profile("!test")
@Role(BeanDefinition.ROLE_INFRASTRUCTURE)
public class RedisCacheErrorConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheErrorConfig.class);

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public CachingConfigurer cachingConfigurer() {
        return new CachingConfigurer() {
            @Override
            public CacheErrorHandler errorHandler() {
                return new SimpleCacheErrorHandler() {
                    @Override
                    public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                        log.warn("[Redis-Cache] GET error on cache='{}' key='{}' — evicting corrupt entry and falling back to DB. Cause: {}",
                                cache.getName(), key, e.getMessage());
                        try {
                            cache.evict(key);
                        } catch (Exception evictEx) {
                            log.warn("[Redis-Cache] Failed to evict key '{}' from cache '{}': {}",
                                    key, cache.getName(), evictEx.getMessage());
                        }
                    }
                };
            }
        };
    }
}
