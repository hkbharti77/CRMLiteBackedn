package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.dto.ai.action.AiAction;
import com.chatcrmlite.backend.dto.ai.action.AiAction.AiActionType;
import com.chatcrmlite.backend.dto.ai.action.AiAction.DecisionSource;
import com.chatcrmlite.backend.dto.ai.catalog.CatalogCandidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiCatalogDecisionService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern ACTION_JSON_PATTERN = Pattern.compile("\\{[^{}]*\"action\"\\s*:\\s*\"SEND_CATALOG\"[^{}]*\\}", Pattern.CASE_INSENSITIVE);

    /**
     * Autonomous decision based on candidate retrieval scores.
     * Evaluates whether to SEND, CLARIFY, or do NONE.
     */
    public AiAction decideFromCandidates(List<CatalogCandidate> candidates, String userQuery, DecisionSource source) {
        if (candidates == null || candidates.isEmpty()) {
            return new AiAction(AiActionType.NONE, null, "No candidates met relevance threshold.", null, source);
        }

        // Case 1: Multiple competing candidates with close scores -> CLARIFY
        if (candidates.size() > 1) {
            CatalogCandidate first = candidates.get(0);
            CatalogCandidate second = candidates.get(1);

            if (Math.abs(first.relevanceScore() - second.relevanceScore()) < 0.15 && second.relevanceScore() >= 0.70) {
                String options = candidates.stream().map(CatalogCandidate::title).collect(Collectors.joining(" or "));
                String clarifyingQuestion = "We have a few options available: " + options + ". Which one would you like me to share?";
                return new AiAction(AiActionType.CLARIFY, null, "Multiple matching catalogs detected with close scores.", clarifyingQuestion, source);
            }
        }

        // Case 2: Clear top candidate -> SEND_CATALOG
        CatalogCandidate top = candidates.get(0);
        String caption = "Here is the " + top.title() + " you requested!";
        return new AiAction(AiActionType.SEND_CATALOG, top.catalogId(), "High relevance score (" + top.relevanceScore() + ") for: " + top.title(), caption, source);
    }

    /**
     * Parses LLM text output for structured fallback action (if tool calling is not supported by model).
     */
    public AiAction parseStructuredFallback(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return new AiAction(AiActionType.NONE, null, "Empty output", null, DecisionSource.STRUCTURED_FALLBACK);
        }

        try {
            Matcher matcher = ACTION_JSON_PATTERN.matcher(llmOutput);
            if (matcher.find()) {
                String jsonStr = matcher.group();
                JsonNode node = objectMapper.readTree(jsonStr);
                String action = node.has("action") ? node.get("action").asText() : "";
                String catalogIdStr = node.has("catalog_id") ? node.get("catalog_id").asText() : null;
                String caption = node.has("caption") ? node.get("caption").asText() : null;
                String reason = node.has("reason") ? node.get("reason").asText() : "Structured JSON fallback match";

                if ("SEND_CATALOG".equalsIgnoreCase(action) && catalogIdStr != null) {
                    try {
                        UUID catalogUuid = UUID.fromString(catalogIdStr.trim());
                        return new AiAction(AiActionType.SEND_CATALOG, catalogUuid, reason, caption, DecisionSource.STRUCTURED_FALLBACK);
                    } catch (IllegalArgumentException ex) {
                        log.warn("Invalid catalog UUID in LLM output: {}", catalogIdStr);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Structured fallback parsing notice: {}", e.getMessage());
        }

        return new AiAction(AiActionType.NONE, null, "No structured action found in output", null, DecisionSource.STRUCTURED_FALLBACK);
    }
}
