package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.ConversationStateRepository;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.IdempotencyService;
import com.chatcrmlite.backend.services.workflow.ProcessingContext;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppIngressService {

    private final ContactRepository contactRepository;
    private final MessageRepository messageRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final ConversationStateRepository conversationStateRepository;
    private final IdempotencyService idempotencyService;
    private final DistributedWebSocketPublisher distributedWebSocketPublisher;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Autowired private com.chatcrmlite.backend.services.ai.SentimentAnalysisService sentimentAnalysisService;
    @org.springframework.beans.factory.annotation.Autowired private com.chatcrmlite.backend.services.lead.LeadScoringService leadScoringService;
    @org.springframework.beans.factory.annotation.Autowired private com.chatcrmlite.backend.repositories.LeadRepository leadRepository;
    @org.springframework.beans.factory.annotation.Autowired private com.chatcrmlite.backend.services.team.AgentAssignmentService agentAssignmentService;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.repositories.flows.FlowSubmissionRepository flowSubmissionRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.repositories.flows.FlowOutboxEventRepository flowOutboxEventRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.repositories.UserRepository userRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.repositories.TenantRepository tenantRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.clients.WhatsAppClient whatsappClient;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private com.chatcrmlite.backend.services.storage.CloudinaryStorageService cloudinaryStorageService;
    @org.springframework.beans.factory.annotation.Autowired(required = false) private WhatsAppMediaSizeValidator mediaSizeValidator;

    @Transactional
    public void resolveAndSaveIngress(ProcessingContext context) {
        try {
            // 1. Idempotency check FIRST - before contact resolution or DB side effects
            String messageId = context.getMessageId();
            if (messageId == null || messageId.isBlank()) {
                JsonNode root = objectMapper.readTree(context.getPayload());
                JsonNode value = root.path("entry").get(0).path("changes").get(0).path("value");
                JsonNode messageNode = value.path("messages").get(0);
                messageId = messageNode.path("id").asText();
                context.setMessageId(messageId);
            }

            if (!idempotencyService.markAsProcessing(messageId, context.getTenantId())) {
                log.info("[Idempotency] Duplicate message {} detected. Halting ingress processing.", messageId);
                context.getMetadata().put("isDuplicate", true);
                return;
            }

            // 2. Parse payload details only after idempotency claim passes
            JsonNode root = objectMapper.readTree(context.getPayload());
            JsonNode value = root.path("entry").get(0).path("changes").get(0).path("value");
            JsonNode messageNode = value.path("messages").get(0);
            JsonNode contactsNode = value.path("contacts");
            
            WhatsAppConfig config = whatsappConfigRepository.findByTenantId(context.getTenantId())
                    .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant: " + context.getTenantId()));
            
            com.chatcrmlite.backend.models.Tenant tenant = config.getTenant();
            if (tenant == null && tenantRepository != null && context.getTenantId() != null) {
                tenant = tenantRepository.findById(context.getTenantId()).orElse(null);
            }
            
            User owner = null;
            if (tenant != null && userRepository != null) {
                java.util.List<User> users = userRepository.findAllByTenant(tenant);
                if (!users.isEmpty()) {
                    owner = users.stream()
                            .filter(u -> u.getRole() == User.Role.OWNER || u.getRole() == User.Role.ADMIN)
                            .findFirst()
                            .orElse(users.get(0));
                }
            }

            // Resolve contact & save message
            String profileName = extractProfileName(contactsNode, context.getWaId());
            Contact contact = resolveContact(context.getWaId(), profileName, owner, tenant);
            
            String msgType = messageNode.path("type").asText("text");
            String text = "";
            boolean isFlowNfmReply = false;
            String flowResponseJson = null;

            Message.MessageBuilder incomingMessageBuilder = Message.builder();

            if ("text".equals(msgType)) {
                text = messageNode.path("text").path("body").asText("");
            } else if ("interactive".equals(msgType)) {
                JsonNode interactive = messageNode.path("interactive");
                String interactiveType = interactive.path("type").asText("");
                if ("nfm_reply".equals(interactiveType)) {
                    isFlowNfmReply = true;
                    flowResponseJson = interactive.path("nfm_reply").path("response_json").asText("{}");
                    text = formatFlowResponseForChat(flowResponseJson);
                } else {
                    text = interactive.path(interactiveType).path("title").asText("Interactive Response");
                }
            } else if ("image".equals(msgType) || "video".equals(msgType) || "audio".equals(msgType) || "document".equals(msgType) || "sticker".equals(msgType)) {
                JsonNode mediaNode = messageNode.path(msgType);
                text = processIncomingMedia(msgType, mediaNode, context.getTenantId(), config, incomingMessageBuilder);
            } else {
                text = "Unsupported message type: " + msgType;
            }

            // Check for SMB Message Echo (Agent replied from physical WhatsApp mobile app)
            boolean isEcho = messageNode.path("from_me").asBoolean(false)
                    || (messageNode.has("recipient_id") && !messageNode.path("recipient_id").asText().isBlank())
                    || (config.getDisplayPhoneNumber() != null && !config.getDisplayPhoneNumber().isBlank() && 
                        config.getDisplayPhoneNumber().replaceAll("[^0-9]", "").equals(context.getWaId().replaceAll("[^0-9]", "")));

            if (isEcho) {
                String customerWaId = messageNode.has("recipient_id")
                        ? messageNode.path("recipient_id").asText()
                        : (messageNode.has("to") ? messageNode.path("to").asText() : context.getWaId());

                contact = resolveContact(customerWaId, null, owner, tenant);
                saveOutgoingEchoMessage(contact, text, context.getTimestamp() / 1000, context.getMessageId(), owner, context.getTenantId());

                // Auto-pause AI bot and start 15-minute cooldown timer
                contact.setBotPaused(true);
                contact.setLastAgentReplyAt(LocalDateTime.now());
                contactRepository.save(contact);

                context.getMetadata().put("isEcho", true);
                context.getMetadata().put("botPaused", true);
                log.info("📱 [SMB-Echo] Synced agent reply from mobile WhatsApp to CRM for customer {}. 15-min bot cooldown started.", customerWaId);
                return;
            }

            // Check 15-minute inactivity cooldown for bot auto-unmute
            if (contact.isBotPaused() && contact.getLastAgentReplyAt() != null) {
                LocalDateTime now = LocalDateTime.now();
                long minutesElapsed = java.time.Duration.between(contact.getLastAgentReplyAt(), now).toMinutes();
                if (minutesElapsed >= 15) {
                    contact.setBotPaused(false);
                    contact.setLastAgentReplyAt(null);
                    contactRepository.save(contact);
                    log.info("⏰ [Bot-Cooldown] 15 minutes passed since last agent reply (elapsed: {}m). Auto-unmuting bot for waId={}", minutesElapsed, contact.getWaId());
                } else {
                    log.info("⏸️ [Bot-Cooldown] Customer waId={} within 15-min human cooldown (elapsed: {}m). Bot remains paused for manual conversation.", contact.getWaId(), minutesElapsed);
                }
            }

            saveIncomingMessage(contact, text, context.getTimestamp() / 1000, context.getMessageId(), owner, context.getTenantId(), incomingMessageBuilder);
            
            // Transactional Outbox Pattern: Ingest Flow Submission and queue FlowOutboxEvent in same ACID transaction
            if (isFlowNfmReply && flowSubmissionRepository != null && flowOutboxEventRepository != null) {
                try {
                    com.chatcrmlite.backend.models.flows.FlowSubmission submission = com.chatcrmlite.backend.models.flows.FlowSubmission.builder()
                            .eventId(context.getMessageId())
                            .contact(contact)
                            .customerPhone(context.getWaId())
                            .rawResponseJson(flowResponseJson)
                            .processingStatus(com.chatcrmlite.backend.models.flows.SubmissionProcessingStatus.RECEIVED)
                            .build();
                    if (tenant != null) {
                        submission.setTenant(tenant);
                    } else if (owner != null && owner.getTenant() != null) {
                        submission.setTenant(owner.getTenant());
                    }
                    submission = flowSubmissionRepository.save(submission);

                    com.chatcrmlite.backend.models.flows.FlowOutboxEvent outboxEvent = com.chatcrmlite.backend.models.flows.FlowOutboxEvent.builder()
                            .aggregateType("FLOW_SUBMISSION")
                            .aggregateId(submission.getId())
                            .eventType("FLOW_SUBMITTED")
                            .payloadJson(flowResponseJson)
                            .status(com.chatcrmlite.backend.models.flows.OutboxStatus.PENDING)
                            .build();
                    if (tenant != null) {
                        outboxEvent.setTenant(tenant);
                    } else if (owner != null && owner.getTenant() != null) {
                        outboxEvent.setTenant(owner.getTenant());
                    }
                    flowOutboxEventRepository.save(outboxEvent);
                    log.info("📥 [Flow-Ingress] Created FlowSubmission {} and FlowOutboxEvent {} in single ACID transaction (Tenant: {})", 
                            submission.getId(), outboxEvent.getId(), tenant != null ? tenant.getId() : "null");
                } catch (Exception ex) {
                    log.warn("⚠️ [Flow-Ingress] Error writing FlowSubmission / FlowOutboxEvent: {}", ex.getMessage());
                }
            }

            // Store metadata for next stages
            context.getMetadata().put("isNewContact", messageRepository.countByContact(contact) == 1);
            context.getMetadata().put("text", text);
            context.getMetadata().put("type", msgType);
            context.getMetadata().put("isFlowNfmReply", isFlowNfmReply);
            // Flag whether this contact is currently mid-flow so the orchestrator
            // can route free-text replies to the flow worker instead of the AI worker.
            context.getMetadata().put("hasActiveFlow", conversationStateRepository.existsActiveByContact(contact));
            context.getMetadata().put("botPaused", contact.isBotPaused());
            
            log.info("✅ [Ingress-Stage] Resolved contact {} and saved message {}", context.getWaId(), context.getMessageId());
        } catch (Exception e) {
            log.error("❌ [Ingress-Stage] Failed for {}", context.getMessageId(), e);
            throw new RuntimeException(e);
        }
    }

    private String processIncomingMedia(String type, JsonNode mediaNode, UUID tenantId, WhatsAppConfig config, Message.MessageBuilder messageBuilder) {
        String mediaId = mediaNode.path("id").asText(null);
        String mimeType = mediaNode.path("mime_type").asText(null);
        String sha256 = mediaNode.path("sha256").asText(null);
        String caption = mediaNode.has("caption") ? mediaNode.path("caption").asText(null) : null;
        String rawFilename = mediaNode.has("filename") ? mediaNode.path("filename").asText(null) : null;

        String mediaType = type.toUpperCase();
        if ("audio".equalsIgnoreCase(type) && mediaNode.path("voice").asBoolean(false)) {
            mediaType = "VOICE";
        }

        String extension = resolveExtension(mimeType, type);
        String defaultName = "whatsapp_" + type.toLowerCase() + "_" + System.currentTimeMillis() + extension;
        String fileName = (rawFilename != null && !rawFilename.isBlank()) 
                ? rawFilename.replaceAll("[^a-zA-Z0-9._-]", "_") 
                : defaultName;

        messageBuilder.mediaType(mediaType)
                .mimeType(mimeType)
                .fileName(fileName)
                .mediaId(mediaId);

        if (mediaId == null || mediaId.isBlank()) {
            log.warn("⚠️ [Media-Ingress] Missing media_id for type={}", type);
            return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + "]";
        }

        // 1. Deduplication check: check if mediaId was already uploaded previously
        try {
            Optional<Message> existingMediaMsg = messageRepository.findFirstByMediaIdOrderByTimestampDesc(mediaId);
            if (existingMediaMsg.isPresent() && existingMediaMsg.get().getMediaUrl() != null && !existingMediaMsg.get().getMediaUrl().isBlank()) {
                Message prev = existingMediaMsg.get();
                messageBuilder.mediaUrl(prev.getMediaUrl())
                        .thumbnailUrl(prev.getThumbnailUrl())
                        .fileSize(prev.getFileSize())
                        .mimeType(prev.getMimeType() != null ? prev.getMimeType() : mimeType)
                        .fileName(prev.getFileName() != null ? prev.getFileName() : fileName);
                log.info("♻️ [Media-Ingress] Reused cached mediaUrl for mediaId={} -> {}", mediaId, prev.getMediaUrl());
                return (caption != null && !caption.isBlank()) ? caption : "";
            }
        } catch (Exception ex) {
            log.debug("[Media-Ingress] Deduplication lookup skipped: {}", ex.getMessage());
        }

        // 2. Download from Meta and Upload to Cloudinary
        if (whatsappClient == null || config == null || config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            log.warn("⚠️ [Media-Ingress] WhatsApp client or access token missing. Skipping media download for mediaId={}", mediaId);
            return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + ": Download unavailable]";
        }

        try {
            // Fetch metadata
            com.chatcrmlite.backend.dto.MetaMediaDto metadata = whatsappClient.fetchMediaMetadata(mediaId, config.getAccessToken());
            if (metadata == null || metadata.getUrl() == null || metadata.getUrl().isBlank()) {
                log.warn("⚠️ [Media-Ingress] Meta returned empty download URL for mediaId={}", mediaId);
                return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + ": Download URL unavailable]";
            }

            if (metadata.getMimeType() != null && !metadata.getMimeType().isBlank()) {
                mimeType = metadata.getMimeType();
                messageBuilder.mimeType(mimeType);
            }

            long maxLimitBytes = (mediaSizeValidator != null) 
                    ? mediaSizeValidator.getMaxLimitBytes(mediaType) 
                    : 16L * 1024 * 1024;

            // 1. Pre-validate reported metadata size before streaming body
            if (mediaSizeValidator != null && !mediaSizeValidator.validateReportedSize(mediaType, metadata.getFileSize())) {
                log.warn("🚨 [Media-Ingress] Media reported size {} bytes exceeds limit of {} bytes for type={}", 
                        metadata.getFileSize(), maxLimitBytes, mediaType);
                return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + ": Size limit exceeded]";
            }

            if (metadata.getFileSize() != null && metadata.getFileSize() > 0) {
                messageBuilder.fileSize(metadata.getFileSize());
            }

            // 2. Stream directly into Cloudinary with bounded size protection
            String resourceType = mapCloudinaryResourceType(mediaType);
            String cdnUrl = whatsappClient.streamMedia(metadata.getUrl(), config.getAccessToken(), maxLimitBytes, boundedStream -> {
                if (cloudinaryStorageService != null && cloudinaryStorageService.isConfigured()) {
                    return cloudinaryStorageService.uploadTenantStream(tenantId, "whatsapp_media", fileName, boundedStream, resourceType);
                }
                return null;
            });

            if (cdnUrl != null && !cdnUrl.isBlank()) {
                messageBuilder.mediaUrl(cdnUrl);
                if ("IMAGE".equalsIgnoreCase(mediaType) || "STICKER".equalsIgnoreCase(mediaType)) {
                    messageBuilder.thumbnailUrl(cdnUrl);
                }
                log.info("✅ [Media-Ingress] Successfully streamed WhatsApp {} to Cloudinary: {}", mediaType, cdnUrl);
            } else {
                log.warn("⚠️ [Media-Ingress] Cloudinary not configured or returned empty URL for mediaId={}", mediaId);
            }

            return (caption != null && !caption.isBlank()) ? caption : "";
        } catch (Exception ex) {
            String errorMsg = ex.getMessage() != null ? ex.getMessage() : "";
            if (errorMsg.contains("exceeded configured maximum size limit") || errorMsg.contains("Size limit exceeded")) {
                log.warn("🚨 [Media-Ingress] Media size limit exceeded during streaming for mediaId={}: {}", mediaId, errorMsg);
                return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + ": Size limit exceeded]";
            }
            log.error("❌ [Media-Ingress] Failed to fetch/store media for mediaId={}: {}", mediaId, errorMsg);
            return (caption != null && !caption.isBlank()) ? caption : "[" + mediaType + ": Download failed]";
        }
    }

    private String mapCloudinaryResourceType(String mediaType) {
        return switch (mediaType.toUpperCase()) {
            case "IMAGE", "STICKER" -> "image";
            case "VIDEO", "AUDIO", "VOICE" -> "video";
            default -> "raw"; // Documents (PDF, DOCX, etc.)
        };
    }

    private String resolveExtension(String mimeType, String defaultType) {
        if (mimeType == null) return switch (defaultType.toLowerCase()) {
            case "image" -> ".jpg";
            case "video" -> ".mp4";
            case "audio", "voice" -> ".ogg";
            case "sticker" -> ".webp";
            default -> ".bin";
        };
        String lower = mimeType.toLowerCase();
        if (lower.contains("jpeg") || lower.contains("jpg")) return ".jpg";
        if (lower.contains("png")) return ".png";
        if (lower.contains("webp")) return ".webp";
        if (lower.contains("gif")) return ".gif";
        if (lower.contains("mp4")) return ".mp4";
        if (lower.contains("3gpp") || lower.contains("3gp")) return ".3gp";
        if (lower.contains("ogg") || lower.contains("opus")) return ".ogg";
        if (lower.contains("mp3") || lower.contains("mpeg")) return ".mp3";
        if (lower.contains("pdf")) return ".pdf";
        if (lower.contains("msword") || lower.contains("wordprocessingml")) return ".docx";
        if (lower.contains("excel") || lower.contains("spreadsheetml")) return ".xlsx";
        return ".bin";
    }

    private String extractProfileName(JsonNode contactsNode, String waId) {
        if (contactsNode != null && contactsNode.isArray()) {
            for (JsonNode c : contactsNode) {
                if (waId.equals(c.path("wa_id").asText())) {
                    String name = c.path("profile").path("name").asText();
                    if (name != null && !name.isBlank()) return name;
                }
            }
        }
        return null;
    }

    private Contact resolveContact(String waId, String profileName, User owner, Tenant tenant) {
        UUID tenantId = (tenant != null) ? tenant.getId() : ((owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : null);
        Optional<Contact> existing = (tenantId != null) 
                ? contactRepository.findByWaIdAndTenant_Id(waId, tenantId)
                : contactRepository.findByWaId(waId);
        if (existing.isPresent()) {
            Contact c = existing.get();
            if (profileName != null && !profileName.isBlank() && 
                (c.getName() == null || c.getName().isBlank() || 
                 c.getName().startsWith("WhatsApp User") || 
                 c.getName().startsWith("Test User") || 
                 !profileName.equals(c.getName()))) {
                log.info("[Ingress] Auto-updating contact name from '{}' to '{}' for waId={}", c.getName(), profileName, waId);
                c.setName(profileName);
                contactRepository.save(c);
            }
            return c;
        }
        User assignedOwner = owner;
        if (agentAssignmentService != null && owner != null && owner.getTenant() != null) {
            User rrAgent = agentAssignmentService.getNextRoundRobinAgent(owner.getTenant());
            if (rrAgent != null) {
                assignedOwner = rrAgent;
            }
        }
        Contact newContact = Contact.builder()
                .waId(waId)
                .name(profileName != null && !profileName.isBlank() ? profileName : "WhatsApp User " + waId)
                .source("WhatsApp")
                .owner(assignedOwner)
                .build();
        return contactRepository.save(newContact);
    }

    private void saveOutgoingEchoMessage(Contact contact, String text, long timestamp, String waMessageId, User owner, UUID tenantId) {
        Message message = Message.builder()
                .contact(contact)
                .owner(owner)
                .content(text)
                .direction(Message.Direction.OUTGOING)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault()))
                .waMessageId(waMessageId)
                .build();
        messageRepository.save(message);

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("id",          message.getId() != null ? message.getId().toString() : waMessageId);
        wsPayload.put("contactId",   contact.getId().toString());
        wsPayload.put("contactName", contact.getName());
        wsPayload.put("content",     text);
        wsPayload.put("direction",   "OUTGOING");
        wsPayload.put("source",      "WHATSAPP_MOBILE_APP");
        wsPayload.put("sentiment",   "NEUTRAL");
        wsPayload.put("escalated",   contact.isEscalated());
        UUID targetTenantId = (tenantId != null) ? tenantId : ((owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : (owner != null ? owner.getId() : null));
        if (targetTenantId != null) {
            distributedWebSocketPublisher.publishMessage(targetTenantId, wsPayload);
        }
    }

    private void saveIncomingMessage(Contact contact, String text, long timestamp, String waMessageId, User owner, UUID tenantId, Message.MessageBuilder builder) {
        Message message = (builder != null ? builder : Message.builder())
                .contact(contact)
                .owner(owner)
                .content(text)
                .direction(Message.Direction.INCOMING)
                .timestamp(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault()))
                .waMessageId(waMessageId)
                .build();
        messageRepository.save(message);

        // Perform Sentiment Analysis & Auto-Escalation check
        if (sentimentAnalysisService != null) {
            try {
                sentimentAnalysisService.analyzeAndProcessMessage(message);
            } catch (Exception e) {
                log.error("[Ingress] Sentiment analysis failed for messageId={}: {}", waMessageId, e.getMessage());
            }
        }

        // Recalculate Lead Score if lead exists
        if (leadScoringService != null && leadRepository != null) {
            try {
                leadRepository.findTopByContactOrderByCreatedAtDesc(contact).ifPresent(lead -> {
                    leadScoringService.calculateAndUpdateLeadScore(lead);
                });
            } catch (Exception e) {
                log.error("[Ingress] Lead score calculation failed for contactId={}: {}", contact.getId(), e.getMessage());
            }
        }

        Map<String, Object> wsPayload = new HashMap<>();
        wsPayload.put("id",          message.getId() != null ? message.getId().toString() : waMessageId);
        wsPayload.put("contactId",   contact.getId().toString());
        wsPayload.put("contactName", contact.getName());
        wsPayload.put("content",     text);
        wsPayload.put("direction",   "INCOMING");
        wsPayload.put("sentiment",   message.getSentiment() != null ? message.getSentiment().name() : "NEUTRAL");
        wsPayload.put("escalated",   contact.isEscalated());
        wsPayload.put("mediaUrl",    message.getMediaUrl());
        wsPayload.put("mediaType",   message.getMediaType());
        wsPayload.put("mimeType",    message.getMimeType());
        wsPayload.put("fileName",    message.getFileName());
        wsPayload.put("fileSize",    message.getFileSize());
        wsPayload.put("thumbnailUrl",message.getThumbnailUrl());

        UUID targetTenantId = (tenantId != null) ? tenantId : ((owner != null && owner.getTenant() != null) ? owner.getTenant().getId() : (owner != null ? owner.getId() : null));
        if (targetTenantId != null) {
            distributedWebSocketPublisher.publishMessage(targetTenantId, wsPayload);
        }
    }

    private String formatFlowResponseForChat(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return "📋 WhatsApp Flow Form Submitted";
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isObject()) {
                StringBuilder sb = new StringBuilder();
                sb.append("📋 *Form Submission:*\n");
                node.fields().forEachRemaining(entry -> {
                    String rawKey = entry.getKey().replace('_', ' ');
                    String prettyKey = java.util.Arrays.stream(rawKey.split(" "))
                            .filter(w -> !w.isBlank())
                            .map(w -> Character.toUpperCase(w.charAt(0)) + (w.length() > 1 ? w.substring(1) : ""))
                            .collect(java.util.stream.Collectors.joining(" "));
                    String val = entry.getValue().asText();
                    if (!val.isBlank()) {
                        sb.append("• *").append(prettyKey).append("*: ").append(val).append("\n");
                    }
                });
                return sb.toString().trim();
            }
        } catch (Exception e) {
            log.warn("[Ingress] Failed to parse flow JSON for chat: {}", e.getMessage());
        }
        return "📋 Form Submitted: " + json;
    }
}
