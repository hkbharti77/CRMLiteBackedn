package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import com.chatcrmlite.backend.repositories.voice.VoiceSessionRepository;
import com.chatcrmlite.backend.services.storage.CloudinaryStorageService;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import com.chatcrmlite.backend.utils.WavHeaderUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Slf4j
@Service
public class WhatsAppCallingAgentService {

    private final WhatsAppCallSessionRepository callSessionRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final WhatsAppPhoneNumberConfigRepository phoneConfigRepository;
    private final InboundCallPolicyService inboundCallPolicyService;
    private final OutboundCallPermissionService outboundCallPermissionService;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final StringRedisTemplate redisTemplate;
    private final WhatsAppWebRtcMediaGateway mediaGateway;
    private final CloudinaryStorageService cloudinaryStorageService;
    private final VoiceSessionRepository voiceSessionRepository;

    @Autowired
    public WhatsAppCallingAgentService(
            WhatsAppCallSessionRepository callSessionRepository,
            WhatsAppConfigRepository whatsappConfigRepository,
            WhatsAppPhoneNumberConfigRepository phoneConfigRepository,
            InboundCallPolicyService inboundCallPolicyService,
            OutboundCallPermissionService outboundCallPermissionService,
            MetaWhatsAppClient metaWhatsAppClient,
            StringRedisTemplate redisTemplate,
            WhatsAppWebRtcMediaGateway mediaGateway,
            CloudinaryStorageService cloudinaryStorageService,
            VoiceSessionRepository voiceSessionRepository
    ) {
        this.callSessionRepository = callSessionRepository;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.phoneConfigRepository = phoneConfigRepository;
        this.inboundCallPolicyService = inboundCallPolicyService;
        this.outboundCallPermissionService = outboundCallPermissionService;
        this.metaWhatsAppClient = metaWhatsAppClient;
        this.redisTemplate = redisTemplate;
        this.mediaGateway = mediaGateway;
        this.cloudinaryStorageService = cloudinaryStorageService;
        this.voiceSessionRepository = voiceSessionRepository;
    }

    public WhatsAppCallingAgentService(
            WhatsAppCallSessionRepository callSessionRepository,
            WhatsAppConfigRepository whatsappConfigRepository,
            WhatsAppPhoneNumberConfigRepository phoneConfigRepository,
            InboundCallPolicyService inboundCallPolicyService,
            OutboundCallPermissionService outboundCallPermissionService,
            MetaWhatsAppClient metaWhatsAppClient,
            StringRedisTemplate redisTemplate,
            WhatsAppWebRtcMediaGateway mediaGateway
    ) {
        this(callSessionRepository, whatsappConfigRepository, phoneConfigRepository,
             inboundCallPolicyService, outboundCallPermissionService, metaWhatsAppClient,
             redisTemplate, mediaGateway, null, null);
    }

    @Value("${crmlite.calling.sdp.ttl-seconds:300}")
    private long sdpTtlSeconds;

    /**
     * Entry point for processing Meta 'calls' and 'statuses' webhook envelopes
     */
    @Transactional
    public void processWebhookEnvelope(WhatsAppCallWebhookEnvelope envelope) {
        if (envelope == null || envelope.entry() == null) return;

        for (var entry : envelope.entry()) {
            if (entry.changes() == null) continue;
            String wabaId = entry.id();

            for (var change : entry.changes()) {
                if (!"calls".equalsIgnoreCase(change.field()) || change.value() == null) continue;
                var val = change.value();
                String phoneNumberId = val.metadata() != null ? val.metadata().phoneNumberId() : null;

                UUID tenantId = null;
                if (phoneNumberId != null && !phoneNumberId.isBlank()) {
                    tenantId = whatsappConfigRepository.findTenantIdByPhoneNumberId(phoneNumberId).orElse(null);
                }
                if (tenantId == null && wabaId != null && !wabaId.isBlank()) {
                    tenantId = whatsappConfigRepository.findTenantIdByWabaId(wabaId).orElse(null);
                }

                if (tenantId == null) {
                    log.warn("⚠️ [WhatsAppCallAgent] Tenant not found for wabaId={} phoneNumberId={}", wabaId, phoneNumberId);
                    continue;
                }

                // Process calls array (lifecycle events)
                if (val.calls() != null) {
                    for (var callEvent : val.calls()) {
                        processCallEvent(tenantId, wabaId, phoneNumberId, callEvent);
                    }
                }

                // Process statuses array (business-initiated status events)
                if (val.statuses() != null) {
                    for (var callStatus : val.statuses()) {
                        processCallStatus(tenantId, phoneNumberId, callStatus);
                    }
                }
            }
        }
    }

