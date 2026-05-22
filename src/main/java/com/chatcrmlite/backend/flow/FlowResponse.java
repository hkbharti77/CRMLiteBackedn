package com.chatcrmlite.backend.flow;

import lombok.Builder;
import lombok.Getter;

/**
 * Result returned by a {@link FlowHandler} after completing a flow.
 *
 * Contains the WhatsApp confirmation message to send back to the user
 * and whether the handler successfully completed its CRM action.
 */
@Getter
@Builder
public class FlowResponse {

    /** Whether the flow handler successfully processed the context */
    private final boolean success;

    /**
     * The confirmation message to send to the WhatsApp user.
     * Example: "✅ Your appointment has been booked!"
     */
    private final String confirmationMessage;

    /**
     * Optional error description (when success = false).
     * Not exposed to the end user — used for internal logging only.
     */
    private final String errorReason;

    // ── Convenience factories ──────────────────────────────────────────────

    public static FlowResponse ok(String confirmationMessage) {
        return FlowResponse.builder()
                .success(true)
                .confirmationMessage(confirmationMessage)
                .build();
    }

    public static FlowResponse failure(String reason) {
        return FlowResponse.builder()
                .success(false)
                .confirmationMessage("⚠️ Something went wrong. Please try again or contact support.")
                .errorReason(reason)
                .build();
    }
    public boolean isSuccess() { return success; }
    public String getConfirmationMessage() { return confirmationMessage; }
    public String getErrorReason() { return errorReason; }

    public static FlowResponseBuilder builder() {
        return new FlowResponseBuilder();
    }

    public static class FlowResponseBuilder {
        private boolean success;
        private String confirmationMessage;
        private String errorReason;

        public FlowResponseBuilder success(boolean success) { this.success = success; return this; }
        public FlowResponseBuilder confirmationMessage(String confirmationMessage) { this.confirmationMessage = confirmationMessage; return this; }
        public FlowResponseBuilder errorReason(String errorReason) { this.errorReason = errorReason; return this; }

        public FlowResponse build() {
            return new FlowResponse(success, confirmationMessage, errorReason);
        }
    }

    private FlowResponse(boolean success, String confirmationMessage, String errorReason) {
        this.success = success;
        this.confirmationMessage = confirmationMessage;
        this.errorReason = errorReason;
    }
}
