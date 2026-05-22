package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.models.ConversationState.FlowType;

/**
 * Strategy interface for handling a completed conversational flow.
 *
 * Each module (Lead, Booking, Appointment) implements this interface.
 * The {@link com.chatcrmlite.backend.services.WhatsAppFlowService} iterates
 * over all registered implementations and delegates to the first one
 * that declares it can handle the given {@link FlowType}.
 *
 * Benefits:
 * - Adding a new flow type = adding one new class, zero changes to WhatsAppFlowService
 * - Each handler is independently testable
 * - Handlers can be enabled/disabled via Spring conditions
 */
public interface FlowHandler {

    /**
     * Returns true if this handler can process the given flow type.
     * Called by WhatsAppFlowService to route completed flows.
     */
    boolean supports(FlowType flowType);

    /**
     * Executes the CRM action for the completed flow (e.g., create a Booking).
     * Must be idempotent — the caller will have already checked the idempotency guard.
     *
     * @param context All data collected during the conversation
     * @return FlowResponse containing success status and the user-facing confirmation message
     */
    FlowResponse handle(FlowContext context);
}
