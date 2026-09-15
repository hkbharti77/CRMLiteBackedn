package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.email.InboundEmailDTO;
import com.chatcrmlite.backend.models.email.EmailInboundMessage;
import com.chatcrmlite.backend.services.email.EmailInboundReplyService;
import com.chatcrmlite.backend.services.email.verifier.InboundProviderVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1/webhooks/email")
@RequiredArgsConstructor
@Slf4j
public class EmailInboundWebhookController {

    private final List<InboundProviderVerifier> verifiers;
    private final EmailInboundReplyService inboundReplyService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/inbound-reply", consumes = {"application/json", "application/x-www-form-urlencoded", "multipart/form-data"})
    public ResponseEntity<Void> handleInboundReplyWebhook(
            @RequestParam(value = "provider", defaultValue = "generic") String providerParam,
            HttpServletRequest request,
            @RequestBody(required = false) String rawBodyStr) {

        Map<String, String> headersMap = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headersMap.put(name.toLowerCase(), request.getHeader(name));
            }
        }

        String provider = providerParam.toLowerCase();
        InboundProviderVerifier verifier = verifiers.stream()
                .filter(v -> v.getProviderName().equalsIgnoreCase(provider))
                .findFirst()
                .orElseGet(() -> verifiers.stream()
                        .filter(v -> v.getProviderName().equalsIgnoreCase("generic"))
                        .findFirst()
                        .orElseThrow());

        boolean isValid = verifier.verify(request, headersMap, rawBodyStr != null ? rawBodyStr : "");
        if (!isValid) {
            log.warn("[InboundWebhook] Webhook signature/secret verification failed for provider={}", provider);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        try {
            InboundEmailDTO dto = parseInboundDto(provider, request, rawBodyStr);
            if (dto.getProviderMessageId() == null || dto.getProviderMessageId().isBlank()) {
                dto.setProviderMessageId(UUID.randomUUID().toString());
            }

            EmailInboundMessage message = inboundReplyService.processInboundReply(dto);
            log.info("[InboundWebhook] Inbound email reply processed successfully (id={}, status={})",
                    message.getId(), message.getAttributionStatus());

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("[InboundWebhook] Error processing inbound email webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private InboundEmailDTO parseInboundDto(String provider, HttpServletRequest request, String rawBodyStr) {
        InboundEmailDTO.InboundEmailDTOBuilder builder = InboundEmailDTO.builder()
                .provider(provider)
                .receivedAt(Instant.now())
                .rawPayload(rawBodyStr);

        if (rawBodyStr != null && rawBodyStr.trim().startsWith("{")) {
            try {
                JsonNode json = objectMapper.readTree(rawBodyStr);
                builder.providerMessageId(json.path("message_id").asText(json.path("email_id").asText(null)))
                        .fromEmail(json.path("from").asText(null))
                        .toEmail(json.path("to").asText(null))
                        .subject(json.path("subject").asText(null))
                        .textBody(json.path("text").asText(json.path("plain").asText(null)))
                        .htmlBody(json.path("html").asText(null))
                        .inReplyTo(json.path("in_reply_to").asText(null))
                        .references(json.path("references").asText(null))
                        .recipientReplyToken(json.path("reply_token").asText(null));
                return builder.build();
            } catch (Exception e) {
                log.debug("[InboundWebhook] Raw body is not JSON, falling back to form parameters");
            }
        }

        // Form / Multipart fallback
        builder.providerMessageId(request.getParameter("Message-ID") != null ? request.getParameter("Message-ID") : request.getParameter("message-id"))
                .fromEmail(request.getParameter("from") != null ? request.getParameter("from") : request.getParameter("sender"))
                .toEmail(request.getParameter("to") != null ? request.getParameter("to") : request.getParameter("recipient"))
                .subject(request.getParameter("subject"))
                .textBody(request.getParameter("text") != null ? request.getParameter("text") : request.getParameter("body-plain"))
                .htmlBody(request.getParameter("html") != null ? request.getParameter("html") : request.getParameter("body-html"))
                .inReplyTo(request.getParameter("in-reply-to") != null ? request.getParameter("in-reply-to") : request.getParameter("In-Reply-To"))
                .references(request.getParameter("references"))
                .recipientReplyToken(request.getParameter("reply_token"));

        return builder.build();
    }
}