    private void processCallEvent(UUID tenantId, String wabaId, String phoneNumberId, WhatsAppCallWebhookEnvelope.CallEvent callEvent) {
        String callId = callEvent.id();
        if (callId == null || callId.isBlank()) {
            log.warn("⚠️ [WhatsAppCallAgent] Received CallEvent with missing call ID, ignoring");
            return;
        }

        String eventType = callEvent.event();
        String fromWaId = callEvent.from();
        String toWaId = callEvent.to();
        String direction = callEvent.direction() != null ? callEvent.direction() : "USER_INITIATED";

        log.info("📞 [WhatsAppCallAgent] Event event='{}' callId='{}' dir='{}' from='{}'", eventType, callId, direction, fromWaId);

        if ("CONNECT".equalsIgnoreCase(eventType)) {
            if ("USER_INITIATED".equalsIgnoreCase(direction)) {
                handleInboundConnect(tenantId, wabaId, phoneNumberId, callId, fromWaId, toWaId, callEvent);
            } else {
                handleOutboundConnectAnswer(tenantId, callId, callEvent);
            }
        } else if ("TERMINATED".equalsIgnoreCase(eventType) || "TERMINATE".equalsIgnoreCase(eventType) || "FAILED".equalsIgnoreCase(eventType)) {
            handleCallTerminated(tenantId, callId, callEvent);
        }
    }

    private void handleInboundConnect(UUID tenantId, String wabaId, String phoneNumberId, String callId, String fromWaId, String toWaId, WhatsAppCallWebhookEnvelope.CallEvent event) {
        // Prevent late or duplicate CONNECT from resurrecting or re-processing
        WhatsAppCallSession existing = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (existing != null) {
            String s = existing.getState();
            if ("ENDED".equalsIgnoreCase(s) || "TERMINATED".equalsIgnoreCase(s) || "REJECTED".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s)) {
                log.warn("⚠️ [WhatsAppCallAgent] Ignoring late CONNECT for already finalized callId={} (state={})", callId, s);
                return;
            }
            if ("ACTIVE".equalsIgnoreCase(s) || "ACCEPTING".equalsIgnoreCase(s) || "MEDIA_NEGOTIATING".equalsIgnoreCase(s) || "PRE_ACCEPTING".equalsIgnoreCase(s) || "ACCEPTED_200_OK".equalsIgnoreCase(s)) {
                log.info("ℹ️ [WhatsAppCallAgent] Ignoring duplicate CONNECT for in-flight/active callId={} (state={})", callId, s);
                return;
            }
        }

