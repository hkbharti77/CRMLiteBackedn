package com.chatcrmlite.backend.config;

import com.chatcrmlite.backend.services.WebhookWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ObjectRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.data.redis.stream.Subscription;

import java.time.Duration;
import java.util.Collections;

@Configuration
@RequiredArgsConstructor
public class RedisStreamConfig {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RedisStreamConfig.class);

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

    @Value("${whatsapp.async.worker.concurrency}")
    private int concurrency;

    private final com.chatcrmlite.backend.services.workflow.AIWorker aiWorker;
    private final com.chatcrmlite.backend.services.workflow.FlowWorker flowWorker;
    private final com.chatcrmlite.backend.services.workflow.DeliveryWorker deliveryWorker;

    @Bean
    public Subscription ingressSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate) {
        setupStream(redisTemplate, ingressStream);
        return createSubscription(factory, ingressStream, "ingress-worker", webhookWorker);
    }

    @Bean
    public Subscription aiSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate) {
        setupStream(redisTemplate, aiStream);
        return createSubscription(factory, aiStream, "ai-worker", aiWorker);
    }

    @Bean
    public Subscription flowSubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate) {
        setupStream(redisTemplate, flowStream);
        return createSubscription(factory, flowStream, "flow-worker", flowWorker);
    }

    @Bean
    public Subscription deliverySubscription(RedisConnectionFactory factory, StringRedisTemplate redisTemplate) {
        setupStream(redisTemplate, deliveryStream);
        return createSubscription(factory, deliveryStream, "delivery-worker", deliveryWorker);
    }

    private void setupStream(StringRedisTemplate redisTemplate, String name) {
        try {
            if (!redisTemplate.hasKey(name)) {
                redisTemplate.opsForStream().add(name, Collections.singletonMap("_init", "true"));
            }
            redisTemplate.opsForStream().createGroup(name, groupName);
        } catch (Exception e) {
            log.debug("Stream {} or group {} initialization skipped", name, groupName);
        }
    }

    private Subscription createSubscription(RedisConnectionFactory factory, String stream, String consumerName, StreamListener<String, ObjectRecord<String, String>> listener) {
        StreamMessageListenerContainer.StreamMessageListenerContainerOptions<String, ObjectRecord<String, String>> options =
                StreamMessageListenerContainer.StreamMessageListenerContainerOptions
                        .builder()
                        .pollTimeout(Duration.ofSeconds(1))
                        .batchSize(10)
                        .targetType(String.class)
                        .errorHandler(t -> {
                            if (t instanceof NullPointerException && t.getStackTrace() != null && t.getStackTrace().length > 0 && t.getStackTrace()[0].getClassName().contains("StreamPollTask")) {
                                log.trace("Redis stream poll timeout for [{}] on stream '{}'", consumerName, stream);
                            } else {
                                log.error("❌ [RedisStreamWorker-{}] Error processing stream '{}': {}", consumerName, stream, t.getMessage(), t);
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
        log.info("🚀 Worker [{}] started for stream: {}", consumerName, stream);
        return subscription;
    }
}
