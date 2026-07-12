package com.chatcrmlite.backend.queue;

import com.chatcrmlite.backend.dto.email.EmailJobPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisEmailProducer {

    private final RedissonClient redissonClient;
    private static final String EMAIL_QUEUE = "email_queue";

    public void enqueueEmail(EmailJobPayload payload) {
        try {
            org.redisson.api.RBlockingQueue<EmailJobPayload> queue = redissonClient.getBlockingQueue(EMAIL_QUEUE);
            queue.add(payload);
            log.debug("[RedisEmailProducer] Enqueued email job for type: {}, to: {}", payload.getJobType(), payload.getToEmail());
        } catch (Exception e) {
            log.error("[RedisEmailProducer] Failed to enqueue email job for {}", payload.getToEmail(), e);
        }
    }
}