        boolean canAccept = inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, fromWaId);
        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        String token = config != null ? config.getAccessToken() : null;

        if (!canAccept || token == null) {
            log.warn("⛔ [WhatsAppCallAgent] Rejecting callId={} (policy or token missing)", callId);
            if (token != null) {
                try {
                    metaWhatsAppClient.rejectIncomingCall(phoneNumberId, token, callId, "USER_BUSY");
                } catch (Exception e) {
                    log.warn("⚠️ [WhatsAppCallAgent] Meta reject API failed for callId={}: {}", callId, e.getMessage());
                }
            }
            saveCallSession(tenantId, wabaId, phoneNumberId, callId, "USER_INITIATED", fromWaId, toWaId, "REJECTED", event.session());
            return;
        }

        // 1. Inbound State Machine: RECEIVED -> PRE_ACCEPTING -> MEDIA_NEGOTIATING
        WhatsAppCallSession session = saveCallSession(tenantId, wabaId, phoneNumberId, callId, "USER_INITIATED", fromWaId, toWaId, "PRE_ACCEPTING", event.session());

        // Ephemeral SDP offer storage in Redis
        if (event.session() != null && event.session().sdp() != null) {
            storeRedisSdp("wa:sdp:offer:" + callId, event.session().sdp());
        }

        try {
            // 2. Generate SDP Answer (WebRTC Engine)
            String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, fromWaId, event.session() != null ? event.session().sdp() : null);
            if (sdpAnswer != null) {
                storeRedisSdp("wa:sdp:answer:" + callId, sdpAnswer);
                session.setSdpAnswerHash(DigestUtils.sha256Hex(sdpAnswer));
            }

            // 3. Issue PRE_ACCEPT Graph Call
            session.setState("MEDIA_NEGOTIATING");
            metaWhatsAppClient.preAcceptCall(phoneNumberId, token, callId, sdpAnswer);

            // Promptly start outbound media pipeline (DTLS handshake) so WebRTC connects concurrently
            mediaGateway.startOutboundMedia(callId);

            // 4. Issue ACCEPT Graph Call -> MEDIA_READY -> ACCEPTED_200_OK -> ACTIVE
            session.setState("ACCEPTING");
            metaWhatsAppClient.acceptIncomingCall(phoneNumberId, token, callId);

            session.setState("ACCEPTED_200_OK");
            session.setConnectedAt(Instant.now());
            session.setState("ACTIVE");
            callSessionRepository.save(session);

            log.info("📞 [WhatsAppCallAgent] Call callId={} accepted. Initiated WebRTC media pipeline", callId);
        } catch (Exception e) {
            log.error("❌ [WhatsAppCallAgent] Call setup failed for callId={}: {}", callId, e.getMessage(), e);
            mediaGateway.terminateSession(callId);
            session.setState("FAILED");
            session.setEndedAt(Instant.now());
            session.setFailureStage("SETUP");
            session.setMetaErrorTitle("CALL_SETUP_FAILED");
            session.setMetaErrorMessage(e.getMessage());
            callSessionRepository.save(session);
            cleanupRedisSdp(callId);
        }
    }

    private void handleOutboundConnectAnswer(UUID tenantId, String callId, WhatsAppCallWebhookEnvelope.CallEvent event) {
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session == null) {
            log.warn("⚠️ [WhatsAppCallAgent] Outbound call session not found for callId={}", callId);
            return;
        }

        // CRITICAL: A duplicate or late webhook must NEVER reopen an already terminated call
        String s = session.getState();
        if ("ENDED".equalsIgnoreCase(s) || "TERMINATED".equalsIgnoreCase(s) || "FAILED".equalsIgnoreCase(s) || "REJECTED".equalsIgnoreCase(s)) {
            log.warn("⚠️ [WhatsAppCallAgent] Ignoring late outbound CONNECT for finalized callId={} (state={})", callId, s);
            return;
        }
        if ("ACTIVE".equalsIgnoreCase(s)) {
            log.info("ℹ️ [WhatsAppCallAgent] Ignoring duplicate outbound CONNECT for already active callId={}", callId);
            return;
        }

        try {
            // Outbound State Machine: OFFER_SENT -> SDP_ANSWER_RECEIVED -> MEDIA_NEGOTIATING -> MEDIA_CONNECTED -> ACTIVE
            session.setState("SDP_ANSWER_RECEIVED");
            if (event.session() != null && event.session().sdp() != null) {
                String sdpAnswer = event.session().sdp();
                storeRedisSdp("wa:sdp:answer:" + callId, sdpAnswer);
                session.setSdpAnswerHash(DigestUtils.sha256Hex(sdpAnswer));
            }

            session.setState("MEDIA_NEGOTIATING");
            session.setState("MEDIA_CONNECTED");
            session.setState("ACTIVE");
            session.setConnectedAt(Instant.now());
            callSessionRepository.save(session);

            // Promptly start outbound RTP comfort stream
            mediaGateway.startOutboundMedia(callId);

            outboundCallPermissionService.recordLimitEvent(tenantId, session.getPhoneNumberId(), session.getToWaId(), "CALL_CONNECTED", callId);
            log.info("✅ [WhatsAppCallAgent] Outbound call callId={} transition to ACTIVE and outbound media started", callId);
        } catch (Exception e) {
            log.error("❌ [WhatsAppCallAgent] Outbound call connection failed for callId={}: {}", callId, e.getMessage(), e);
            mediaGateway.terminateSession(callId);
            session.setState("FAILED");
            session.setEndedAt(Instant.now());
            session.setFailureStage("ANSWER");
            session.setMetaErrorTitle("CALL_SETUP_FAILED");
            session.setMetaErrorMessage(e.getMessage());
            callSessionRepository.save(session);
            cleanupRedisSdp(callId);
        }
    }

    /**
     * Initiates a Business-Initiated Outbound Call to a WhatsApp user
     */
    @Transactional
    public String initiateOutboundCall(UUID tenantId, String phoneNumberId, String toWaId, String bizOpaqueCallbackData) {
        log.info("📞 [WhatsAppCallAgent] Initiating outbound call tenantId={} phone={} to={}", tenantId, phoneNumberId, toWaId);

        // 1. Permission & Limits Gate
        boolean canCall = outboundCallPermissionService.canInitiateOutboundCall(tenantId, phoneNumberId, toWaId);
        if (!canCall) {
            log.warn("⛔ [WhatsAppCallAgent] Outbound call blocked by permissions or rate limits for toWaId={}", toWaId);
            throw new IllegalStateException("Outbound call not permitted: active permission required or rate limit reached");
        }

        // 2. Concurrency Limit Gate
        var configOpt = phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId);
        int maxConcurrent = configOpt.map(c -> c.getMaxConcurrentCalls()).filter(m -> m != null && m > 0).orElse(5);
        long activeCalls = callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId);
        if (activeCalls >= maxConcurrent) {
            log.warn("⛔ [WhatsAppCallAgent] Outbound call rejected - Concurrency limit reached for phone={}: {}/{}", phoneNumberId, activeCalls, maxConcurrent);
            throw new IllegalStateException("Concurrency limit reached on phone number: " + activeCalls + "/" + maxConcurrent);
        }

        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (config == null || config.getAccessToken() == null) {
            throw new IllegalStateException("WhatsAppConfig missing for phoneNumberId=" + phoneNumberId);
        }

        String callId = "wa_call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 3. Generate local WebRTC SDP Offer
        String sdpOffer = mediaGateway.createMediaSession(callId, tenantId, toWaId, null);
        storeRedisSdp("wa:sdp:offer:" + callId, sdpOffer);

        // 4. Persist session state: OFFER_SENT
        WhatsAppCallSession session = WhatsAppCallSession.builder()
                .wabaId(config.getWabaId())
                .phoneNumberId(phoneNumberId)
                .callId(callId)
                .direction("BUSINESS_INITIATED")
                .toWaId(toWaId)
                .startedAt(Instant.now())
                .build();
        session.setTenantId(tenantId);
        session.setState("OFFER_SENT");
        session.setSdpOfferHash(DigestUtils.sha256Hex(sdpOffer));
        callSessionRepository.save(session);

        // 5. Issue Meta POST /calls (action=connect)
        try {
            metaWhatsAppClient.initiateBusinessCall(phoneNumberId, config.getAccessToken(), toWaId, sdpOffer, bizOpaqueCallbackData);
        } catch (Exception e) {
            log.error("❌ [WhatsAppCallAgent] Meta initiateBusinessCall failed for callId={}: {}", callId, e.getMessage(), e);
            mediaGateway.terminateSession(callId);
            session.setState("FAILED");
            session.setEndedAt(Instant.now());
            session.setFailureStage("INITIATION");
            session.setMetaErrorTitle("CALL_SETUP_FAILED");
            session.setMetaErrorMessage(e.getMessage());
            callSessionRepository.save(session);
            cleanupRedisSdp(callId);
            throw new IllegalStateException("Failed to initiate outbound call with Meta: " + e.getMessage(), e);
        }

        // 6. Record Call Limit Event
        outboundCallPermissionService.recordLimitEvent(tenantId, phoneNumberId, toWaId, "CALL_INITIATED", callId);

        log.info("🚀 [WhatsAppCallAgent] Outbound call initiated successfully with callId={}", callId);
        return callId;
    }

    /**
     * Terminate an active call on demand (human hangup or AI turn finish)
     */
    @Transactional
    public void terminateActiveCall(UUID tenantId, String callId) {
        log.info("📞 [WhatsAppCallAgent] Manual termination requested for tenantId={} callId={}", tenantId, callId);

        // TENANT ISOLATION: verify call belongs to the tenant BEFORE terminating media session
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session == null) {
            log.warn("⚠️ [WhatsAppCallAgent] Cannot terminate call: callId={} not found for tenantId={}", callId, tenantId);
            return;
        }

        // IDEMPOTENCY: if call is already TERMINATED or ENDED, do not re-invoke Meta or recalculate
        if ("TERMINATED".equalsIgnoreCase(session.getState()) || "ENDED".equalsIgnoreCase(session.getState())) {
            log.info("ℹ️ [WhatsAppCallAgent] Call callId={} is already terminated, ignoring duplicate termination", callId);
            return;
        }

        byte[] recordedPcm = mediaGateway.getRecordedAudio(callId);
        mediaGateway.terminateSession(callId);
        if (recordedPcm == null || recordedPcm.length == 0) {
            recordedPcm = mediaGateway.getRecordedAudio(callId);
        }

        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(session.getPhoneNumberId()).orElse(null);
        if (config != null && config.getAccessToken() != null) {
            try {
                metaWhatsAppClient.terminateCall(session.getPhoneNumberId(), config.getAccessToken(), callId);
            } catch (Exception e) {
                log.warn("⚠️ [WhatsAppCallAgent] Meta terminate API error for callId={}: {}", callId, e.getMessage());
            }
        }
        session.setState("TERMINATED");
        session.setEndedAt(Instant.now());
        if (session.getConnectedAt() != null) {
            session.setDurationSeconds((int) Duration.between(session.getConnectedAt(), Instant.now()).getSeconds());
        }

        boolean hasAudio = (recordedPcm != null && recordedPcm.length > 0);
        boolean isCloudinaryConfigured = (cloudinaryStorageService != null && cloudinaryStorageService.isConfigured());
        boolean alreadyAvailable = "AVAILABLE".equalsIgnoreCase(session.getRecordingStatus())
                && session.getRecordingUrl() != null && !session.getRecordingUrl().isBlank();

        if (!alreadyAvailable) {
            if (hasAudio && isCloudinaryConfigured) {
                session.setRecordingStatus("PROCESSING");
            } else if (!"PROCESSING".equalsIgnoreCase(session.getRecordingStatus())) {
                session.setRecordingStatus("NOT_AVAILABLE");
            }
        }

        callSessionRepository.save(session);

        if (hasAudio && isCloudinaryConfigured && !alreadyAvailable) {
            finalizeAndUploadRecordingAsync(session, recordedPcm);
        }
        cleanupRedisSdp(callId);
    }

    private void processCallStatus(UUID tenantId, String phoneNumberId, WhatsAppCallWebhookEnvelope.CallStatus status) {
        String callId = status.id();
        String statusVal = status.status();
        log.info("📞 [WhatsAppCallAgent] Out-of-band status update callId='{}' status='{}'", callId, statusVal);

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session != null) {
            if ("RINGING".equalsIgnoreCase(statusVal)) {
                if ("OFFER_SENT".equalsIgnoreCase(session.getState())) {
                    session.setState("RINGING");
                    callSessionRepository.save(session);
                }
            } else if ("REJECTED".equalsIgnoreCase(statusVal)) {
                if (!"TERMINATED".equalsIgnoreCase(session.getState()) && !"ENDED".equalsIgnoreCase(session.getState())) {
                    session.setState("REJECTED");
                    session.setEndedAt(Instant.now());
                    if (status.errors() != null && !status.errors().isEmpty()) {
                        var err = status.errors().get(0);
                        session.setMetaErrorCode(err.code());
                        session.setMetaErrorTitle(err.title());
                        session.setMetaErrorMessage(err.message());
                    }
                    callSessionRepository.save(session);
                    mediaGateway.terminateSession(callId);
                    cleanupRedisSdp(callId);
                    outboundCallPermissionService.recordLimitEvent(tenantId, phoneNumberId, session.getToWaId(), "CALL_REJECTED", callId);
                }
            } else if ("FAILED".equalsIgnoreCase(statusVal) || "MISSED".equalsIgnoreCase(statusVal) || "TIMEOUT".equalsIgnoreCase(statusVal)) {
                if (!"TERMINATED".equalsIgnoreCase(session.getState()) && !"ENDED".equalsIgnoreCase(session.getState())) {
                    session.setState(statusVal.toUpperCase());
                    session.setEndedAt(Instant.now());
                    if (status.errors() != null && !status.errors().isEmpty()) {
                        var err = status.errors().get(0);
                        session.setMetaErrorCode(err.code());
                        session.setMetaErrorTitle(err.title());
                        session.setMetaErrorMessage(err.message());
                    }
                    callSessionRepository.save(session);
                    mediaGateway.terminateSession(callId);
                    cleanupRedisSdp(callId);
                }
            }
        }
    }

    private void handleCallTerminated(UUID tenantId, String callId, WhatsAppCallWebhookEnvelope.CallEvent event) {
        // Capture precise media-plane cause BEFORE tearing down the UDP session
        String mediaFailure = mediaGateway.resolveMediaFailureReason(callId);
        WhatsAppWebRtcMediaGateway.CallMediaState mediaState = mediaGateway.getMediaState(callId);
        byte[] recordedPcm = mediaGateway.getRecordedAudio(callId);
        mediaGateway.terminateSession(callId);
        if (mediaFailure == null) {
            mediaFailure = mediaGateway.resolveMediaFailureReason(callId);
        }
        if (mediaState == null) {
            mediaState = mediaGateway.getMediaState(callId);
        }
        if (recordedPcm == null || recordedPcm.length == 0) {
            recordedPcm = mediaGateway.getRecordedAudio(callId);
        }

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session != null) {
            boolean alreadyFinalized = "TERMINATED".equalsIgnoreCase(session.getState()) || "ENDED".equalsIgnoreCase(session.getState());

            if (!alreadyFinalized) {
                session.setState("TERMINATED");
                session.setEndedAt(Instant.now());
                if (event.duration() != null && event.duration() > 0) {
                    session.setDurationSeconds(event.duration());
                } else if (session.getConnectedAt() != null) {
                    session.setDurationSeconds((int) Duration.between(session.getConnectedAt(), Instant.now()).getSeconds());
                }
            }

            if (event.errors() != null && !event.errors().isEmpty()) {
                WhatsAppCallWebhookEnvelope.CallError firstErr = event.errors().get(0);
                int code = firstErr.code() != null ? firstErr.code() : 0;
                CallingErrorMapper.CallingErrorDetails mapped = CallingErrorMapper.map(code);

                // Do not hide a DTLS failure (or RTP_NOT_RECEIVED) behind generic MEDIA_RECEIVE_TIMEOUT
                if (code == 138021 && mediaFailure != null) {
                    if (mediaFailure.startsWith("DTLS_FAILED") || mediaFailure.contains("DTLS")) {
                        mapped = new CallingErrorMapper.CallingErrorDetails(
                                "DTLS_FAILED", false,
                                mediaFailure + " | mediaState=" + mediaState);
                    } else if ("RTP_NOT_RECEIVED".equals(mediaFailure) || mediaFailure.contains("RTP_NOT_RECEIVED")) {
                        mapped = new CallingErrorMapper.CallingErrorDetails(
                                "RTP_NOT_RECEIVED", true,
                                "ICE_CONNECTED DTLS_CONNECTED SRTP_READY but no inbound RTP | mediaState=" + mediaState);
                    }
                } else if (mediaFailure != null && mediaFailure.startsWith("DTLS_FAILED")) {
                    mapped = new CallingErrorMapper.CallingErrorDetails("DTLS_FAILED", false, mediaFailure);
                }

                session.setMetaErrorCode(firstErr.code());
                session.setMetaErrorTitle(mapped.internalCode());
                session.setMetaErrorMessage(firstErr.message() != null
                        ? firstErr.message() + " | " + mapped.description()
                        : mapped.description());
                log.warn("⚠️ [WhatsAppCallAgent] Call callId={} terminated with error code={} internal={} mediaFailure={} mediaState={}",
                        callId, firstErr.code(), mapped.internalCode(), mediaFailure, mediaState);
            } else if (mediaFailure != null) {
                session.setMetaErrorTitle(mediaFailure.startsWith("DTLS") ? "DTLS_FAILED" : mediaFailure);
                session.setMetaErrorMessage(mediaFailure);
                log.warn("⚠️ [WhatsAppCallAgent] Call callId={} terminated with mediaFailure={} mediaState={}",
                        callId, mediaFailure, mediaState);
            }

            boolean hasAudio = (recordedPcm != null && recordedPcm.length > 0);
            boolean isCloudinaryConfigured = (cloudinaryStorageService != null && cloudinaryStorageService.isConfigured());
            boolean alreadyAvailable = "AVAILABLE".equalsIgnoreCase(session.getRecordingStatus())
                    && session.getRecordingUrl() != null && !session.getRecordingUrl().isBlank();

            if (!alreadyAvailable) {
                if (hasAudio && isCloudinaryConfigured) {
                    session.setRecordingStatus("PROCESSING");
                } else if (!"PROCESSING".equalsIgnoreCase(session.getRecordingStatus())) {
                    session.setRecordingStatus("NOT_AVAILABLE");
                }
            }

            callSessionRepository.save(session);

            if (hasAudio && isCloudinaryConfigured && !alreadyAvailable) {
                finalizeAndUploadRecordingAsync(session, recordedPcm);
            }
        }

        cleanupRedisSdp(callId);
        log.info("📞 [WhatsAppCallAgent] Terminated session for callId={}", callId);
    }

    @Value("${app.recording.spool-dir:./data/recordings/spool}")
    private String spoolDirPath = "./data/recordings/spool";

    private final java.util.Map<String, byte[]> failedUploadAudios = new java.util.concurrent.ConcurrentHashMap<>();

    private Path getSpoolDirectory() {
        Path p = Paths.get(spoolDirPath);
        try {
            if (!Files.exists(p)) {
                Files.createDirectories(p);
            }
        } catch (Exception e) {
            log.warn("⚠️ [RecordingSpool] Could not create spool directory: {}", e.getMessage());
        }
        return p;
    }

    private String buildSpoolFilename(UUID tenantId, String callId) {
        String encodedCallId = Base64.getUrlEncoder().withoutPadding().encodeToString(callId.getBytes(StandardCharsets.UTF_8));
        return (tenantId != null ? tenantId.toString() : "unknown") + "__" + encodedCallId + ".wav";
    }

    public void saveToSpool(UUID tenantId, String callId, byte[] wavAudio) {
        if (callId == null || wavAudio == null || wavAudio.length == 0) return;
        try {
            Path file = getSpoolDirectory().resolve(buildSpoolFilename(tenantId, callId));
            Files.write(file, wavAudio, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("💾 [RecordingSpool] Persisted recording to spool: callId={} size={}B", callId, wavAudio.length);
        } catch (Exception e) {
            log.warn("⚠️ [RecordingSpool] Failed to write spool file for callId={}: {}", callId, e.getMessage());
        }
    }

    public void deleteFromSpool(UUID tenantId, String callId) {
        if (callId == null) return;
        try {
            Path file = getSpoolDirectory().resolve(buildSpoolFilename(tenantId, callId));
            Files.deleteIfExists(file);
            log.info("🗑️ [RecordingSpool] Removed recording from spool: callId={}", callId);
        } catch (Exception e) {
            log.warn("⚠️ [RecordingSpool] Failed to delete spool file for callId={}: {}", callId, e.getMessage());
        }
    }

    public byte[] readFromSpool(UUID tenantId, String callId) {
        if (callId == null) return null;
        try {
            if (tenantId != null) {
                Path file = getSpoolDirectory().resolve(buildSpoolFilename(tenantId, callId));
                if (Files.exists(file)) {
                    return Files.readAllBytes(file);
                }
            } else {
                // If tenantId is not known, scan spool for this callId
                String encodedCallId = Base64.getUrlEncoder().withoutPadding().encodeToString(callId.getBytes(StandardCharsets.UTF_8));
                Path dir = getSpoolDirectory();
                if (Files.exists(dir)) {
                    try (var stream = Files.list(dir)) {
                        for (Path p : stream.toList()) {
                            if (p.getFileName().toString().endsWith("__" + encodedCallId + ".wav")) {
                                return Files.readAllBytes(p);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [RecordingSpool] Failed to read spool file for callId={}: {}", callId, e.getMessage());
        }
        return null;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverSpoolOnStartup() {
        try {
            Path dir = getSpoolDirectory();
            if (!Files.exists(dir)) return;
            try (var stream = Files.list(dir)) {
                for (Path file : stream.toList()) {
                    String fname = file.getFileName().toString();
                    if (!fname.endsWith(".wav") || !fname.contains("__")) continue;
                    String[] parts = fname.substring(0, fname.length() - 4).split("__");
                    if (parts.length != 2) continue;
                    try {
                        UUID tenantId = UUID.fromString(parts[0]);
                        String callId = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                        byte[] spooled = Files.readAllBytes(file);
                        failedUploadAudios.put(tenantId.toString() + ":" + callId, spooled);
                        log.info("🔄 [RecordingSpool] Recovered spooled recording after process restart: tenantId={} callId={}", tenantId, callId);

                        // If call session is still in PROCESSING or FAILED, attempt background retry
                        callSessionRepository.findByTenantIdAndCallId(tenantId, callId).ifPresent(session -> {
                            if (!"AVAILABLE".equalsIgnoreCase(session.getRecordingStatus())) {
                                log.info("🚀 [RecordingSpool] Auto-resuming upload for recovered callId={}", callId);
                                retryUploadAsync(session, spooled);
                            }
                        });
                    } catch (Exception parseEx) {
                        log.warn("⚠️ [RecordingSpool] Skipping unparseable spool file {}: {}", fname, parseEx.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ [RecordingSpool] Startup recovery error: {}", e.getMessage());
        }
    }

    public byte[] getFailedRecordingAudio(UUID tenantId, String callId) {
        if (callId == null) return null;
        if (tenantId != null) {
            String cacheKey = tenantId.toString() + ":" + callId;
            byte[] inMem = failedUploadAudios.get(cacheKey);
            if (inMem != null && inMem.length > 0) {
                return inMem;
            }
            byte[] spooled = readFromSpool(tenantId, callId);
            if (spooled != null && spooled.length > 0) {
                failedUploadAudios.put(cacheKey, spooled);
                return spooled;
            }
            return null;
        }

        // Fallback for null tenantId
        return getFailedRecordingAudio(callId);
    }

    public byte[] getFailedRecordingAudio(String callId) {
        if (callId == null) return null;
        for (var entry : failedUploadAudios.entrySet()) {
            if (entry.getKey().endsWith(":" + callId)) {
                return entry.getValue();
            }
        }
        byte[] spooled = readFromSpool(null, callId);
        if (spooled != null && spooled.length > 0) {
            failedUploadAudios.put("unknown:" + callId, spooled);
            return spooled;
        }
        return null;
    }

    public void retryUploadAsync(WhatsAppCallSession session, byte[] wavAudio) {
        if (session == null || wavAudio == null || wavAudio.length == 0) return;
        UUID tenantId = session.getTenantId();
        String callId = session.getCallId();
        String cacheKey = (tenantId != null ? tenantId.toString() : "unknown") + ":" + callId;

        CompletableFuture.runAsync(() -> {
            try {
                CloudinaryStorageService.AudioUploadResult uploadResult =
                        cloudinaryStorageService.uploadCallRecording(tenantId, callId, wavAudio);
                failedUploadAudios.remove(cacheKey);
                deleteFromSpool(tenantId, callId);

                WhatsAppCallSession currentSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
                if (currentSession != null) {
                    currentSession.setRecordingUrl(uploadResult.secureUrl());
                    currentSession.setRecordingPublicId(uploadResult.publicId());
                    currentSession.setRecordingFormat(uploadResult.format() != null ? uploadResult.format() : "mp3");
                    currentSession.setRecordingSizeBytes(uploadResult.sizeBytes());
                    if (uploadResult.durationSeconds() != null && uploadResult.durationSeconds() > 0) {
                        currentSession.setRecordingDurationSeconds(uploadResult.durationSeconds());
                    } else if (currentSession.getDurationSeconds() != null && currentSession.getDurationSeconds() > 0) {
                        currentSession.setRecordingDurationSeconds(currentSession.getDurationSeconds());
                    }
                    currentSession.setRecordingStatus("AVAILABLE");
                    callSessionRepository.save(currentSession);
                    log.info("✅ [WhatsAppCallAgent] Manual/Durable retry succeeded: callId={} publicId={}", callId, uploadResult.publicId());
                }
            } catch (Exception e) {
                log.error("❌ [WhatsAppCallAgent] Manual retry failed for callId={}: {}", callId, e.getMessage());
                try {
                    WhatsAppCallSession currentSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
                    if (currentSession != null) {
                        currentSession.setRecordingStatus("FAILED");
                        callSessionRepository.save(currentSession);
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    /**
     * Asynchronously uploads call audio recording to Cloudinary with bounded exponential backoff.
     * Guaranteed non-blocking to preserve <50ms webhook response times.
     */
    private void finalizeAndUploadRecordingAsync(WhatsAppCallSession session, byte[] pcmAudio) {
        if (session == null || pcmAudio == null || pcmAudio.length == 0) return;
        if (cloudinaryStorageService == null || !cloudinaryStorageService.isConfigured()) return;

        String callId = session.getCallId();
        UUID tenantId = session.getTenantId();

        // Wrap 48kHz 16-bit mono PCM into standard WAV format
        byte[] wavAudio = WavHeaderUtil.addWavHeader(pcmAudio, 48000, 1);

        // Save immediately to persistent spool before async network dispatch
        saveToSpool(tenantId, callId, wavAudio);

        CompletableFuture.runAsync(() -> {
            int maxAttempts = 3;
            int attempt = 0;
            CloudinaryStorageService.AudioUploadResult uploadResult = null;
            Exception lastException = null;

            while (attempt < maxAttempts && uploadResult == null) {
                attempt++;
                try {
                    log.info("🎙️ [WhatsAppCallAgent] Uploading call recording to Cloudinary for callId={} (attempt {}/{}, {} bytes WAV)",
                            callId, attempt, maxAttempts, wavAudio.length);
                    uploadResult = cloudinaryStorageService.uploadCallRecording(tenantId, callId, wavAudio);
                } catch (Exception e) {
                    lastException = e;
                    log.warn("⚠️ [WhatsAppCallAgent] Recording upload attempt {}/{} failed for callId={}: {}",
                            attempt, maxAttempts, callId, e.getMessage());
                    if (attempt < maxAttempts) {
                        try {
                            Thread.sleep(500L * (1L << (attempt - 1))); // 500ms, 1000ms
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            String cacheKey = (tenantId != null ? tenantId.toString() : "unknown") + ":" + callId;

            if (uploadResult != null) {
                final CloudinaryStorageService.AudioUploadResult finalResult = uploadResult;
                failedUploadAudios.remove(cacheKey);
                deleteFromSpool(tenantId, callId);
                try {
                    WhatsAppCallSession currentSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
                    if (currentSession != null) {
                        currentSession.setRecordingUrl(finalResult.secureUrl());
                        currentSession.setRecordingPublicId(finalResult.publicId());
                        currentSession.setRecordingFormat(finalResult.format() != null ? finalResult.format() : "mp3");
                        currentSession.setRecordingSizeBytes(finalResult.sizeBytes());
                        if (finalResult.durationSeconds() != null && finalResult.durationSeconds() > 0) {
                            currentSession.setRecordingDurationSeconds(finalResult.durationSeconds());
                        } else if (currentSession.getDurationSeconds() != null && currentSession.getDurationSeconds() > 0) {
                            currentSession.setRecordingDurationSeconds(currentSession.getDurationSeconds());
                        }
                        currentSession.setRecordingStatus("AVAILABLE");
                        callSessionRepository.save(currentSession);

                        // Sync recording URL to AI VoiceSession
                        if (currentSession.getAiSessionId() != null && voiceSessionRepository != null) {
                            try {
                                voiceSessionRepository.findById(currentSession.getAiSessionId()).ifPresent(vs -> {
                                    vs.setRecordingUrl(finalResult.secureUrl());
                                    voiceSessionRepository.save(vs);
                                });
                            } catch (Exception e) {
                                log.warn("⚠️ [WhatsAppCallAgent] Could not link recording to VoiceSession: {}", e.getMessage());
                            }
                        }

                        log.info("✅ [WhatsAppCallAgent] Recording upload completed: callId={} publicId={} duration={}s",
                                callId, finalResult.publicId(), currentSession.getRecordingDurationSeconds());
                    }
                } catch (Exception dbEx) {
                    log.error("❌ [WhatsAppCallAgent] Failed to persist recording metadata: {}", dbEx.getMessage(), dbEx);
                }
            } else {
                log.error("❌ [WhatsAppCallAgent] Recording upload failed after {} attempts for callId={}: {}",
                        maxAttempts, callId, lastException != null ? lastException.getMessage() : "Unknown error");
                failedUploadAudios.put(cacheKey, wavAudio);
                try {
                    WhatsAppCallSession currentSession = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
                    if (currentSession != null) {
                        currentSession.setRecordingStatus("FAILED");
                        callSessionRepository.save(currentSession);
                    }
                } catch (Exception dbEx) {
                    log.error("❌ [WhatsAppCallAgent] Failed to update recording status to FAILED: {}", dbEx.getMessage());
                }
            }
        });
    }

    private void storeRedisSdp(String key, String value) {
        try {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(sdpTtlSeconds));
        } catch (Exception e) {
            log.warn("⚠️ [WhatsAppCallAgent] Redis set failed for key={}: {}", key, e.getMessage());
        }
    }

    private void cleanupRedisSdp(String callId) {
        try {
            redisTemplate.delete("wa:sdp:offer:" + callId);
            redisTemplate.delete("wa:sdp:answer:" + callId);
        } catch (Exception e) {
            log.warn("⚠️ [WhatsAppCallAgent] Redis delete failed for callId={}: {}", callId, e.getMessage());
        }
    }

    private WhatsAppCallSession saveCallSession(UUID tenantId, String wabaId, String phoneNumberId, String callId, String direction, String fromWaId, String toWaId, String state, WhatsAppCallWebhookEnvelope.CallSession sessionPayload) {
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId)
                .orElseGet(() -> {
                    WhatsAppCallSession s = WhatsAppCallSession.builder()
                            .wabaId(wabaId)
                            .phoneNumberId(phoneNumberId)
                            .callId(callId)
                            .direction(direction)
                            .fromWaId(fromWaId)
                            .toWaId(toWaId)
                            .startedAt(Instant.now())
                            .build();
                    s.setTenantId(tenantId);
                    return s;
                });

        session.setState(state);
        if (sessionPayload != null && sessionPayload.sdp() != null) {
            session.setSdpOfferHash(DigestUtils.sha256Hex(sessionPayload.sdp()));
        }

        return callSessionRepository.save(session);
    }
}
