package com.chatcrmlite.backend.dto.whatsapp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WhatsAppCallWebhookEnvelope(
    @JsonProperty("object") String object,
    @JsonProperty("entry") List<Entry> entry
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(
        @JsonProperty("id") String id,
        @JsonProperty("changes") List<Change> changes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Change(
        @JsonProperty("field") String field,
        @JsonProperty("value") Value value
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Value(
        @JsonProperty("messaging_product") String messagingProduct,
        @JsonProperty("metadata") Metadata metadata,
        @JsonProperty("calls") List<CallEvent> calls,
        @JsonProperty("statuses") List<CallStatus> statuses
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Metadata(
        @JsonProperty("display_phone_number") String displayPhoneNumber,
        @JsonProperty("phone_number_id") String phoneNumberId
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallEvent(
        @JsonProperty("id") String id,
        @JsonProperty("from") String from,
        @JsonProperty("to") String to,
        @JsonProperty("event") String event, // CONNECT, TERMINATED, FAILED
        @JsonProperty("direction") String direction, // USER_INITIATED, BUSINESS_INITIATED
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("session") CallSession session, // Optional
        @JsonProperty("start_time") Long startTime,
        @JsonProperty("end_time") Long endTime,
        @JsonProperty("duration") Integer duration,
        @JsonProperty("errors") List<CallError> errors,
        @JsonProperty("biz_opaque_callback_data") String bizOpaqueCallbackData,
        @JsonProperty("deeplink_payload") String deeplinkPayload,
        @JsonProperty("cta_payload") String ctaPayload
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallStatus(
        @JsonProperty("id") String id,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("recipient_id") String recipientId,
        @JsonProperty("status") String status, // RINGING, ACCEPTED, REJECTED
        @JsonProperty("errors") List<CallError> errors,
        @JsonProperty("biz_opaque_callback_data") String bizOpaqueCallbackData
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallSession(
        @JsonProperty("sdp") String sdp,
        @JsonProperty("media_type") String mediaType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallError(
        @JsonProperty("code") Integer code,
        @JsonProperty("title") String title,
        @JsonProperty("message") String message
    ) {}
}
