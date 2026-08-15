package com.chatcrmlite.backend.models.flows;

public enum FlowAuditAction {
    FLOW_CREATED,
    REVISION_CREATED,
    FLOW_PUBLISH_QUEUED,
    FLOW_PUBLISHED,
    FLOW_PUBLISH_FAILED,
    FLOW_DUPLICATED,
    FLOW_ARCHIVED,
    FLOW_ATTACHED,
    FLOW_DETACHED
}
