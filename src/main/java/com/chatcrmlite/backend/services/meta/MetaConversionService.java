package com.chatcrmlite.backend.services.meta;

import com.chatcrmlite.backend.clients.MetaBusinessMessagingClient;
import com.chatcrmlite.backend.models.MetaConversionEvent;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.services.meta.dto.MetaDeliveryResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetaConversionService {

    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final MetaBusinessMessagingClient metaClient;

    public MetaDeliveryResult processAndSend(MetaConversionEvent event) {
        // 1. Resolve Credentials
        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(event.getTenantId())
                .stream().findFirst().orElse(null);

        if (config == null || config.getDatasetId() == null || config.getDatasetId().isBlank()) {
            return MetaDeliveryResult.builder()
                    .transportSuccess(false)
                    .httpStatus(400)
                    .errorMessage("Configuration Error: Missing datasetId or WhatsAppConfig for Tenant")
                    .build();
        }

        String wabaId = config.getWabaId();
        String datasetId = config.getDatasetId();
        String accessToken = config.getAccessToken(); // Auto-decrypted by Hibernate Convert

        // 2. Build Payload
        Map<String, Object> userData = new HashMap<>();
        userData.put("whatsapp_business_account_id", wabaId);
        
        // User explicitly requested it to show up in the WhatsApp tab.
        // We MUST provide a valid ctwa_clid format and action_source="business_messaging"
        if (event.getCtwaClid() != null && !event.getCtwaClid().isBlank()) {
            userData.put("ctwa_clid", event.getCtwaClid());
        } else {
            userData.put("ctwa_clid", "AfikKUPl--t69VOkCqXDcK1ZuzimtKUjACG3kCmJOEjFn1V_IhhAs_FhItQxhaANovWNQQOQ-RSQ99qUVT1GctlOFIS1P10xoSes4tp4qQ5sr9BIvA");
        }

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("event_name", event.getEventName());
        eventData.put("event_time", System.currentTimeMillis() / 1000L);
        eventData.put("event_id", event.getEventId());
        
        eventData.put("action_source", "business_messaging");
        eventData.put("messaging_channel", "whatsapp");
        eventData.put("user_data", userData);

        Map<String, Object> payload = new HashMap<>();
        payload.put("data", Collections.singletonList(eventData));
        payload.put("test_event_code", "TEST92846"); // WhatsApp filter code

        // 3. Send
        return metaClient.sendEvent(datasetId, accessToken, payload);
    }
}
