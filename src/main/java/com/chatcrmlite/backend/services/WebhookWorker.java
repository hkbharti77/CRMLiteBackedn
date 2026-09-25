package com.chatcrmlite.backend.services;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.UUID;

@Service
public class WebhookWorker implements StreamListener<String, MapRecord<String, String, String>> {
    private static final Logger log = LoggerFactory.getLogger(WebhookWorker.class);

    private final com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator workflowOrchestrator;
    private final com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeadLetterHandler dlqHandler;
    private final com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager;
    private final com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository;
    private final com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    private final com.chatcrmlite.backend.services.whatsapp.TemplateHealthService templateHealthService;
    private final com.chatcrmlite.backend.services.whatsapp.AccountHealthService accountHealthService;

    @Autowired
    public WebhookWorker(
            com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator workflowOrchestrator,
            com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            DeadLetterHandler dlqHandler,
            com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager,
            com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository,
            com.chatcrmlite.backend.repositories.TenantRepository tenantRepository,
            @Autowired(required = false) com.chatcrmlite.backend.services.whatsapp.TemplateHealthService templateHealthService,
            @Autowired(required = false) com.chatcrmlite.backend.services.whatsapp.AccountHealthService accountHealthService) {
        this.workflowOrchestrator = workflowOrchestrator;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.dlqHandler = dlqHandler;
        this.resourceManager = resourceManager;
        this.whatsappTemplateRepository = whatsappTemplateRepository;
        this.tenantRepository = tenantRepository;
        this.templateHealthService = templateHealthService;
        this.accountHealthService = accountHealthService;
    }

    public WebhookWorker(
            com.chatcrmlite.backend.services.workflow.WorkflowOrchestrator workflowOrchestrator,
            com.chatcrmlite.backend.repositories.WhatsAppConfigRepository whatsappConfigRepository,
            ObjectMapper objectMapper,
            StringRedisTemplate redisTemplate,
            DeadLetterHandler dlqHandler,
            com.chatcrmlite.backend.services.tenant.TenantResourceManager resourceManager,
            com.chatcrmlite.backend.repositories.WhatsAppTemplateRepository whatsappTemplateRepository,
            com.chatcrmlite.backend.repositories.TenantRepository tenantRepository) {
        this(workflowOrchestrator, whatsappConfigRepository, objectMapper, redisTemplate, dlqHandler, resourceManager,
             whatsappTemplateRepository, tenantRepository, null, null);
    }

    @Autowired private com.chatcrmlite.backend.services.whatsapp.campaign.CampaignAnalyticsService campaignAnalyticsService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.MessageDeliveryStatusService messageDeliveryStatusService;
    @Autowired private RedisStateService redisStateService;
    @Autowired private com.chatcrmlite.backend.clients.WhatsAppClient whatsappClient;
    @Autowired private com.chatcrmlite.backend.repositories.flows.WhatsAppFlowRepository flowRepository;
    @Autowired private com.chatcrmlite.backend.repositories.flows.FlowRevisionRepository flowRevisionRepository;
    @Autowired private com.chatcrmlite.backend.services.IdempotencyService idempotencyService;
    @Autowired private com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher distributedWebSocketPublisher;
    @Autowired private com.chatcrmlite.backend.repositories.ContactRepository contactRepository;
    @Autowired private com.chatcrmlite.backend.repositories.MessageRepository messageRepository;
    @Autowired private com.chatcrmlite.backend.repositories.UserRepository userRepository;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.ContactIdentityService contactIdentityService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.WhatsAppPreferenceService whatsappPreferenceService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.WhatsAppHandoverService whatsappHandoverService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.StandbyProcessor standbyProcessor;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.PhoneCallingSettingsService phoneCallingSettingsService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.BusinessUsernameService businessUsernameService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.WhatsAppCallingAgentService callingAgentService;
    @Autowired(required = false) private com.chatcrmlite.backend.services.whatsapp.OutboundCallPermissionService outboundCallPermissionService;

    @Value("${whatsapp.async.stream.ingress}")
    private String streamName;

    @Value("${whatsapp.async.group}")
    private String groupName;

    @Value("${whatsapp.async.max-retries}")
    private int maxRetries;

