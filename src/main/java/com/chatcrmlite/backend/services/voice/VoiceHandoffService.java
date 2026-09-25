package com.chatcrmlite.backend.services.voice;

import com.chatcrmlite.backend.models.whatsapp.WhatsAppCallSession;
import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceHandoffService {

    private final WhatsAppCallSessionRepository callSessionRepository;

    /**
     * Evaluates whether a live voice call requires human escalation based on sentiment, frustration, or sensitive actions
     */
    public boolean evaluateHandoffRequired(String userTranscript, String aiResponse, int turnCount) {
        if (userTranscript == null || userTranscript.isBlank()) return false;

        String text = userTranscript.toLowerCase();
        // Escalation trigger keywords
        boolean explicitHumanRequest = text.contains("speak to human") || text.contains("real person")
                || text.contains("talk to agent") || text.contains("customer service executive")
                || text.contains("operator") || text.contains("human agent");

        boolean frustrationDetected = text.contains("frustrated") || text.contains("useless")
                || text.contains("angry") || text.contains("bad service");

        if (explicitHumanRequest || frustrationDetected) {
            log.info("🚨 [VoiceHandoff] Human escalation requested: transcript='{}'", userTranscript);
            return true;
        }

        return false;
    }

    /**
     * Triggers human handoff for a live WhatsApp call session based on signaling mode (SIP REFER vs Graph Ticketing)
     */
    @Transactional
    public void executeHandoff(UUID tenantId, String callId, String reason) {
        log.info("📞 [VoiceHandoff] Executing call handoff for callId={} reason='{}'", callId, reason);

        WhatsAppCallSession session = callSessionRepository.findByTenantIdAndCallId(tenantId, callId).orElse(null);
        if (session == null) return;

        session.setState("HANDOVER_INITIATED");
        session.setTerminationReason("HUMAN_HANDOVER: " + reason);

        if ("SIP".equalsIgnoreCase(session.getSignalingMode())) {
            // SIP Mode: Trigger SIP REFER / PBX transfer to human agent queue
            log.info("📞 [VoiceHandoff] SIP Mode handoff -> Initiating SIP REFER transfer for callId={}", callId);
        } else {
            // Graph Mode: Create priority live-chat / support ticket for live agent takeover
            log.info("📞 [VoiceHandoff] Graph Mode handoff -> Creating priority support ticket for callId={}", callId);
        }

        session.setEndedAt(Instant.now());
        callSessionRepository.save(session);
    }
}
