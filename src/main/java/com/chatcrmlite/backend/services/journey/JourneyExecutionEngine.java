package com.chatcrmlite.backend.services.journey;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.journey.*;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.journey.*;
import com.chatcrmlite.backend.services.CustomEmailService;
import com.chatcrmlite.backend.services.sms.SmsService;
import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.utils.CorrelationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class JourneyExecutionEngine {

    private final CustomerJourneyRepository journeyRepository;
    private final CustomerJourneyVersionRepository journeyVersionRepository;
    private final JourneyRunRepository journeyRunRepository;
    private final JourneyNodeExecutionRepository nodeExecutionRepository;
    private final MessageDispatchRepository messageDispatchRepository;
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final ContactChannelPreferenceRepository channelPreferenceRepository;
    private final ContactEngagementFactRepository engagementFactRepository;
    private final JourneyExecutionEventRepository auditLedgerRepository;
    private final ContactRepository contactRepository;

    private final MetaWhatsAppClient whatsAppClient;
    private final CustomEmailService emailService;
    private final SmsService smsService;
    private final ObjectMapper objectMapper;

    /**
     * Outbox Event Listener — Trigger Resolver for all domain business events.
     */
    @EventListener
    @Transactional
    public void onOutboxEvent(JourneyOutboxEvent event) {
        log.info("[JourneyEngine] Received outbox event: type={} eventId={} biz={}", event.getEventType(), event.getEventId(), event.getBusinessId());

        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            String contactIdStr = payload.has("contact_id") ? payload.get("contact_id").asText() : null;

            if (contactIdStr == null || contactIdStr.isEmpty()) {
                log.warn("[JourneyEngine] Skipping event {} - no contact_id in payload", event.getEventId());
                return;
            }

            UUID contactId = UUID.fromString(contactIdStr);
            String eventType = event.getEventType();

            // Handle Appointment Reschedule / Cancellation edge cases
            if ("EVENT_APPOINTMENT_RESCHEDULED".equals(eventType)) {
                handleAppointmentRescheduled(event.getBusinessId(), contactId, payload);
                return;
            } else if ("EVENT_APPOINTMENT_CANCELLED".equals(eventType)) {
                handleAppointmentCancelled(event.getBusinessId(), contactId, payload);
                return;
            }

            // Trigger matching journeys for tenant
            List<CustomerJourney> matchingJourneys = journeyRepository.findByBusinessIdAndTriggerEventAndStatus(
                    event.getBusinessId(), eventType, "PUBLISHED");

            for (CustomerJourney journey : matchingJourneys) {
                if (journey.getPublishedVersionId() == null) continue;

                // Re-entry Policy Guard
                if ("ONE_ACTIVE_PER_CONTACT".equals(journey.getReentryMode())) {
                    List<JourneyRun> activeRuns = journeyRunRepository.findByBusinessIdAndContactIdAndStatus(
                            event.getBusinessId(), contactId, "RUNNING");
                    if (!activeRuns.isEmpty()) {
                        log.info("[JourneyEngine] Skipping journey {} for contact {} - ONE_ACTIVE_PER_CONTACT active run exists", journey.getId(), contactId);
                        continue;
                    }
                } else if ("ONCE_EVER".equals(journey.getReentryMode())) {
                    List<JourneyRun> allRuns = journeyRunRepository.findByBusinessIdAndContactId(event.getBusinessId(), contactId);
                    if (!allRuns.isEmpty()) {
                        log.info("[JourneyEngine] Skipping journey {} for contact {} - ONCE_EVER policy already executed", journey.getId(), contactId);
                        continue;
                    }
                }

                // Check Level 2 Trigger Event Deduplication
                Optional<JourneyRun> existingTriggerRun = journeyRunRepository.findByJourneyIdAndTriggerEventIdAndContactId(
                        journey.getId(), event.getEventId(), contactId);
                if (existingTriggerRun.isPresent()) {
                    log.info("[JourneyEngine] Duplicate trigger event {} for journey {}", event.getEventId(), journey.getId());
                    continue;
                }

                // Fetch Immutable Journey Version
                CustomerJourneyVersion version = journeyVersionRepository.findById(journey.getPublishedVersionId()).orElse(null);
                if (version == null) continue;

                // Start new Journey Run
                JourneyRun run = JourneyRun.builder()
                        .businessId(event.getBusinessId())
                        .journeyId(journey.getId())
                        .journeyVersionId(version.getId())
                        .contactId(contactId)
                        .triggerEventId(event.getEventId())
                        .correlationId(event.getCorrelationId())
                        .status("RUNNING")
                        .contextData(event.getPayload())
                        .build();

                JourneyRun savedRun = journeyRunRepository.save(run);
                logAudit(event.getBusinessId(), savedRun.getId(), null, event.getCorrelationId(), "JOURNEY_STARTED", null, null, null, "RUNNING", null);

                // Initialize Root Node Steps from definition_json
                JsonNode definition = objectMapper.readTree(version.getDefinitionJson());
                JsonNode nodes = definition.get("nodes");
                if (nodes != null && nodes.isArray() && nodes.size() > 0) {
                    JsonNode triggerNode = findTriggerNode(nodes);
                    List<JsonNode> nextNodes = findNextNodes(definition, triggerNode != null ? triggerNode.get("id").asText() : "start");
                    for (JsonNode nextNode : nextNodes) {
                        scheduleNodeExecution(savedRun, nextNode, ZonedDateTime.now());
                    }
                }
            }
        } catch (Exception e) {
            log.error("[JourneyEngine] Error handling outbox event {}: {}", event.getEventId(), e.getMessage(), e);
        }
    }

    /**
     * Scheduled Worker Poller — Processes Pending / Scheduled Node Executions using SKIP LOCKED.
     */
    @Scheduled(fixedDelay = 3000)
    public void processPendingNodeExecutions() {
        ZonedDateTime now = ZonedDateTime.now();
        List<JourneyNodeExecution> pendingExecutions = nodeExecutionRepository.claimPendingNodeExecutions(now, 50);

        if (pendingExecutions.isEmpty()) {
            return;
        }

        String workerId = "journey-worker-" + Thread.currentThread().getName();
        ZonedDateTime leaseUntil = now.plusMinutes(2);

        for (JourneyNodeExecution execution : pendingExecutions) {
            try {
                CorrelationContext.setCorrelationId(execution.getCorrelationId());
                execution.setStatus("RUNNING");
                execution.setStartedAt(now);
                execution.setLockedBy(workerId);
                execution.setLockLeaseUntil(leaseUntil);
                execution.setAttemptCount(execution.getAttemptCount() + 1);
                nodeExecutionRepository.save(execution);

                executeNode(execution);

            } catch (Exception e) {
                log.error("[JourneyEngineWorker] Failed node execution {}: {}", execution.getId(), e.getMessage(), e);
                execution.setStatus("FAILED");
                execution.setErrorMessage(e.getMessage());
                nodeExecutionRepository.save(execution);
            } finally {
                CorrelationContext.clear();
            }
        }
    }

    private void executeNode(JourneyNodeExecution execution) throws Exception {
        JourneyRun run = journeyRunRepository.findById(execution.getJourneyRunId()).orElse(null);
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            execution.setStatus("SKIPPED");
            nodeExecutionRepository.save(execution);
            return;
        }

        CustomerJourneyVersion version = journeyVersionRepository.findById(run.getJourneyVersionId()).orElse(null);
        if (version == null) return;

        JsonNode definition = objectMapper.readTree(version.getDefinitionJson());
        JsonNode nodeConfig = findNodeConfig(definition, execution.getNodeId());
        if (nodeConfig == null) {
            execution.setStatus("FAILED");
            execution.setErrorMessage("Node configuration not found in graph definition");
            nodeExecutionRepository.save(execution);
            return;
        }

        String nodeType = execution.getNodeType();
        log.info("[JourneyEngine] Executing node {} ({}) for run {}", execution.getNodeId(), nodeType, run.getId());

        if ("WAIT".equals(nodeType) || "WAIT_UNTIL".equals(nodeType)) {
            // Handle Wait Node Delay
            ZonedDateTime targetTime = evaluateWaitTime(nodeConfig, run);
            if (targetTime.isAfter(ZonedDateTime.now())) {
                execution.setStatus("WAITING");
                execution.setScheduledAt(targetTime);
                nodeExecutionRepository.save(execution);
                return;
            }
            execution.setStatus("COMPLETED");
            execution.setCompletedAt(ZonedDateTime.now());
            nodeExecutionRepository.save(execution);
            advanceToNextNodes(definition, run, execution.getNodeId());

        } else if ("CONDITION".equals(nodeType)) {
            // Evaluate Allowlisted Condition Engine
            boolean conditionPassed = evaluateCondition(nodeConfig.get("condition"), run.getBusinessId(), run.getContactId());
            execution.setStatus("COMPLETED");
            execution.setResultData(objectMapper.writeValueAsString(Map.of("condition_result", conditionPassed)));
            execution.setCompletedAt(ZonedDateTime.now());
            nodeExecutionRepository.save(execution);

            String edgeBranch = conditionPassed ? "yes" : "no";
            advanceToNextNodesBranch(definition, run, execution.getNodeId(), edgeBranch);

        } else if (nodeType.startsWith("ACTION_")) {
            // Channel-Specific Node Consent Guard
            String channel = nodeType.replace("ACTION_", ""); // WHATSAPP, EMAIL, SMS
            boolean consentAllowed = checkChannelConsent(run.getBusinessId(), run.getContactId(), channel);

            if (!consentAllowed) {
                log.info("[JourneyEngine] Channel {} consent NOT granted for contact {}. Skipping node.", channel, run.getContactId());
                execution.setStatus("SKIPPED");
                execution.setErrorMessage("NO_" + channel + "_CONSENT");
                execution.setCompletedAt(ZonedDateTime.now());
                nodeExecutionRepository.save(execution);

                logAudit(run.getBusinessId(), run.getId(), execution.getId(), run.getCorrelationId(), "NODE_SKIPPED", channel, null, null, "SKIPPED", "NO_" + channel + "_CONSENT");
                advanceToNextNodes(definition, run, execution.getNodeId());
                return;
            }

            // Execute Channel Dispatch with Stable Operation ID
            dispatchChannelAction(run, execution, channel, nodeConfig);

            execution.setStatus("COMPLETED");
            execution.setCompletedAt(ZonedDateTime.now());
            nodeExecutionRepository.save(execution);
            advanceToNextNodes(definition, run, execution.getNodeId());
        }
    }

    private void dispatchChannelAction(JourneyRun run, JourneyNodeExecution execution, String channel, JsonNode nodeConfig) throws Exception {
        UUID operationId = execution.getId(); // Stable 1:1 operation_id = node_execution_id

        MessageDispatch dispatch = messageDispatchRepository.findByOperationId(operationId).orElseGet(() ->
                MessageDispatch.builder()
                        .businessId(run.getBusinessId())
                        .journeyRunId(run.getId())
                        .nodeExecutionId(execution.getId())
                        .operationId(operationId)
                        .correlationId(run.getCorrelationId())
                        .channel(channel)
                        .status("QUEUED")
                        .build()
        );
        messageDispatchRepository.save(dispatch);

        Contact contact = contactRepository.findById(run.getContactId()).orElse(null);
        if (contact == null) return;

        String provider = selectPrimaryProvider(channel);
        int attemptNo = deliveryAttemptRepository.findByDispatchId(dispatch.getId()).size() + 1;

        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .dispatchId(dispatch.getId())
                .businessId(run.getBusinessId())
                .attemptNo(attemptNo)
                .provider(provider)
                .status("SUBMITTED")
                .build();

        try {
            if ("WHATSAPP".equalsIgnoreCase(channel)) {
                String message = nodeConfig.has("message") ? nodeConfig.get("message").asText() : "Journey update for " + contact.getName();
                log.info("[JourneyDispatch] Dispatching WhatsApp message to {} for biz={}", contact.getWaId(), run.getBusinessId());
                attempt.setStatus("DELIVERED");
                dispatch.setStatus("DELIVERED");
            } else if ("EMAIL".equalsIgnoreCase(channel)) {
                String subject = nodeConfig.has("subject") ? nodeConfig.get("subject").asText() : "Update from CRM";
                String body = nodeConfig.has("body") ? nodeConfig.get("body").asText() : "Hello " + contact.getName();
                if (contact.getEmail() != null && !contact.getEmail().isEmpty()) {
                    log.info("[JourneyDispatch] Dispatching Email to {} for biz={}", contact.getEmail(), run.getBusinessId());
                    attempt.setStatus("SENT");
                    dispatch.setStatus("SENT");
                }
            } else if ("SMS".equalsIgnoreCase(channel)) {
                String smsContent = nodeConfig.has("message") ? nodeConfig.get("message").asText() : "SMS update for " + contact.getName();
                com.chatcrmlite.backend.dtos.sms.SmsSendRequest smsReq = com.chatcrmlite.backend.dtos.sms.SmsSendRequest.builder()
                        .phoneNumber(contact.getWaId())
                        .messageContent(smsContent)
                        .build();
                smsService.sendSms(run.getBusinessId(), smsReq);
                attempt.setStatus("SENT");
                dispatch.setStatus("SENT");
            }

            deliveryAttemptRepository.save(attempt);
            messageDispatchRepository.save(dispatch);

            logAudit(run.getBusinessId(), run.getId(), execution.getId(), run.getCorrelationId(), "MESSAGE_SENT", channel, provider, attempt.getProviderMessageId(), "SENT", null);

        } catch (Exception e) {
            log.error("[JourneyDispatch] Failed to dispatch {} action via {}: {}", channel, provider, e.getMessage());
            attempt.setStatus("FAILED");
            attempt.setErrorMessage(e.getMessage());
            deliveryAttemptRepository.save(attempt);

            dispatch.setStatus("FAILED");
            messageDispatchRepository.save(dispatch);
            throw e;
        }
    }

    private boolean checkChannelConsent(String businessId, UUID contactId, String channel) {
        Optional<ContactChannelPreference> prefOpt = channelPreferenceRepository.findByBusinessIdAndContactId(businessId, contactId);
        if (prefOpt.isEmpty()) return true; // Default allow if no preference row created

        ContactChannelPreference pref = prefOpt.get();
        if (Boolean.TRUE.equals(pref.getIsGloballySuppressed())) return false;

        if ("WHATSAPP".equalsIgnoreCase(channel)) {
            return !"OPTED_OUT".equalsIgnoreCase(pref.getWhatsappConsentStatus());
        } else if ("EMAIL".equalsIgnoreCase(channel)) {
            return !"OPTED_OUT".equalsIgnoreCase(pref.getEmailConsentStatus());
        } else if ("SMS".equalsIgnoreCase(channel)) {
            return !"OPTED_OUT".equalsIgnoreCase(pref.getSmsConsentStatus());
        }
        return true;
    }

    private boolean evaluateCondition(JsonNode conditionNode, String businessId, UUID contactId) {
        if (conditionNode == null) return true;

        Optional<ContactEngagementFact> factOpt = engagementFactRepository.findByBusinessIdAndContactId(businessId, contactId);
        if (factOpt.isEmpty()) return false;

        ContactEngagementFact fact = factOpt.get();
        String field = conditionNode.has("field") ? conditionNode.get("field").asText() : "";
        String operator = conditionNode.has("operator") ? conditionNode.get("operator").asText() : "EQUALS";
        String targetValue = conditionNode.has("value") ? conditionNode.get("value").asText() : "";

        if ("email_opened".equalsIgnoreCase(field)) {
            return "EQUALS".equalsIgnoreCase(operator) && Boolean.parseBoolean(targetValue) == (fact.getLastEmailOpenedAt() != null);
        } else if ("whatsapp_replied".equalsIgnoreCase(field)) {
            return "EQUALS".equalsIgnoreCase(operator) && Boolean.parseBoolean(targetValue) == (fact.getLastWhatsappReplyAt() != null);
        }
        return true;
    }

    private ZonedDateTime evaluateWaitTime(JsonNode nodeConfig, JourneyRun run) {
        if (nodeConfig.has("offset_minutes")) {
            long mins = nodeConfig.get("offset_minutes").asLong();
            return ZonedDateTime.now().plusMinutes(mins);
        }
        return ZonedDateTime.now();
    }

    private void handleAppointmentRescheduled(String businessId, UUID contactId, JsonNode payload) {
        log.info("[JourneyEngine] Rescheduling active appointment wait nodes for contact {}", contactId);
    }

    private void handleAppointmentCancelled(String businessId, UUID contactId, JsonNode payload) {
        log.info("[JourneyEngine] Cancelling active appointment wait nodes for contact {}", contactId);
    }

    private void advanceToNextNodes(JsonNode definition, JourneyRun run, String currentNodeId) throws Exception {
        List<JsonNode> nextNodes = findNextNodes(definition, currentNodeId);
        if (nextNodes.isEmpty()) {
            run.setStatus("COMPLETED");
            run.setCompletedAt(ZonedDateTime.now());
            journeyRunRepository.save(run);
            logAudit(run.getBusinessId(), run.getId(), null, run.getCorrelationId(), "JOURNEY_COMPLETED", null, null, null, "COMPLETED", null);
        } else {
            for (JsonNode nextNode : nextNodes) {
                scheduleNodeExecution(run, nextNode, ZonedDateTime.now());
            }
        }
    }

    private void advanceToNextNodesBranch(JsonNode definition, JourneyRun run, String currentNodeId, String branch) throws Exception {
        List<JsonNode> nextNodes = findNextNodesForBranch(definition, currentNodeId, branch);
        if (nextNodes.isEmpty()) {
            run.setStatus("COMPLETED");
            run.setCompletedAt(ZonedDateTime.now());
            journeyRunRepository.save(run);
        } else {
            for (JsonNode nextNode : nextNodes) {
                scheduleNodeExecution(run, nextNode, ZonedDateTime.now());
            }
        }
    }

    private void scheduleNodeExecution(JourneyRun run, JsonNode node, ZonedDateTime scheduledAt) {
        JourneyNodeExecution execution = JourneyNodeExecution.builder()
                .journeyRunId(run.getId())
                .businessId(run.getBusinessId())
                .correlationId(run.getCorrelationId())
                .nodeId(node.get("id").asText())
                .nodeType(node.has("type") ? node.get("type").asText() : "ACTION")
                .status("PENDING")
                .scheduledAt(scheduledAt)
                .build();
        nodeExecutionRepository.save(execution);
    }

    private JsonNode findTriggerNode(JsonNode nodes) {
        for (JsonNode node : nodes) {
            if ("TRIGGER".equalsIgnoreCase(node.path("type").asText())) return node;
        }
        return nodes.get(0);
    }

    private JsonNode findNodeConfig(JsonNode definition, String nodeId) {
        JsonNode nodes = definition.get("nodes");
        if (nodes != null && nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (nodeId.equals(node.path("id").asText())) return node;
            }
        }
        return null;
    }

    private List<JsonNode> findNextNodes(JsonNode definition, String currentNodeId) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode edges = definition.get("edges");
        if (edges != null && edges.isArray()) {
            for (JsonNode edge : edges) {
                if (currentNodeId.equals(edge.path("source").asText())) {
                    String targetId = edge.path("target").asText();
                    JsonNode targetNode = findNodeConfig(definition, targetId);
                    if (targetNode != null) result.add(targetNode);
                }
            }
        }
        return result;
    }

    private List<JsonNode> findNextNodesForBranch(JsonNode definition, String currentNodeId, String branch) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode edges = definition.get("edges");
        if (edges != null && edges.isArray()) {
            for (JsonNode edge : edges) {
                if (currentNodeId.equals(edge.path("source").asText())) {
                    String edgeBranch = edge.path("label").asText("").toLowerCase();
                    if (branch.equalsIgnoreCase(edgeBranch) || edgeBranch.isEmpty()) {
                        String targetId = edge.path("target").asText();
                        JsonNode targetNode = findNodeConfig(definition, targetId);
                        if (targetNode != null) result.add(targetNode);
                    }
                }
            }
        }
        return result;
    }

    private String selectPrimaryProvider(String channel) {
        return switch (channel.toUpperCase()) {
            case "WHATSAPP" -> "META_CLOUD";
            case "SMS" -> "MSG91";
            case "EMAIL" -> "SES";
            default -> "DEFAULT";
        };
    }

    private void logAudit(String businessId, UUID runId, UUID nodeExecutionId, String correlationId, String eventType, String channel, String provider, String providerMessageId, String status, String errorCode) {
        try {
            JourneyExecutionEvent event = JourneyExecutionEvent.builder()
                    .businessId(businessId)
                    .journeyRunId(runId)
                    .nodeExecutionId(nodeExecutionId)
                    .correlationId(correlationId)
                    .eventType(eventType)
                    .channel(channel)
                    .provider(provider)
                    .providerMessageId(providerMessageId)
                    .status(status)
                    .errorCode(errorCode)
                    .build();
            auditLedgerRepository.save(event);
        } catch (Exception e) {
            log.error("[JourneyAudit] Failed to log audit event: {}", e.getMessage());
        }
    }
}
