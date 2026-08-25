package com.chatcrmlite.backend.services.ai.guardrail;

import com.chatcrmlite.backend.services.AbuseDetectionService;
import com.chatcrmlite.backend.dto.ai.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardrailServiceImpl implements GuardrailService {

    private final NicheConfigLoader configLoader;
    private final NluEngine nluEngine;
    private final GuardrailSessionManager sessionManager;
    private final GuardrailMetrics metrics;
    private final AbuseDetectionService abuseDetectionService;

    private boolean shadowMode = false;

    @Override
    public GuardrailResult evaluate(String rawText, String userId, boolean lastIsAi, String niche, UUID tenantId) {
        long startTime = System.currentTimeMillis();
        UserSession session = sessionManager.getSession(tenantId, userId);
        session.setLastUpdated(startTime);

        NicheConfig config = configLoader.getConfig(niche);

        try {
            String text = prepareText(rawText);
            String normalizedText = normalize(text);

            // Abuse Detection
            AbuseDetectionService.AbuseResult abuse = abuseDetectionService.detectAndClean(text);
            if (abuse.isAbusive()) {
                normalizedText = handleAbuse(session, abuse, config);
                if (normalizedText == null) {
                    return recordResult(GuardrailResult.builder()
                            .decision(Decision.IGNORE)
                            .reason("abuse_throttled")
                            .build());
                }
            }

            // Deduplication
            if (isDuplicate(session, normalizedText, startTime)) {
                return recordResult(GuardrailResult.builder()
                        .decision(Decision.REUSE)
                        .reason("exact_duplicate_within_ttl")
                        .build());
            }

            String mappedText = nluEngine.applyHinglishMapping(normalizedText, config);
            Set<String> detectedIntents = nluEngine.detectIntents(mappedText, config);
            List<String> entities = nluEngine.extractEntities(mappedText, config);
            
            String primaryIntent = detectedIntents.isEmpty() ? "none" : detectedIntents.iterator().next();
            String contextKey = primaryIntent + ":" + (entities.isEmpty() ? "generic" : String.join("+", entities));

            if (isSemanticDuplicate(session, contextKey, startTime)) {
                return recordResult(GuardrailResult.builder()
                        .decision(Decision.REUSE)
                        .reason("semantic_duplicate_within_ttl")
                        .contextKey(contextKey)
                        .build());
            }

            int score = nluEngine.calculateScore(mappedText, detectedIntents, entities);
            score = applyContextBoosts(score, lastIsAi, normalizedText, session);

            if (nluEngine.isTrash(normalizedText)) {
                return handleTrash(session);
            }

            Decision decision = makeDecision(score, detectedIntents, entities, text);
            
            GuardrailResult result = GuardrailResult.builder()
                    .decision(decision)
                    .reason(decision == Decision.CALL_AI ? "high_signal_intent" : (score >= 10 ? "ambiguous_signal" : "low_signal_fallback"))
                    .detectedIntent(primaryIntent)
                    .contextKey(contextKey)
                    .suggestion(getSuggestion(primaryIntent))
                    .build();

            sessionManager.saveSession(tenantId, userId, session);
            metrics.recordMetrics(decision, System.currentTimeMillis() - startTime, tenantId);

            if (shadowMode) {
                log.info("[Shadow] Real Decision: {}, Score: {}, Text: '{}'", decision, score, text);
            }

            return result;

        } catch (Exception e) {
            log.error("Guardrail processing error", e);
            session.getUserFailures().incrementAndGet();
            throw e;
        }
    }

    private String prepareText(String rawText) {
        if (rawText == null) return "";
        return rawText.trim();
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.trim();
    }

    private String handleAbuse(UserSession session, AbuseDetectionService.AbuseResult abuse, NicheConfig config) {
        String scrubbedText = abuse.getCleanText();
        Set<String> scrubbedIntents = nluEngine.detectIntents(nluEngine.applyHinglishMapping(scrubbedText, config), config);
        
        if (scrubbedIntents.isEmpty()) {
            session.getJunkCount().incrementAndGet();
            return null; // Signals throttled or pure abuse
        } else {
            log.info("[Abuse] Mixed abuse scrubbed. Proceeding with clean text: '{}'", scrubbedText);
            return scrubbedText;
        }
    }

    private boolean isDuplicate(UserSession session, String text, long now) {
        return session.getLastMessage() != null && 
               session.getLastMessage().equals(text) && 
               (now - session.getLastUpdated()) < 60000;
    }

    private boolean isSemanticDuplicate(UserSession session, String contextKey, long now) {
        return !contextKey.equals("none:generic") && 
               contextKey.equals(session.getLastContextKey()) && 
               (now - session.getLastUpdated()) < 60000;
    }

    private int applyContextBoosts(int score, boolean lastIsAi, String text, UserSession session) {
        if (lastIsAi && (text.length() > 4 || text.contains("?"))) score += 30;
        if (session.getLastMessage() == null && score > 20) score += 20;
        if (text.contains("no ") || text.contains("not ")) score -= 30;
        return score;
    }

    private GuardrailResult handleTrash(UserSession session) {
        int junk = session.getJunkCount().incrementAndGet();
        if (junk >= 3) {
            return recordResult(GuardrailResult.builder().decision(Decision.IGNORE).reason("spam_throttled").build());
        }
        return recordResult(GuardrailResult.builder().decision(Decision.MENU).reason("gibberish").build());
    }

    /**
     * Enterprise Multi-Tenant NLU Decision Engine:
     * Evaluates domain intent signals and entity density to distinguish pure greetings from business inquiries.
     */
    private Decision makeDecision(int score, Set<String> intents, List<String> entities, String text) {
        if (intents != null && intents.contains("menu")) {
            return Decision.MENU;
        }

        // Domain Intent Signals (e.g. price, timing, services, location, appointment, support, contact)
        boolean hasBusinessIntent = intents != null && intents.stream().anyMatch(i -> 
            !"greeting".equalsIgnoreCase(i) && !"menu".equalsIgnoreCase(i)
        );

        // Domain Entity Signals (e.g. development, branding, design, crm, lead, deployment)
        boolean hasEntitySignal = (entities != null && !entities.isEmpty());

        // Active business query signals or high NLU score -> Route to RAG + LLM Engine
        if (hasBusinessIntent || hasEntitySignal || score >= 20) {
            return Decision.CALL_AI;
        }

        // Pure Greeting (Greeting intent detected without any business intent/entity signals)
        if (intents != null && intents.contains("greeting")) {
            return Decision.GREETING;
        }

        return Decision.CALL_AI;
    }

    private String getSuggestion(String intent) {
        switch (intent) {
            case "price": return "Are you asking about consultation fees or treatment cost?";
            case "timing": return "Do you want our opening hours or specific doctor timings?";
            case "location": return "Are you looking for our address or a map link?";
            default: return "Could you please clarify what you're looking for?";
        }
    }

    private GuardrailResult recordResult(GuardrailResult result) {
        log.info("Guardrail Result: Decision={}, Reason={}", result.getDecision(), result.getReason());
        return result;
    }
}
