package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.whatsapp.WhatsAppCallWebhookEnvelope;
import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.voice.WhatsAppWebRtcMediaGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppCallingAgentService {

    private final WhatsAppCallSessionRepository callSessionRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final InboundCallPolicyService inboundCallPolicyService;
    private final OutboundCallPermissionService outboundCallPermissionService;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final StringRedisTemplate redisTemplate;
    private final WhatsAppWebRtcMediaGateway mediaGateway;

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
        // Prevent late/duplicate CONNECT from resurrecting an already terminated call
        WhatsAppCallSession existing = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (existing != null && ("ENDED".equalsIgnoreCase(existing.getState()) || "TERMINATED".equalsIgnoreCase(existing.getState()) || "REJECTED".equalsIgnoreCase(existing.getState()))) {
            log.warn("⚠️ [WhatsAppCallAgent] Ignoring late CONNECT for already finalized callId={}", callId);
            return;
        }

        boolean canAccept = inboundCallPolicyService.canAcceptInboundCall(tenantId, phoneNumberId, fromWaId);
        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        String token = config != null ? config.getAccessToken() : null;

        if (!canAccept || token == null) {
            log.warn("⛔ [WhatsAppCallAgent] Rejecting callId={} (policy or token missing)", callId);
            if (token != null) {
                metaWhatsAppClient.rejectIncomingCall(phoneNumberId, token, callId, "USER_BUSY");
            }
            saveCallSession(tenantId, wabaId, phoneNumberId, callId, "USER_INITIATED", fromWaId, toWaId, "REJECTED", event.session());
            return;
        }

        // 1. Inbound State Machine: RECEIVED -> PRE_ACCEPTING -> MEDIA_NEGOTIATING
        WhatsAppCallSession session = saveCallSession(tenantId, wabaId, phoneNumberId, callId, "USER_INITIATED", fromWaId, toWaId, "PRE_ACCEPTING", event.session());

        // Ephemeral SDP offer storage in Redis
        if (event.session() != null && event.session().sdp() != null) {
            redisTemplate.opsForValue().set("wa:sdp:offer:" + callId, event.session().sdp(), Duration.ofSeconds(sdpTtlSeconds));
        }

        // 2. Generate SDP Answer (WebRTC Engine)
        String sdpAnswer = mediaGateway.createMediaSession(callId, tenantId, fromWaId, event.session() != null ? event.session().sdp() : null);
        if (sdpAnswer != null) {
            redisTemplate.opsForValue().set("wa:sdp:answer:" + callId, sdpAnswer, Duration.ofSeconds(sdpTtlSeconds));
            session.setSdpAnswerHash(DigestUtils.sha256Hex(sdpAnswer));
        }

        // 3. Issue PRE_ACCEPT Graph Call
        session.setState("MEDIA_NEGOTIATING");
        metaWhatsAppClient.preAcceptCall(phoneNumberId, token, callId, sdpAnswer);

        // 4. Issue ACCEPT Graph Call -> MEDIA_READY -> ACCEPTED_200_OK -> ACTIVE
        session.setState("ACCEPTING");
        metaWhatsAppClient.acceptIncomingCall(phoneNumberId, token, callId);

        session.setState("ACCEPTED_200_OK");
        session.setConnectedAt(Instant.now());
        session.setState("ACTIVE");
        callSessionRepository.save(session);

        // Promptly start outbound RTP comfort stream to prevent 138021 MEDIA_RECEIVE_TIMEOUT
        mediaGateway.startOutboundMedia(callId);

        log.info("📞 [WhatsAppCallAgent] Call callId={} accepted. Initiated WebRTC media pipeline", callId);
    }

    private void handleOutboundConnectAnswer(UUID tenantId, String callId, WhatsAppCallWebhookEnvelope.CallEvent event) {
        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session == null) return;

        // Outbound State Machine: OFFER_SENT -> SDP_ANSWER_RECEIVED -> MEDIA_NEGOTIATING -> MEDIA_CONNECTED -> ACTIVE
        session.setState("SDP_ANSWER_RECEIVED");
        if (event.session() != null && event.session().sdp() != null) {
            String sdpAnswer = event.session().sdp();
            redisTemplate.opsForValue().set("wa:sdp:answer:" + callId, sdpAnswer, Duration.ofSeconds(sdpTtlSeconds));
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

        WhatsAppConfig config = whatsappConfigRepository.findByPhoneNumberId(phoneNumberId).orElse(null);
        if (config == null || config.getAccessToken() == null) {
            throw new IllegalStateException("WhatsAppConfig missing for phoneNumberId=" + phoneNumberId);
        }

        String callId = "wa_call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        // 2. Generate local WebRTC SDP Offer
        String sdpOffer = mediaGateway.createMediaSession(callId, tenantId, toWaId, null);
        redisTemplate.opsForValue().set("wa:sdp:offer:" + callId, sdpOffer, Duration.ofSeconds(sdpTtlSeconds));

        // 3. Persist session state: OFFER_SENT
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

        // 4. Issue Meta POST /calls (action=connect)
        metaWhatsAppClient.initiateBusinessCall(phoneNumberId, config.getAccessToken(), toWaId, sdpOffer, bizOpaqueCallbackData);

        // 5. Record Call Limit Event
        outboundCallPermissionService.recordLimitEvent(tenantId, phoneNumberId, toWaId, "CALL_INITIATED", callId);

        log.info("🚀 [WhatsAppCallAgent] Outbound call initiated successfully with callId={}", callId);
        return callId;
    }

    /**
     * Terminate an active call on demand (human hangup or AI turn finish)
     */
    @Transactional
    public void terminateActiveCall(UUID tenantId, String callId) {
        log.info("📞 [WhatsAppCallAgent] Manual termination requested for callId={}", callId);
        mediaGateway.terminateSession(callId);

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session != null) {
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
            callSessionRepository.save(session);
        }

        redisTemplate.delete("wa:sdp:offer:" + callId);
        redisTemplate.delete("wa:sdp:answer:" + callId);
    }


    private void processCallStatus(UUID tenantId, String phoneNumberId, WhatsAppCallWebhookEnvelope.CallStatus status) {
        String callId = status.id();
        String statusVal = status.status();
        log.info("📞 [WhatsAppCallAgent] Out-of-band status update callId='{}' status='{}'", callId, statusVal);

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session != null) {
            if ("REJECTED".equalsIgnoreCase(statusVal)) {
                session.setState("REJECTED");
                session.setEndedAt(Instant.now());
                callSessionRepository.save(session);
                outboundCallPermissionService.recordLimitEvent(tenantId, phoneNumberId, session.getToWaId(), "CALL_REJECTED", callId);
            }
        }
    }

    private void handleCallTerminated(UUID tenantId, String callId, WhatsAppCallWebhookEnvelope.CallEvent event) {
        mediaGateway.terminateSession(callId);

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session != null) {
            session.setState("TERMINATED");
            session.setEndedAt(Instant.now());

            if (event.errors() != null && !event.errors().isEmpty()) {
                WhatsAppCallWebhookEnvelope.CallError firstErr = event.errors().get(0);
                CallingErrorMapper.CallingErrorDetails mapped = CallingErrorMapper.map(firstErr.code() != null ? firstErr.code() : 0);
                session.setMetaErrorCode(firstErr.code());
                session.setMetaErrorTitle(mapped.internalCode());
                session.setMetaErrorMessage(firstErr.message() != null ? firstErr.message() : mapped.description());
                log.warn("⚠️ [WhatsAppCallAgent] Call callId={} terminated with error code={} internal={}", callId, firstErr.code(), mapped.internalCode());
            }

            if (session.getConnectedAt() != null) {
                session.setDurationSeconds((int) Duration.between(session.getConnectedAt(), Instant.now()).getSeconds());
            }
            callSessionRepository.save(session);
        }

        // Clean up ephemeral SDP keys in Redis
        redisTemplate.delete("wa:sdp:offer:" + callId);
        redisTemplate.delete("wa:sdp:answer:" + callId);
        log.info("📞 [WhatsAppCallAgent] Terminated session for callId={}", callId);
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
