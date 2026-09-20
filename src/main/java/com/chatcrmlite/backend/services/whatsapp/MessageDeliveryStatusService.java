package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MessageDeliveryStatusService {

    private final MessageRepository messageRepository;
    private final DistributedWebSocketPublisher webSocketPublisher;

    @Transactional
    public void updateDeliveryStatus(String waMessageId, String statusStr) {
        if (statusStr == null || waMessageId == null || waMessageId.isBlank()) {
            return;
        }

        String normalized = statusStr.trim().toLowerCase();
        int rowsUpdated = switch (normalized) {
            case "read" -> messageRepository.markReadConditional(waMessageId);
            case "delivered" -> messageRepository.markDeliveredConditional(waMessageId);
            case "failed" -> messageRepository.markFailedConditional(waMessageId);
            default -> 0;
        };

        if (rowsUpdated > 0) {
            log.info("[Delivery-Status] Updated waMessageId={} to status={}", waMessageId, normalized.toUpperCase());
            messageRepository.findByWaMessageId(waMessageId).ifPresent(this::publishStatusUpdate);
        } else {
            log.debug("[Delivery-Status] No rows updated for waMessageId={} status={} (status already higher or message not found)",
                    waMessageId, normalized);
        }
    }

    private void publishStatusUpdate(Message msg) {
        UUID tenantId = null;
        if (msg.getTenant() != null) {
            tenantId = msg.getTenant().getId();
        } else if (msg.getOwner() != null && msg.getOwner().getTenant() != null) {
            tenantId = msg.getOwner().getTenant().getId();
        }

        if (tenantId != null && webSocketPublisher != null) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "DELIVERY_STATUS_UPDATE");
            payload.put("messageId", msg.getId() != null ? msg.getId().toString() : null);
            payload.put("waMessageId", msg.getWaMessageId());
            payload.put("contactId", msg.getContact() != null ? msg.getContact().getId().toString() : null);
            payload.put("deliveryStatus", msg.getDeliveryStatus() != null ? msg.getDeliveryStatus().name() : null);

            webSocketPublisher.publishMessage(tenantId, payload);
        }
    }
}
