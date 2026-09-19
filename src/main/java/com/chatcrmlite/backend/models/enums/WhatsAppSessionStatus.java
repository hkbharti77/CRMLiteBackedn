package com.chatcrmlite.backend.models.enums;

/**
 * Lifecycle state of the customer's 24-hour WhatsApp service window.
 */
public enum WhatsAppSessionStatus {
    /**
     * Active 24-hour service window. Interactive and free-form messages permitted.
     */
    OPEN,

    /**
     * Expired 24-hour service window or no prior inbound message. Only approved templates permitted.
     */
    CLOSED,

    /**
     * Internal lookup failed or unresolvable. Fail-safe policy blocks session messaging and mandates templates.
     */
    UNKNOWN
}