    @Override
    public void onMessage(MapRecord<String, String, String> record) {
        String rawData = record.getValue().get("payload");
        String streamMessageId = record.getId().toString();

        // Skip initialization dummy messages
        if (rawData == null || rawData.isBlank() || "true".equals(rawData) || rawData.contains("_init")) {
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        log.info("[WhatsApp-Queue] Consumed message streamMessageId={} from stream={}", streamMessageId, streamName);

        String payload = unwrapPayload(rawData);
        if (payload == null || payload.isBlank()) {
            log.warn("⚠️ [WhatsApp-Queue] Empty unwrapped payload for streamMessageId={}. Acknowledging to clear.", streamMessageId);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            return;
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            if (root == null || !root.has("entry")) {
                log.warn("⚠️ [WhatsApp-Queue] Non-webhook payload missing 'entry' node. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode entryArray = root.path("entry");
            if (!entryArray.isArray() || entryArray.isEmpty()) {
                log.warn("⚠️ [WhatsApp-Queue] Empty 'entry' array in webhook payload. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode entry = entryArray.get(0);
            JsonNode changesArray = entry != null ? entry.path("changes") : null;
            if (changesArray == null || !changesArray.isArray() || changesArray.isEmpty()) {
                log.warn("⚠️ [WhatsApp-Queue] Missing 'changes' in webhook payload. streamMessageId={}", streamMessageId);
                redisTemplate.opsForStream().acknowledge(groupName, record);
                return;
            }

            JsonNode change = changesArray.get(0);
            String field = change.path("field").asText("");
            JsonNode value = change.path("value");
            boolean handedToOrchestrator = false;

            long rawSeconds = entry.path("time").asLong(0);
            if (rawSeconds == 0) {
                rawSeconds = value.path("timestamp").asLong(System.currentTimeMillis() / 1000);
            }
            java.time.Instant eventTimestamp = java.time.Instant.ofEpochSecond(rawSeconds);

            switch (field) {
                case "messages":
                    handedToOrchestrator = processInboundMessages(entry, change, record, payload);
                    break;
                case "message_echoes":
                case "smb_message_echoes":
                    processEchoes(entry, change, record, field);
                    break;
                case "flows":
                    processFlowWebhook(entry, change);
                    break;
                case "account_update":
                    accountHealthService.handleAccountUpdate(entry, change, eventTimestamp, streamMessageId);
                    break;
                case "business_capability_update":
                    accountHealthService.handleBusinessCapabilityUpdate(entry, change, eventTimestamp);
                    break;
                case "security":
                    accountHealthService.handleSecurityAlert(entry, change, eventTimestamp, streamMessageId);
                    break;
                case "template_correct_category_detection":
                    templateHealthService.handleCategoryDetection(entry, change, eventTimestamp);
                    break;
                case "template_category_update":
                    templateHealthService.handleCategoryUpdate(entry, change, eventTimestamp);
                    break;
                case "message_template_quality_update":
                    templateHealthService.handleQualityUpdate(entry, change, eventTimestamp);
                    break;
                case "message_template_status_update":
                    templateHealthService.handleStatusUpdate(entry, change, eventTimestamp, streamMessageId);
                    break;
                case "message_template_components_update":
                    templateHealthService.handleComponentsUpdate(entry, change, eventTimestamp);
                    break;
                case "account_alerts":
                    processAccountAlerts(entry, change);
                    break;
                case "account_review_update":
                    processAccountReviewUpdate(entry, change);
                    break;
                case "phone_number_quality_update":
                case "quality_update":
                    processQualityUpdate(entry, change);
                    break;
                case "phone_number_name_update":
                    processPhoneNumberNameUpdate(entry, change);
                    break;
                case "smb_app_state_sync":
                    processSmbAppStateSync(entry, change);
                    break;
                case "user_preferences":
                    if (whatsappPreferenceService != null) {
                        whatsappPreferenceService.handleUserPreferences(entry, change, eventTimestamp);
                    }
                    break;
                case "messaging_handovers":
                    if (whatsappHandoverService != null) {
                        whatsappHandoverService.handleHandover(entry, change, eventTimestamp);
                    }
                    break;
                case "standby":
                    if (standbyProcessor != null) {
                        standbyProcessor.handleStandby(entry, change, eventTimestamp);
                    }
                    break;
                case "account_settings_update":
                    if (phoneCallingSettingsService != null) {
                        phoneCallingSettingsService.handleSettingsUpdate(entry, change, eventTimestamp);
                    }
                    break;
                case "business_username_update":
                case "business_username_updates":
                    if (businessUsernameService != null) {
                        businessUsernameService.handleUsernameUpdate(entry, change, eventTimestamp);
                    }
                    break;
                case "calls":
                    if (callingAgentService != null) {
                        try {
                            com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope envelope = objectMapper.readValue(payload, com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope.class);
                            callingAgentService.processWebhookEnvelope(envelope);
                        } catch (Exception e) {
                            log.error("❌ [WebhookWorker] Error processing call webhook envelope: {}", e.getMessage(), e);
                        }
                    }
                    break;
                default:
                    log.debug("ℹ️ [BSP] Unhandled webhook field: {}", field);
                    break;
            }

            // ACKNOWLEDGE successful ingress
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete("worker:retry:" + streamMessageId);
            if (handedToOrchestrator) {
                log.info("[WhatsApp-Queue] ACK message handed to orchestrator. streamMessageId={}", streamMessageId);
            } else {
                log.info("[WhatsApp-Queue] ACK callback processed. streamMessageId={}", streamMessageId);
            }

        } catch (Exception e) {
            handleFailure(record, e);
        }
    }

    private boolean processInboundMessages(JsonNode entry, JsonNode change, MapRecord<String, String, String> record, String payload) {
        JsonNode value = change.path("value");
        JsonNode messages = value != null ? value.path("messages") : null;
        JsonNode statuses = value != null ? value.path("statuses") : null;

        if (messages != null && messages.isArray() && !messages.isEmpty()) {
            JsonNode firstMsg = messages.get(0);
            String waId = firstMsg.path("from").asText(null);
            if (waId == null || waId.isBlank()) {
                waId = firstMsg.path("from_user_id").asText(null);
            }
            if (waId == null || waId.isBlank()) {
                waId = firstMsg.path("from_parent_user_id").asText(null);
            }
            if (waId == null || waId.isBlank()) {
                waId = firstMsg.path("user_id").asText(null);
            }
            if (waId == null || waId.isBlank()) {
                JsonNode contacts = value.path("contacts");
                if (contacts != null && contacts.isArray() && !contacts.isEmpty()) {
                    waId = contacts.get(0).path("wa_id").asText(null);
                    if (waId == null || waId.isBlank()) {
                        waId = contacts.get(0).path("user_id").asText(null);
                    }
                    if (waId == null || waId.isBlank()) {
                        waId = contacts.get(0).path("parent_user_id").asText(null);
                    }
                }
            }
            String waMessageId = firstMsg.path("id").asText();
            String phoneNumberId = value.path("metadata").path("phone_number_id").asText();

            log.info("[WhatsApp-Message] Parsed incoming message waMessageId={} from/userId={} phoneNumberId={}",
                    waMessageId, waId, phoneNumberId);

            UUID tenantId = whatsappConfigRepository
                    .findTenantIdByPhoneNumberId(phoneNumberId.trim())
                    .orElse(null);

            if (tenantId != null) {
                // Intercept system messages (e.g. user_changed_number, user_changed_user_id, user_identity_changed)
                String msgType = firstMsg.path("type").asText("");
                if ("system".equalsIgnoreCase(msgType)) {
                    log.info("🔔 [SystemMessage] Intercepted system message in messages webhook for tenantId={}", tenantId);
                    if (contactIdentityService != null) {
                        contactIdentityService.processSystemMessage(firstMsg, tenantId, value);
                    }
                    return false;
                }

                // Intercept call permission interactive replies
                if ("interactive".equalsIgnoreCase(msgType)) {
                    JsonNode interactive = firstMsg.path("interactive");
                    String interType = interactive.path("type").asText("");
                    JsonNode permReply = interactive.has("call_permission_reply") ? interactive.path("call_permission_reply") : interactive.path("voice_call");
                    if ("call_permission_reply".equalsIgnoreCase(interType) || "voice_call".equalsIgnoreCase(interType) || !permReply.isMissingNode()) {
                        if (outboundCallPermissionService != null && !permReply.isMissingNode()) {
                            String status = permReply.path("status").asText(null);
                            String permType = permReply.path("permission_type").asText(null);
                            Boolean isPermanent = permReply.has("is_permanent") ? permReply.path("is_permanent").asBoolean() : null;
                            String responseSource = permReply.path("response_source").asText("USER_RESPONSE");
                            Instant expiresAt = null;
                            if (permReply.has("expiration_timestamp")) {
                                long expSeconds = permReply.path("expiration_timestamp").asLong(0);
                                if (expSeconds > 0) expiresAt = Instant.ofEpochSecond(expSeconds);
                            }
                            if (status != null && !status.isBlank()) {
                                outboundCallPermissionService.reconcilePermissionFromWebhook(
                                    tenantId, phoneNumberId.trim(), waId, status.toUpperCase(),
                                    permType != null ? permType.toUpperCase() : null,
                                    isPermanent, responseSource, expiresAt
                                );
                                log.info("📞 [CallPermissionReply] Reconciled call permission from reply for user={}: status={}", waId, status);
                            }
                        }
                    }
                }

                if (!resourceManager.canConsume(tenantId,
                        com.chatcrmlite.backend.services.tenant.TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
                    log.warn("🚨 [Rate-Limit] Tenant {} exceeded message rate limit. Dropping message {}", tenantId, waMessageId);
                    redisTemplate.opsForStream().acknowledge(groupName, record);
                    return false;
                }

                // 🔵 Send Blue Tick Read Receipt to WhatsApp user
                try {
                    whatsappConfigRepository.findByTenantId(tenantId).ifPresent(config -> {
                        if (config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
                            whatsappClient.markAsRead(waMessageId, config.getAccessToken(), phoneNumberId.trim());
                            log.info("🔵 [BlueTick] Sent read receipt to user for waMessageId={}", waMessageId);
                        }
                    });
                } catch (Exception ex) {
                    log.warn("⚠️ [BlueTick] Could not send read receipt: {}", ex.getMessage());
                }

                log.info("[WhatsApp-Queue] Processing started waMessageId={} tenantId={}", waMessageId, tenantId);
                workflowOrchestrator.startWorkflow(waMessageId, waId, tenantId, payload);
                return true;
            } else {
                log.warn("⚠️ [Worker] No tenant found in system for phone_number_id: {}", phoneNumberId);
            }
        } else if (statuses != null && statuses.isArray() && !statuses.isEmpty()) {
            for (JsonNode statusNode : statuses) {
                String waMsgId = statusNode.path("id").asText("");
                String statusStr = statusNode.path("status").asText("");
                String errorReason = statusNode.has("errors") ? statusNode.path("errors").toString() : null;

                // BSUID-aware recipient identifier resolution:
                // recipient_user_id -> recipient_parent_user_id -> recipient_id (phone)
                String recipientIdentifier = statusNode.path("recipient_user_id").asText(null);
                if (recipientIdentifier == null || recipientIdentifier.isBlank()) {
                    recipientIdentifier = statusNode.path("recipient_parent_user_id").asText(null);
                }
                if (recipientIdentifier == null || recipientIdentifier.isBlank()) {
                    recipientIdentifier = statusNode.path("recipient_id").asText("");
                }

                log.info("[WhatsApp-Delivery] Status update waMessageId={} recipientIdentifier={} status={} conversationId={}",
                        waMsgId,
                        recipientIdentifier,
                        statusStr,
                        statusNode.path("conversation").path("id").asText(""));

                if (!waMsgId.isBlank() && campaignAnalyticsService != null) {
                    try {
                        campaignAnalyticsService.processWebhookStatus(waMsgId, statusStr, errorReason);
                    } catch (Exception ex) {
                        log.warn("[Worker] Error updating campaign status for message {}: {}", waMsgId, ex.getMessage());
                    }
                }

                if (!waMsgId.isBlank() && messageDeliveryStatusService != null) {
                    try {
                        messageDeliveryStatusService.updateDeliveryStatus(waMsgId, statusStr);
                    } catch (Exception ex) {
                        log.warn("[Worker] Error updating delivery status for message {}: {}", waMsgId, ex.getMessage());
                    }
                }
            }
        }
        return false;
    }

    private void processEchoes(JsonNode entry, JsonNode change, MapRecord<String, String, String> record, String fieldName) {
        String streamMessageId = record.getId().toString();
        JsonNode value = change.path("value");
        if (value == null || value.isMissingNode()) return;

        JsonNode echoes = value.path("message_echoes");
        if (echoes == null || echoes.isMissingNode() || !echoes.isArray() || echoes.isEmpty()) {
            log.warn("⚠️ [Echo] Webhook field '{}' missing or empty message_echoes[] array. streamMessageId={}", fieldName, streamMessageId);
            return;
        }

        String wabaId = entry.path("id").asText("").trim();
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText("").trim();

        // Multi-identifier validation
        com.chatcrmlite.backend.models.WhatsAppConfig config = null;
        if (!wabaId.isBlank()) {
            config = whatsappConfigRepository.findByWabaId(wabaId).orElse(null);
        }
        if (config == null && !phoneNumberId.isBlank()) {
            config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        }

        if (config == null) {
            log.warn("⚠️ [Echo-Security] No WhatsAppConfig mapped for WABA '{}' or Phone '{}'. Dropping echo.", wabaId, phoneNumberId);
            return;
        }

        UUID tenantId = config.getTenant() != null ? config.getTenant().getId() : null;
        if (tenantId == null) {
            log.warn("⚠️ [Echo-Security] WhatsAppConfig id={} has no associated tenant. Dropping echo.", config.getId());
            return;
        }

        if (!phoneNumberId.isBlank() && config.getPhoneNumberId() != null && !config.getPhoneNumberId().isBlank()) {
            if (!config.getPhoneNumberId().trim().equals(phoneNumberId)) {
                log.warn("⚠️ [Echo-Security] Echo phoneNumberId '{}' does not match tenant configured phoneNumberId '{}'. Dropping.",
                        phoneNumberId, config.getPhoneNumberId());
                return;
            }
        }

        com.chatcrmlite.backend.models.Tenant tenant = config.getTenant();
        com.chatcrmlite.backend.models.User owner = null;
        if (userRepository != null && tenant != null) {
            java.util.List<com.chatcrmlite.backend.models.User> users = userRepository.findAllByTenant(tenant);
            if (!users.isEmpty()) {
                owner = users.stream()
                        .filter(u -> u.getRole() == com.chatcrmlite.backend.models.User.Role.OWNER || u.getRole() == com.chatcrmlite.backend.models.User.Role.ADMIN)
                        .findFirst()
                        .orElse(users.get(0));
            }
        }

        for (JsonNode echoNode : echoes) {
            String wamid = echoNode.path("id").asText("").trim();
            if (wamid.isBlank()) continue;

            String to = echoNode.path("to").asText("").trim();
            String msgType = echoNode.path("type").asText("text");
            long timestamp = echoNode.path("timestamp").asLong(System.currentTimeMillis() / 1000);

            // Step 1: Check if already processed
            if (idempotencyService.isProcessed(wamid, tenantId)) {
                log.info("ℹ️ [Echo-Idempotency] Echo WAMID {} already processed. Skipping duplicate.", wamid);
                continue;
            }

            // Step 2: Acquire short-lived processing lock (2 min)
            boolean lockAcquired = idempotencyService.acquireProcessingLock(wamid, tenantId, streamMessageId);
            if (!lockAcquired) {
                log.warn("⚠️ [Echo-Lock] Lock contention for WAMID {} streamMessageId={}. Leaving in stream for retry.", wamid, streamMessageId);
                throw new RuntimeException("Lock contention on WAMID " + wamid);
            }

            try {
                // Participant derived strictly from 'to' (customer)
                String customerWaId = to;
                com.chatcrmlite.backend.models.Contact contact = resolveContactForEcho(customerWaId, owner, tenant);

                // Apply time-bound bot cooldown (Instant) based on tenant configuration
                int cooldownMinutes = (config != null && config.getBotCooldownMinutes() != null && config.getBotCooldownMinutes() > 0)
                        ? config.getBotCooldownMinutes() : 15;
                contact.setBotPausedUntil(java.time.Instant.now().plus(cooldownMinutes, java.time.temporal.ChronoUnit.MINUTES));
                contact.setLastAgentReplyAt(java.time.Instant.now());
                contact.setBotPauseReason("HUMAN_AGENT_MESSAGE");
                contactRepository.save(contact);

                // Extract content
                String text = "";
                if ("text".equals(msgType)) {
                    text = echoNode.path("text").path("body").asText("");
                } else if ("image".equals(msgType) || "video".equals(msgType) || "audio".equals(msgType) || "document".equals(msgType)) {
                    text = "[" + msgType.toUpperCase() + "]";
                } else if ("interactive".equals(msgType)) {
                    text = "[Interactive message]";
                } else {
                    text = "[" + msgType + "]";
                }

                // Check for revoke
                if (echoNode.has("protocolMessage")) {
                    String protocolType = echoNode.path("protocolMessage").path("type").asText("");
                    if ("REVOKE".equalsIgnoreCase(protocolType)) {
                        String targetWamid = echoNode.path("protocolMessage").path("key").path("id").asText("");
                        if (!targetWamid.isBlank()) {
                            messageRepository.findByWaMessageId(targetWamid).ifPresent(m -> {
                                m.setContent("🚫 This message was deleted");
                                messageRepository.save(m);
                            });
                        }
                    }
                }

                // Persist OUTGOING message
                com.chatcrmlite.backend.models.Message message = com.chatcrmlite.backend.models.Message.builder()
                        .contact(contact)
                        .owner(owner)
                        .content(text)
                        .direction(com.chatcrmlite.backend.models.Message.Direction.OUTGOING)
                        .timestamp(java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(timestamp), java.time.ZoneId.systemDefault()))
                        .waMessageId(wamid)
                        .build();
                message.setTenant(tenant);
                messageRepository.save(message);

                // Real-time WebSocket broadcast to CRM Chat
                java.util.Map<String, Object> wsPayload = new java.util.HashMap<>();
                wsPayload.put("id", message.getId() != null ? message.getId().toString() : wamid);
                wsPayload.put("contactId", contact.getId().toString());
                wsPayload.put("contactName", contact.getName());
                wsPayload.put("content", text);
                wsPayload.put("direction", "OUTGOING");
                wsPayload.put("source", fieldName.toUpperCase());
                wsPayload.put("sentiment", "NEUTRAL");
                wsPayload.put("escalated", contact.isEscalated());
                distributedWebSocketPublisher.publishMessage(tenantId, wsPayload);

                // Step 3 & 4: Mark processed (72h) and release processing lock
                idempotencyService.markAsProcessedSuccess(wamid, tenantId);
                log.info("✅ [Echo] Successfully processed {} echo wamid={} for customer={}", fieldName, wamid, customerWaId);

            } catch (Exception ex) {
                idempotencyService.releaseProcessingLock(wamid, tenantId);
                throw ex;
            }
        }
    }

    private com.chatcrmlite.backend.models.Contact resolveContactForEcho(String customerWaId, com.chatcrmlite.backend.models.User owner, com.chatcrmlite.backend.models.Tenant tenant) {
        UUID tenantId = (tenant != null) ? tenant.getId() : ((owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : null);
        java.util.Optional<com.chatcrmlite.backend.models.Contact> existing = (tenantId != null) 
                ? contactRepository.findByWaIdAndTenant_Id(customerWaId, tenantId)
                : contactRepository.findByWaId(customerWaId);
        if (existing.isPresent()) {
            return existing.get();
        }
        com.chatcrmlite.backend.models.Contact newContact = com.chatcrmlite.backend.models.Contact.builder()
                .waId(customerWaId)
                .name("WhatsApp User " + customerWaId)
                .source("WhatsApp Mobile Echo")
                .owner(owner)
                .build();
        if (tenant != null) {
            newContact.setTenant(tenant);
        }
        return contactRepository.save(newContact);
    }

    private void processFlowWebhook(JsonNode entry, JsonNode change) {
        String wabaId = entry.path("id").asText("").trim();
        UUID tenantId = whatsappConfigRepository.findTenantIdByWabaId(wabaId).orElse(null);
        if (tenantId == null) {
            log.warn("⚠️ [Flow-Security] Flow webhook received for unmapped WABA ID: {}. Discarding.", wabaId);
            return;
        }

        JsonNode value = change.path("value");
        if (value == null || value.isMissingNode()) return;

        String event = value.path("event").asText("").trim();
        String flowId = value.path("flow_id").asText("").trim();
        log.info("📋 [Flow-Webhook] event='{}' flowId='{}' wabaId='{}' tenantId={}", event, flowId, wabaId, tenantId);

        switch (event) {
            case "FLOW_STATUS_CHANGE":
                String oldStatus = value.has("old_status") ? value.path("old_status").asText(null) : null;
                String newStatus = value.has("new_status") ? value.path("new_status").asText(null) : value.path("status").asText(null);
                String reason = value.has("reason") ? value.path("reason").asText(null) : null;

                if (!flowId.isBlank()) {
                    flowRepository.findByAnyMetaFlowIdAndTenantId(flowId, tenantId).ifPresent(flow -> {
                        flow.setMetaStatus(newStatus);
                        if (reason != null && !reason.isBlank()) {
                            flow.setLastMetaEventReason(reason);
                        }

                        if ("PUBLISHED".equalsIgnoreCase(newStatus)) {
                            flow.setStatus(com.chatcrmlite.backend.models.flows.FlowLifecycleStatus.PUBLISHED);
                        } else if ("DEPRECATED".equalsIgnoreCase(newStatus)) {
                            flow.setStatus(com.chatcrmlite.backend.models.flows.FlowLifecycleStatus.DEPRECATED);
                        } else if ("BLOCKED".equalsIgnoreCase(newStatus) || "THROTTLED".equalsIgnoreCase(newStatus)) {
                            flow.setStatus(com.chatcrmlite.backend.models.flows.FlowLifecycleStatus.PUBLISH_FAILED);
                        }

                        if (flow.getPublishedRevision() != null) {
                            flow.getPublishedRevision().setMetaStatus(newStatus);
                            flowRevisionRepository.save(flow.getPublishedRevision());
                        }

                        flowRepository.save(flow);
                        log.info("✅ [Flow-Webhook] Updated Flow id={} metaStatus='{}' (reason: '{}')", flow.getId(), newStatus, reason);

                        java.util.Map<String, Object> flowEvent = new java.util.HashMap<>();
                        flowEvent.put("type", "FLOW_STATUS_CHANGE");
                        flowEvent.put("flowId", flow.getId().toString());
                        flowEvent.put("metaFlowId", flowId);
                        flowEvent.put("oldStatus", oldStatus);
                        flowEvent.put("newStatus", newStatus);
                        flowEvent.put("reason", reason);
                        distributedWebSocketPublisher.publish(tenantId, "/topic/" + tenantId + "/flows", flowEvent);
                    });
                }
                break;

            case "ENDPOINT_ERROR_RATE":
            case "ENDPOINT_LATENCY":
            case "ENDPOINT_AVAILABILITY":
                double metricVal = value.path("error_rate").asDouble(value.path("latency").asDouble(0.0));
                String alertState = value.path("alert_state").asText("");
                log.warn("🚨 [Flow-Health] Alert event={} flowId={} metric={} alertState={}", event, flowId, metricVal, alertState);

                if (!flowId.isBlank()) {
                    flowRepository.findByAnyMetaFlowIdAndTenantId(flowId, tenantId).ifPresent(flow -> {
                        if (flow.getPublishedRevision() != null) {
                            flow.getPublishedRevision().setMetaHealthJson(value.toString());
                            flowRevisionRepository.save(flow.getPublishedRevision());
                        }

                        java.util.Map<String, Object> healthEvent = new java.util.HashMap<>();
                        healthEvent.put("type", event);
                        healthEvent.put("flowId", flow.getId().toString());
                        healthEvent.put("metaFlowId", flowId);
                        healthEvent.put("alertState", alertState);
                        healthEvent.put("details", value.toString());
                        distributedWebSocketPublisher.publish(tenantId, "/topic/" + tenantId + "/flows", healthEvent);
                    });
                }
                break;

            case "FLOW_VERSION_EXPIRY_WARNING":
                log.warn("⚠️ [Flow-Health] Version expiry warning for flowId={}: {}", flowId, value);
                break;

            default:
                log.warn("📋 [Flow-Webhook] Unrecognized Flow event: '{}' for flowId={}", event, flowId);
                break;
        }
    }

    private void processAccountAlerts(JsonNode entry, JsonNode change) {
        JsonNode value = change.path("value");
        String alertType = value.path("alert_type").asText(value.path("type").asText("GENERAL_ALERT"));
        String severity = value.path("alert_severity").asText("WARNING");
        String description = value.path("alert_description").asText(value.path("description").asText(""));
        String wabaId = entry.path("id").asText("");
        log.warn("⚠️ [BSP-Alert] Meta account alert for WABA {}: severity='{}' type='{}' desc='{}'", wabaId, severity, alertType, description);

        if (!wabaId.isBlank()) {
            whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                if ("CRITICAL".equalsIgnoreCase(severity)) {
                    config.setAccountStatus("ALERT_" + alertType);
                    whatsappConfigRepository.save(config);
                }
            });
        }
    }

    private void processAccountReviewUpdate(JsonNode entry, JsonNode change) {
        JsonNode value = change.path("value");
        String decision = value.path("decision").asText(value.path("event").asText(""));
        String wabaId = entry.path("id").asText("");
        String reason = value.path("rejection_reason").asText("");
        log.info("⚖️ [BSP-Review] Meta account review update for WABA {}: decision='{}' reason='{}'", wabaId, decision, reason);

        if (!wabaId.isBlank()) {
            whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                if ("APPROVED".equalsIgnoreCase(decision)) {
                    config.setAccountStatus("ACTIVE");
                    config.setVerificationStatus("VERIFIED");
                } else if ("REJECTED".equalsIgnoreCase(decision)) {
                    config.setAccountStatus("RESTRICTED");
                }
                whatsappConfigRepository.save(config);
                log.info("✅ [Worker] Applied review decision '{}' to WABA {}", decision, wabaId);
            });
        }
    }

    private void processQualityUpdate(JsonNode entry, JsonNode change) {
        JsonNode value = change.path("value");
        String event = value.path("event").asText("");
        String newQuality = value.has("quality_rating") ? value.path("quality_rating").asText("") : value.path("new_quality_rating").asText("");
        String currentLimit = value.path("current_limit").asText("");
        String wabaId = entry.path("id").asText("");
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
        log.warn("🚨 [BSP] Quality update event: {} | Quality: {} | Limit: {} | WABA: {}", event, newQuality, currentLimit, wabaId);

        if (!wabaId.isBlank()) {
            whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                if (!newQuality.isBlank()) config.setQualityRating(newQuality);
                whatsappConfigRepository.save(config);
                log.info("✅ [Worker] Updated WhatsApp quality rating to '{}' for WABA {}", newQuality, wabaId);
            });
        } else if (!phoneNumberId.isBlank()) {
            whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).ifPresent(config -> {
                if (!newQuality.isBlank()) config.setQualityRating(newQuality);
                whatsappConfigRepository.save(config);
                log.info("✅ [Worker] Updated WhatsApp quality rating to '{}' for PhoneNumberId {}", newQuality, phoneNumberId);
            });
        }
    }

    private void processPhoneNumberNameUpdate(JsonNode entry, JsonNode change) {
        JsonNode value = change.path("value");
        String newName = value.path("requested_verified_name").asText("");
        String decision = value.has("decision") ? value.path("decision").asText("") : value.path("event").asText("");
        String wabaId = entry.path("id").asText("");
        String phoneNumberId = value.path("metadata").path("phone_number_id").asText();
        log.info("ℹ️ [BSP] Phone number name update for WABA {}: name='{}' decision='{}'", wabaId, newName, decision);

        if ("APPROVED".equalsIgnoreCase(decision)) {
            if (!wabaId.isBlank()) {
                whatsappConfigRepository.findByWabaId(wabaId).ifPresent(config -> {
                    config.setVerifiedName(newName);
                    whatsappConfigRepository.save(config);
                    log.info("✅ [Worker] Updated verified name to '{}' for WABA {}", newName, wabaId);
                });
            } else if (!phoneNumberId.isBlank()) {
                whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).ifPresent(config -> {
                    config.setVerifiedName(newName);
                    whatsappConfigRepository.save(config);
                    log.info("✅ [Worker] Updated verified name to '{}' for PhoneNumberId {}", newName, phoneNumberId);
                });
            }
        }
    }

    private void processSmbAppStateSync(JsonNode entry, JsonNode change) {
        JsonNode value = change.path("value");
        String eventType = value.path("event_type").asText(value.path("action").asText("STATE_SYNC"));
        String waId = value.path("wa_id").asText(value.path("chat_id").asText(""));
        log.info("📱 [SMB-State-Sync] WhatsApp Business app state sync event: {} for waId: {}", eventType, waId);
    }

    private String unwrapPayload(String data) {
        if (data == null || data.isBlank()) return null;
        try {
            if (data.startsWith("{\"payload\":") || data.startsWith("{\"payload\" :")) {
                JsonNode node = objectMapper.readTree(data);
                if (node.has("payload")) {
                    return node.get("payload").asText();
                }
            }
            return data;
        } catch (Exception e) {
            return data;
        }
    }

    private void handleFailure(MapRecord<String, String, String> record, Exception e) {
        String messageId = record.getId().toString();
        String retryKey = "worker:retry:" + messageId;
        Long currentRetries = redisStateService.increment(retryKey, java.time.Duration.ofHours(1));

        log.error("[WhatsApp-Queue] FAILED streamMessageId={} error={}", messageId, e.getMessage(), e);

        if (currentRetries != null && currentRetries <= maxRetries) {
            log.warn("⚠️ [WhatsApp-Queue] Retry {}/{} scheduled for streamMessageId={}",
                    currentRetries, maxRetries, messageId);
        } else {
            log.error("❌ [WhatsApp-Queue] Max retries reached for streamMessageId={}. Routing to DLQ.", messageId);
            dlqHandler.moveToDlq(record, e);
            redisTemplate.opsForStream().acknowledge(groupName, record);
            redisStateService.delete(retryKey);
        }
    }
}
