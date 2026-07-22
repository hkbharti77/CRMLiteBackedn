package com.chatcrmlite.backend.queue;

import com.chatcrmlite.backend.dto.email.EmailJobPayload;
import com.chatcrmlite.backend.services.EmailService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisEmailConsumer {

    private final RedissonClient redissonClient;
    private final EmailService emailService;

    private static final String EMAIL_QUEUE = "email_queue";
    private static final String EMAIL_DLQ = "email_dlq";
    private static final int MAX_RETRIES = 3;

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @PostConstruct
    public void init() {
        RBlockingQueue<EmailJobPayload> queue = redissonClient.getBlockingQueue(EMAIL_QUEUE);
        // Important: RDelayedQueue must be retrieved to activate the delay mechanism for the destination queue
        RDelayedQueue<EmailJobPayload> delayedQueue = redissonClient.getDelayedQueue(queue);

        executorService.submit(() -> {
            while (!Thread.currentThread().isInterrupted() && !redissonClient.isShutdown()) {
                try {
                    // Blocking take: waits until an item is available
                    EmailJobPayload payload = queue.take();
                    processEmail(payload, delayedQueue);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("[RedisEmailConsumer] Consumer thread interrupted");
                    break;
                } catch (org.redisson.RedissonShutdownException e) {
                    log.info("[RedisEmailConsumer] Redisson client shutdown. Stopping email consumer.");
                    break;
                } catch (Exception e) {
                    if (redissonClient.isShutdown()) {
                        log.info("[RedisEmailConsumer] Redisson is shutdown. Exiting consumer loop.");
                        break;
                    }
                    log.error("[RedisEmailConsumer] Unexpected error polling queue", e);
                }
            }
        });
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        log.info("[RedisEmailConsumer] Shutting down executor service...");
        executorService.shutdownNow();
    }

    private void processEmail(EmailJobPayload payload, RDelayedQueue<EmailJobPayload> delayedQueue) {
        log.debug("[RedisEmailConsumer] Processing email for: {}", payload.getToEmail());
        try {
            Context ctx = new Context();
            if (payload.getContextVariables() != null) {
                ctx.setVariables(payload.getContextVariables());
            }
            // Send synchronously in this worker thread.
            // Note: Ensure EmailService.sendTemplate is synchronous or adapt accordingly.
            emailService.sendTemplateSync(payload.getToEmail(), payload.getSubject(), payload.getTemplateName(), ctx);
            log.info("[RedisEmailConsumer] Successfully sent email to {}", payload.getToEmail());

        } catch (Exception e) {
            log.error("[RedisEmailConsumer] Failed to send email to {}. Attempt: {}", payload.getToEmail(), payload.getRetryCount() + 1, e);
            handleFailure(payload, delayedQueue);
        }
    }

    private void handleFailure(EmailJobPayload payload, RDelayedQueue<EmailJobPayload> delayedQueue) {
        int currentRetries = payload.getRetryCount();
        if (currentRetries < MAX_RETRIES) {
            payload.setRetryCount(currentRetries + 1);
            // Exponential backoff: 2 mins, 4 mins, 8 mins...
            long delayMinutes = (long) Math.pow(2, currentRetries);
            delayedQueue.offer(payload, delayMinutes, TimeUnit.MINUTES);
            log.info("[RedisEmailConsumer] Re-queued email for {} to retry in {} minutes", payload.getToEmail(), delayMinutes);
        } else {
            log.error("[RedisEmailConsumer] Max retries reached for {}. Moving to DLQ.", payload.getToEmail());
            org.redisson.api.RBlockingQueue<EmailJobPayload> dlq = redissonClient.getBlockingQueue(EMAIL_DLQ);
            dlq.add(payload);
        }
    }
}
