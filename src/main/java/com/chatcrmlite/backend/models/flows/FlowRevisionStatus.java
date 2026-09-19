package com.chatcrmlite.backend.models.flows;

/**
 * Local lifecycle status of a FlowRevision within the CRM.
 * <p>
 * This is separate from {@code metaStatus} (which mirrors Meta's reported state)
 * and {@code deprecationStatus} (which tracks the Meta deprecation operation).
 *
 * <pre>
 *   DRAFT       → being built; no Meta container yet
 *   PUBLISHING  → publish job in flight; Meta container created or being created
 *   PUBLISHED   → live on Meta, serving customers
 *   PUBLISH_FAILED → Meta rejected upload/publish; see validationErrorsJson
 *   SUPERSEDED  → locally replaced by a newer revision; Meta may still report PUBLISHED
 *                 until deprecation succeeds (tracked via metaStatus + deprecationStatus)
 * </pre>
 *
 * Note: DEPRECATED is a Meta-side state only — it lives in {@code FlowRevision.metaStatus}.
 */
public enum FlowRevisionStatus {
    DRAFT,
    PUBLISHING,
    PUBLISHED,
    PUBLISH_FAILED,
    SUPERSEDED
}
