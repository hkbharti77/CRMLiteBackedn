package com.chatcrmlite.backend.models.flows;

public enum SubmissionProcessingStatus {
    RECEIVED,           // flow_token resolved via FlowSendSession — queued for outbox processing
    UNRESOLVED,         // unknown or expired flow_token — stored for audit; NOT queued for processing
    PROCESSING,
    PROCESSED,
    PROCESSING_FAILED
}
