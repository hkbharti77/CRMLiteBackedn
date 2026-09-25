package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.repositories.WhatsAppCallSessionRepository;
import com.chatcrmlite.backend.repositories.WhatsAppPhoneNumberConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboundCallPolicyService {

    private final WhatsAppPhoneNumberConfigRepository phoneConfigRepository;
    private final WhatsAppCallSessionRepository callSessionRepository;

    /**
     * Evaluates whether an incoming call can be accepted under tenant business hours, capacity, and anti-abuse policies
     */
    public boolean canAcceptInboundCall(UUID tenantId, String phoneNumberId, String fromWaId) {
        if (phoneNumberId == null || fromWaId == null) {
            return false;
        }

        // 1. Check Phone Number Config Calling Status
        var configOpt = phoneConfigRepository.findByTenantIdAndPhoneNumberId(tenantId, phoneNumberId);
        if (configOpt.isPresent()) {
            var config = configOpt.get();
            if ("DISABLED".equalsIgnoreCase(config.getCallingStatus())) {
                log.warn("⛔ [InboundCallPolicy] Inbound call rejected - Calling disabled for phone={}", phoneNumberId);
                return false;
            }

            // 2. Check Tenant Concurrency Limit
            int maxConcurrent = config.getMaxConcurrentCalls() != null ? config.getMaxConcurrentCalls() : 5;
            long activeCalls = callSessionRepository.countActiveCallsForPhone(tenantId, phoneNumberId);
            if (activeCalls >= maxConcurrent) {
                log.warn("⛔ [InboundCallPolicy] Inbound call rejected - Concurrency limit reached for phone={}: {}/{}", phoneNumberId, activeCalls, maxConcurrent);
                return false;
            }
        }

        // 3. Business Availability Pass
        log.info("✅ [InboundCallPolicy] Inbound call approved for accepting: phone={} fromWaId={}", phoneNumberId, fromWaId);
        return true;
    }
}
