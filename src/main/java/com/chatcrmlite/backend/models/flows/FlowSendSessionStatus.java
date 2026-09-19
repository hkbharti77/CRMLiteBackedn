package com.chatcrmlite.backend.models.flows;

/**
 * Lifecycle of a FlowSendSession.
 *
 * <pre>
 *   CREATED     → token persisted in DB; WhatsApp API call not yet made
 *   SENT        → WhatsApp API confirmed delivery; flow_token included in message
 *   SEND_FAILED → WhatsApp API call failed; no nfm_reply expected
 *   RESPONDED   → nfm_reply received and linked to a FlowSubmission
 *   EXPIRED     → session TTL elapsed without a response
 * </pre>
 */
public enum FlowSendSessionStatus {
    CREATED,
    SENT,
    SEND_FAILED,
    RESPONDED,
    EXPIRED
}
