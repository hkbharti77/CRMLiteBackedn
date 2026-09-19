package com.chatcrmlite.backend.dto.flows;

/**
 * Structured validation error returned by Meta's Flow asset upload endpoint.
 *
 * <p>Meta's documented response shape:
 * <pre>
 * {
 *   "error_type": "JSON_SCHEMA_ERROR",
 *   "error":      "INVALID_PROPERTY",
 *   "message":    "...",
 *   "line_start": 46,
 *   "line_end":   46,
 *   "column_start": 17,
 *   "column_end":   30
 * }
 * </pre>
 *
 * @param errorType   e.g. "JSON_SCHEMA_ERROR"
 * @param error       e.g. "INVALID_PROPERTY"
 * @param message     human-readable description
 * @param component   optional — screen/component name; not present in all Meta error responses.
 *                    Do NOT build UI logic that depends on this field being non-null.
 * @param lineStart   1-based start line in the flow.json
 * @param lineEnd     1-based end line
 * @param columnStart 1-based start column
 * @param columnEnd   1-based end column
 */
public record FlowValidationError(
        String errorType,
        String error,
        String message,
        String component,   // optional
        int lineStart,
        int lineEnd,
        int columnStart,
        int columnEnd
) {}
