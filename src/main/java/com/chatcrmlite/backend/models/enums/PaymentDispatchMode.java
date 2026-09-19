package com.chatcrmlite.backend.models.enums;

/**
 * Defines the delivery channel used to transmit the payment request to the customer.
 */
public enum PaymentDispatchMode {
    /**
     * Interactive session message (UPI Order Details card or Direct Gateway CTA button)
     * dispatched when the 24-hour customer service window is active.
     */
    SESSION_MESSAGE,

    /**
     * WhatsApp Template message dispatched when the 24-hour window has expired
     * or when sending an official utility/transactional notification.
     */
    PAYMENT_TEMPLATE
}
