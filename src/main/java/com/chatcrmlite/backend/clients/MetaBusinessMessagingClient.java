package com.chatcrmlite.backend.clients;

import com.chatcrmlite.backend.services.meta.dto.MetaDeliveryResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetaBusinessMessagingClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${meta.graph-api.version:v25.0}")
    private String apiVersion;

    public MetaDeliveryResult sendEvent(String datasetId, String accessToken, Map<String, Object> payload) {
        String url = String.format("https://graph.facebook.com/%s/%s/events", apiVersion, datasetId);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);
        headers.set("Content-Type", "application/json");

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, request, String.class);
            return parseResponse(response.getBody(), response.getStatusCode().value(), null);
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            log.error("[META CAPI] HTTP Error sending event to Meta: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            return parseResponse(e.getResponseBodyAsString(), e.getStatusCode().value(), e.getMessage());
        } catch (Exception e) {
            log.error("[META CAPI] Error sending event to Meta: {}", e.getMessage());
            return MetaDeliveryResult.builder()
                    .transportSuccess(false)
                    .httpStatus(500)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    private MetaDeliveryResult parseResponse(String responseBody, int statusCode, String fallbackError) {
        if (responseBody == null || responseBody.isBlank()) {
            return MetaDeliveryResult.builder()
                    .transportSuccess(statusCode >= 200 && statusCode < 300)
                    .httpStatus(statusCode)
                    .errorMessage(fallbackError)
                    .build();
        }

        try {
            JsonNode root = objectMapper.readTree(responseBody);
            
            int eventsReceived = root.has("events_received") ? root.get("events_received").asInt() : 0;
            // The API response depends on the exact CAPI version and event parameters. 
            // In some cases it might not return events_failed unless there's an error block.
            int eventsFailed = 0;
            if (root.has("error")) {
                eventsFailed = 1; // If top-level error exists, assume it failed.
            }
            
            String traceId = root.has("fbtrace_id") ? root.get("fbtrace_id").asText() : null;
            String errorMessage = fallbackError;
            
            if (root.has("error")) {
                JsonNode errorNode = root.get("error");
                errorMessage = errorNode.has("message") ? errorNode.get("message").asText() : "Unknown Meta Error";
            }

            return MetaDeliveryResult.builder()
                    .transportSuccess(true)
                    .httpStatus(statusCode)
                    .eventsReceived(eventsReceived)
                    .eventsFailed(eventsFailed)
                    .fbtraceId(traceId)
                    .errorMessage(errorMessage)
                    .build();

        } catch (Exception e) {
            log.warn("[META CAPI] Failed to parse response JSON: {}", e.getMessage());
            return MetaDeliveryResult.builder()
                    .transportSuccess(statusCode >= 200 && statusCode < 300)
                    .httpStatus(statusCode)
                    .errorMessage("Unparseable response: " + fallbackError)
                    .build();
        }
    }
}
