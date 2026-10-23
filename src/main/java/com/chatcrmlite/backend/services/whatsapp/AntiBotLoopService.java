
package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.repositories.ContactRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Production-grade Anti-Bot Loop & Meta Charge Protection Service.
 * Prevents recursive bot-to-bot ping-pong loops (e.g. our bot sending menus
 * and the external bot replying with "We didn't quite understand that").
 *
 * Saves Meta WhatsApp Cloud API costs, prevents Meta Quality Rating degradation,
 * and eliminates infinite LLM token drain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AntiBotLoopService {

    private final StringRedisTemplate redisTemplate;
    private final ContactRepository contactRepository;

    // Common bot auto-responder signatures (Zendesk, Freshdesk, Meta automated responses, etc.)
    private static final Pattern KNOWN_BOT_PATTERNS = Pattern.compile(
            "(?i).*(" +
            "didn[’']t quite understand|" +
            "could you tell us more about what you need help with|" +
            "thank you for (reaching out|contacting us),? we are currently (away|closed|offline)|" +
            "our office hours are|" +
            "out of (the )?office auto-?reply|" +
            "this is an automated (message|response|reply)|" +
            "please do not reply to this (automated )?message|" +
            "your ticket (number|#) has been created|" +
            "press [0-9] for|" +
            "virtual assistant" +
            ").*"
    );

    private static final int MAX_IDENTICAL_MESSAGES = 2; // Trip breaker on 2nd identical message
    private static final int MAX_BURST_MESSAGES_PER_10S = 5; // Max 5 messages in 10 seconds
    private static final int MAX_CONVERSATION_TURNS = 15; // Max 15 bot turns without human / button tap
    private static final int BOT_PAUSE_DURATION_MINUTES = 30; // Pause bot for 30 minutes on loop trip

    public record LoopCheckResult(boolean isLoopDetected, String reason) {
        public static LoopCheckResult safe() {
            return new LoopCheckResult(false, null);
        }
        public static LoopCheckResult loop(String reason) {
            return new LoopCheckResult(true, reason);
        }
    }

    /**
     * Inspects an incoming message for bot-to-bot loop indicators.
     * If a loop or auto-responder is detected, pauses the bot for this contact
     * and returns true to signal that processing should be halted immediately.
     */
    public LoopCheckResult inspectAndProtect(UUID tenantId, Contact contact, String incomingText) {
        if (contact == null || tenantId == null || incomingText == null || incomingText.isBlank()) {
            return LoopCheckResult.safe();
        }

        String waId = contact.getWaId();
        String normalized = incomingText.trim().toLowerCase();

        // 1. Signature Check: Known bot auto-responder phrases
        if (KNOWN_BOT_PATTERNS.matcher(normalized).matches()) {
            log.warn("🚨 [AntiBotLoop] Known bot signature detected from waId={}. Text='{}'. Pausing bot to prevent billing loop.",
                    waId, incomingText.length() > 60 ? incomingText.substring(0, 60) + "..." : incomingText);
            pauseContactBot(contact, "Known Bot Auto-Responder Detected: " + incomingText);
            return LoopCheckResult.loop("KNOWN_BOT_SIGNATURE");
        }

        // 2. Identical Message Ping-Pong Check (Consecutive identical incoming text)
        String textHash = hashText(normalized);
        String lastHashKey = "botloop:last_hash:" + tenantId + ":" + waId;
        String countKey = "botloop:repeat_count:" + tenantId + ":" + waId;

        String prevHash = redisTemplate.opsForValue().get(lastHashKey);
        if (textHash.equals(prevHash)) {
            Long repeatCount = redisTemplate.opsForValue().increment(countKey);
            redisTemplate.expire(countKey, Duration.ofSeconds(60));
            if (repeatCount != null && repeatCount >= MAX_IDENTICAL_MESSAGES) {
                log.warn("🚨 [AntiBotLoop] Identical message loop detected from waId={} (count={}). Pausing bot to prevent Meta charges.",
                        waId, repeatCount);
                pauseContactBot(contact, "Repeated Identical Messages Loop (count=" + repeatCount + ")");
                return LoopCheckResult.loop("IDENTICAL_MESSAGE_LOOP");
            }
        } else {
            redisTemplate.opsForValue().set(lastHashKey, textHash, Duration.ofSeconds(120));
            redisTemplate.opsForValue().set(countKey, "1", Duration.ofSeconds(60));
        }

        // 3. Burst Rate Limit Check (e.g. machine-speed messaging > 5 msgs in 10s)
        String burstKey = "botloop:burst:" + tenantId + ":" + waId;
        Long burstCount = redisTemplate.opsForValue().increment(burstKey);
        if (burstCount != null && burstCount == 1) {
            redisTemplate.expire(burstKey, Duration.ofSeconds(10));
        }
        if (burstCount != null && burstCount > MAX_BURST_MESSAGES_PER_10S) {
            log.warn("🚨 [AntiBotLoop] Burst spam detected from waId={} (>{} msgs in 10s). Pausing bot.",
                    waId, MAX_BURST_MESSAGES_PER_10S);
            pauseContactBot(contact, "Burst Messaging Detected (>5 msgs / 10s)");
            return LoopCheckResult.loop("BURST_RATE_LIMIT_EXCEEDED");
        }

        // 4. Session Turn Budget Check (Cumulative bot replies in 30 minutes)
        String turnsKey = "botloop:turns:" + tenantId + ":" + waId;
        Long turnCount = redisTemplate.opsForValue().increment(turnsKey);
        if (turnCount != null && turnCount == 1) {
            redisTemplate.expire(turnsKey, Duration.ofMinutes(30));
        }
        if (turnCount != null && turnCount > MAX_CONVERSATION_TURNS) {
            log.warn("🚨 [AntiBotLoop] Max bot turn budget exceeded for waId={} (>{} turns in 30m). Pausing bot.",
                    waId, MAX_CONVERSATION_TURNS);
            pauseContactBot(contact, "Session Turn Budget Exceeded (>15 turns)");
            return LoopCheckResult.loop("TURN_BUDGET_EXCEEDED");
        }

        return LoopCheckResult.safe();
    }

    /**
     * Resets the turn counter when a genuine user interaction occurs
     * (e.g. user clicks a button, list item, or sends a flow trigger).
     */
    public void resetTurnBudget(UUID tenantId, String waId) {
        if (tenantId != null && waId != null) {
            redisTemplate.delete("botloop:turns:" + tenantId + ":" + waId);
            redisTemplate.delete("botloop:repeat_count:" + tenantId + ":" + waId);
        }
    }

    private void pauseContactBot(Contact contact, String reason) {
        try {
            contact.setBotPaused(true, BOT_PAUSE_DURATION_MINUTES);
            contactRepository.save(contact);
            log.info("🛡️ [AntiBotLoop] Contact {} successfully paused for {} minutes. Reason: {}",
                    contact.getWaId(), BOT_PAUSE_DURATION_MINUTES, reason);
        } catch (Exception e) {
            log.error("Failed to persist botPaused for contact {}", contact.getWaId(), e);
        }
    }

    private String hashText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
