package com.chatcrmlite.backend.models.flows;

public enum FlowLifecycleStatus {
    DRAFT,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_FAILED,
    ARCHIVED,

    /**
     * @deprecated Meta operational state — no longer emitted by the application.
     * Kept here temporarily so existing DB rows do not cause enum deserialization failures
     * during rolling deployment. Once all instances are updated and the DB backfill migration
     * (normalize_legacy_flow_status) has run, this value will be removed.
     *
     * <p>Meta-side deprecation state is now tracked via {@link com.chatcrmlite.backend.models.flows.FlowRevision#metaStatus}.
     */
    @Deprecated
    DEPRECATED
}
