package com.chatcrmlite.backend.services.payments;

import com.chatcrmlite.backend.models.enums.PaymentTransactionStatus;
import com.chatcrmlite.backend.models.enums.WhatsAppOrderPaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class PaymentStateMachine {

    private static final Map<WhatsAppOrderPaymentStatus, Set<WhatsAppOrderPaymentStatus>> ORDER_TRANSITIONS = Map.of(
        WhatsAppOrderPaymentStatus.CREATED, EnumSet.of(
            WhatsAppOrderPaymentStatus.PAYMENT_REQUEST_SENT,
            WhatsAppOrderPaymentStatus.PAYMENT_PENDING,
            WhatsAppOrderPaymentStatus.FAILED,
            WhatsAppOrderPaymentStatus.EXPIRED
        ),
        WhatsAppOrderPaymentStatus.PAYMENT_REQUEST_SENT, EnumSet.of(
            WhatsAppOrderPaymentStatus.PAYMENT_PENDING,
            WhatsAppOrderPaymentStatus.PAID,
            WhatsAppOrderPaymentStatus.FAILED,
            WhatsAppOrderPaymentStatus.EXPIRED
        ),
        WhatsAppOrderPaymentStatus.PAYMENT_PENDING, EnumSet.of(
            WhatsAppOrderPaymentStatus.PAID,
            WhatsAppOrderPaymentStatus.FAILED,
            WhatsAppOrderPaymentStatus.EXPIRED
        ),
        WhatsAppOrderPaymentStatus.PAID, EnumSet.of(
            WhatsAppOrderPaymentStatus.PARTIALLY_REFUNDED,
            WhatsAppOrderPaymentStatus.REFUNDED
        ),
        WhatsAppOrderPaymentStatus.PARTIALLY_REFUNDED, EnumSet.of(
            WhatsAppOrderPaymentStatus.REFUNDED
        ),
        WhatsAppOrderPaymentStatus.FAILED, EnumSet.of(
            WhatsAppOrderPaymentStatus.PAYMENT_REQUEST_SENT,
            WhatsAppOrderPaymentStatus.PAYMENT_PENDING,
            WhatsAppOrderPaymentStatus.PAID // When a subsequent attempt succeeds
        ),
        WhatsAppOrderPaymentStatus.EXPIRED, EnumSet.of(
            WhatsAppOrderPaymentStatus.PAYMENT_REQUEST_SENT
        ),
        WhatsAppOrderPaymentStatus.REFUNDED, EnumSet.noneOf(WhatsAppOrderPaymentStatus.class)
    );

    public boolean canTransition(WhatsAppOrderPaymentStatus from, WhatsAppOrderPaymentStatus to) {
        if (from == to) return true;
        Set<WhatsAppOrderPaymentStatus> allowed = ORDER_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public void validateTransition(
        WhatsAppOrderPaymentStatus currentOrderStatus,
        PaymentTransactionStatus currentTxStatus,
        PaymentTransactionStatus newTxStatus
    ) {
        if (currentTxStatus == PaymentTransactionStatus.SUCCESS && newTxStatus == PaymentTransactionStatus.FAILED) {
            throw new IllegalStateException("Cannot transition a SUCCESS transaction to FAILED");
        }
        if (currentOrderStatus == WhatsAppOrderPaymentStatus.REFUNDED) {
            throw new IllegalStateException("Cannot process payments on an already fully REFUNDED order");
        }
    }
}
