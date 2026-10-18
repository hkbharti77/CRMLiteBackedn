package com.chatcrmlite.backend.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${spring.data.redis.username:default}")
    private String redisUsername;

    @Value("${spring.data.redis.password:#{null}}")
    private String redisPassword;

    @Value("${spring.data.redis.ssl:false}")
    private boolean ssl;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        String protocol = ssl ? "rediss://" : "redis://";
        String address = protocol + redisHost + ":" + redisPort;
        
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setConnectTimeout(10000)
                .setTimeout(10000)
                .setRetryAttempts(3)
                .setRetryInterval(1500)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(10)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(5);

        if (redisUsername != null && !redisUsername.trim().isEmpty()) {
            singleServerConfig.setUsername(redisUsername.trim());
        }
                
        // Only set the password if it's not null and not an empty string
        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            singleServerConfig.setPassword(redisPassword.trim());
        }
        
        return Redisson.create(config);
    }
}
