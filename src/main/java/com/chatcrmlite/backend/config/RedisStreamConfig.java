package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.services.WebhookWorker;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.Subscription;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.Collections;

@Configuration
@Profile("!test")
@RequiredArgsConstructor
public class RedisStreamConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisStreamConfig.class);

    private final WebhookWorker webhookWorker;

    @Value("${whatsapp.async.stream.ingress}")
    private String ingressStream;

    @Value("${workflow.stream.ai:workflow:ai}")
    private String aiStream;

    @Value("${workflow.stream.flow:workflow:flow}")
    private String flowStream;

    @Value("${workflow.stream.delivery:workflow:delivery}")
    private String deliveryStream;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Value("${whatsapp.async.worker.concurrency:3}")
    private int concurrency;

    private final com.chatcrmlite.backend.services.workflow.AIWorker aiWorker;
    private final com.chatcrmlite.backend.services.workflow.FlowWorker flowWorker;
    private final com.chatcrmlite.backend.services.workflow.DeliveryWorker deliveryWorker;

    @Bean(name = "redisStreamTaskExecutor")
    public TaskExecutor redisStreamTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(4, concurrency * 2));
        executor.setMaxPoolSize(Math.max(8, concurrency * 4));
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("RedisStreamWorker-");
        executor.setDaemon(true);
        executor.initialize();
        return executor;
    }

    @Bean
    public Subscription ingressSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate, TaskExecutor redisStreamTaskExecutor) {
        setupStream(redisTemplate, ingressStream);
        return createSubscription(factory, ingressStream, "ingress-worker", webhookWorker, redisStreamTaskExecutor);
    }

    @Bean
    public Subscription aiSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate, TaskExecutor redisStreamTaskExecutor) {
        setupStream(redisTemplate, aiStream);
        return createSubscription(factory, aiStream, "ai-worker", aiWorker, redisStreamTaskExecutor);
    }

    @Bean
    public Subscription flowSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate, TaskExecutor redisStreamTaskExecutor) {
        setupStream(redisTemplate, flowStream);
        return createSubscription(factory, flowStream, "flow-worker", flowWorker, redisStreamTaskExecutor);
    }

    @Bean
    public Subscription deliverySubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate, TaskExecutor redisStreamTaskExecutor) {
        setupStream(redisTemplate, deliveryStream);
        return createSubscription(factory, deliveryStream, "delivery-worker", deliveryWorker, redisStreamTaskExecutor);
    }

    private void setupStream(StringRedisTemplate redisTemplate, String name) {
        try {
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(name))) {
                redisTemplate.opsForStream().add(name, Collections.singletonMap("_init", "true"));
                log.info("📦 [WhatsApp-Queue] Initialized new Redis stream: {}", name);
            }
            try {
                // Use ReadOffset.from("0-0") so consumer group reads from beginning of stream
                redisTemplate.opsForStream().createGroup(name, ReadOffset.from("0-0"), groupName);
                log.info("👥 [WhatsApp-Queue] Created consumer group '{}' on stream '{}' with offset '0-0'", groupName, name);
            } catch (Exception groupEx) {
                // Group already exists
                log.debug("[WhatsApp-Queue] Consumer group '{}' on stream '{}' already exists: {}", groupName, name, groupEx.getMessage());
            }
        } catch (Exception e) {
            log.warn("⚠️ [WhatsApp-Queue] Stream/Group setup issue for '{}' (group '{}'): {}", name, groupName, e.getMessage());
        }
    }

    private Subscription createSubscription(RedisConnectionFactory factory, String stream, String consumerName,
                                           StreamListener<String, ObjectRecord<String, String>> listener,
                                           TaskExecutor taskExecutor) {
        log.info("[WhatsApp-Queue] Consumer starting. Stream: {}, Consumer group: {}, Consumer name: {}",
                stream, groupName, consumerName);

        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, ObjectRecord<String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .batchSize(10)
                        .targetType(String.class)
                        .executor(taskExecutor)
                        .errorHandler(t -> {
                            String msg = t != null ? t.getMessage() : "";
                            if (msg != null && (msg.contains("Redisson is shutdown") || msg.contains("RedissonShutdownException"))) {
                                log.debug("Redis stream worker [{}] stopped gracefully (Redisson shutdown).", consumerName);
                            } else {
                                log.error("❌ [RedisStreamWorker-{}] Error processing stream '{}': {}",
                                        consumerName, stream, (t != null ? t.getMessage() : "unknown error"), t);
                            }
                        })
                        .build();

        StreamMessageListenerContainer<String, ObjectRecord<String, String>> container =
                StreamMessageListenerContainer.create(factory, options);

        Subscription subscription = container.receive(
                Consumer.from(groupName, consumerName),
                StreamOffset.create(stream, ReadOffset.lastConsumed()),
                listener
        );

        container.start();
        log.info("🚀 [WhatsApp-Queue] Consumer started successfully. Stream: {}, Consumer group: {}, Consumer name: {}",
                stream, groupName, consumerName);
        return subscription;
    }
}
