package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.livechat.OutboxEvent;
import com.chatcrmlite.backend.repositories.OutboxEventRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.EmailService;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class LiveChatOutboxProcessor {
    private static final Logger log = LoggerFactory.getLogger(LiveChatOutboxProcessor.class);

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private EmailService emailService;
    @Autowired private DistributedWebSocketPublisher distributedWebSocketPublisher;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @Scheduled(fixedDelay = 7000)
    @Transactional
    public void processOutboxEvents() {
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(5);
        for (OutboxEvent event : pending) {
            try {
                handleEvent(event);
                event.setStatus(OutboxEvent.OutboxStatus.PROCESSED);
                event.setProcessedAt(LocalDateTime.now());
            } catch (Exception e) {
                log.error("Failed processing OutboxEvent {}", event.getId(), e);
                event.setAttemptCount(event.getAttemptCount() + 1);
                if (event.getAttemptCount() >= 5) {
                    event.setStatus(OutboxEvent.OutboxStatus.FAILED);
                }
            }
            outboxEventRepository.save(event);
        }
    }

    private void handleEvent(OutboxEvent event) throws Exception {
        JsonNode node = objectMapper.readTree(event.getPayload());
        String eventType = event.getEventType();
        UUID tenantId = event.getTenantId();

        // 1. Publish WebSocket STOMP notification to tenant channel
        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("eventType", eventType);
        wsPayload.put("tenantId", tenantId);
        wsPayload.put("aggregateId", event.getAggregateId());
        wsPayload.put("data", node);
        distributedWebSocketPublisher.publishMessage(tenantId, wsPayload);

        // 2. Process specific Email notifications based on eventType
        switch (eventType) {
            case "CHAT_ASSIGNED":
                sendAssignmentEmail(node);
                break;
            case "CHAT_TRANSFERRED":
                sendTransferEmail(node);
                break;
            case "CHAT_TAKEN_OVER":
                sendTakeoverEmails(node, tenantId);
                break;
            case "SLA_BREACHED":
                sendSlaBreachEmails(node, tenantId);
                break;
            default:
                break;
        }
    }

    private void sendAssignmentEmail(JsonNode node) {
        String agentEmail = node.path("assignedToEmail").asText();
        String agentName = node.path("assignedToName").asText();
        String contactName = node.path("contactName").asText();
        String contactPhone = node.path("contactPhone").asText();

        if (agentEmail != null && !agentEmail.isBlank()) {
            emailService.sendLiveChatAssignedNotification(agentEmail, agentName, contactName, contactPhone);
        }
    }

    private void sendTransferEmail(JsonNode node) {
        String targetEmail = node.path("targetUserEmail").asText();
        String targetName = node.path("targetUserName").asText();
        String contactName = node.path("contactName").asText();
        String contactPhone = node.path("contactPhone").asText();

        if (targetEmail != null && !targetEmail.isBlank()) {
            emailService.sendLiveChatAssignedNotification(targetEmail, targetName, contactName, contactPhone);
        }
    }

    private void sendTakeoverEmails(JsonNode node, UUID tenantId) {
        String takeoverByName = node.path("takeoverByName").asText();
        String contactName = node.path("contactName").asText();
        String contactPhone = node.path("contactPhone").asText();
        String reason = node.path("reason").asText();

        List<User> staff = userRepository.findStaffByTenantAndRoles(
                userRepository.findFirstUserIdByTenantId(tenantId).flatMap(userRepository::findById).map(User::getTenant).orElse(null),
                Arrays.asList(User.Role.AGENT, User.Role.ADMIN, User.Role.OWNER)
        );

        for (User u : staff) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                emailService.sendLiveChatTakeoverNotification(u.getEmail(), u.getDisplayName(), takeoverByName, contactName, contactPhone, reason);
            }
        }
    }

    private void sendSlaBreachEmails(JsonNode node, UUID tenantId) {
        String contactName = node.path("contactName").asText();
        String contactPhone = node.path("contactPhone").asText();
        int slaMinutes = node.path("slaMinutes").asInt(30);

        List<User> adminsAndOwners = userRepository.findStaffByTenantAndRoles(
                userRepository.findFirstUserIdByTenantId(tenantId).flatMap(userRepository::findById).map(User::getTenant).orElse(null),
                Arrays.asList(User.Role.ADMIN, User.Role.OWNER)
        );

        for (User u : adminsAndOwners) {
            if (u.getEmail() != null && !u.getEmail().isBlank()) {
                emailService.sendLiveChatSlaEscalationNotification(u.getEmail(), u.getDisplayName(), contactName, contactPhone, slaMinutes);
            }
        }
    }
}
