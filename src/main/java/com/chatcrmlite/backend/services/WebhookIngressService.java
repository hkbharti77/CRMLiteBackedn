package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIngressService {

    private final WebhookQueueProducer queueProducer;

    /**
     * Entry point for incoming webhooks.
     * Performs lightweight validation and hands off to the queue.
     */
    public void ingress(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("Empty payload received at ingress. Skipping.");
            return;
        }

        // Potential early validation (e.g. check for "entry" field) could happen here
        // but we prioritize speed and offload parsing to the worker.

        String correlationId = queueProducer.enqueue(payload);
        log.debug("Ingressed payload. Handled by producer. CorrelationId: {}", correlationId);
    }
}
