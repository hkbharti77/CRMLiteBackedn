package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.clients.MetaCommerceClient;
import com.chatcrmlite.backend.dto.catalog.CatalogVerificationResult;
import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CommerceCatalogRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommerceCatalogService {

    private final CommerceCatalogRepository catalogRepository;
    private final MetaCommerceClient metaCommerceClient;
    private final TenantRepository tenantRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;

    @Transactional
    public CommerceCatalog createAndConnectCatalog(UUID tenantId, String businessId, String wabaId, String name, String accessToken) {
        if (businessId == null || businessId.isBlank()) {
            throw new IllegalStateException("Business Manager ID is required to create a catalog. Please ensure it is set in your WhatsApp Configuration.");
        }

        JsonNode createResponse = metaCommerceClient.createCatalog(businessId, name, accessToken);
        if (createResponse == null || !createResponse.has("id")) {
            throw new RuntimeException("Failed to create catalog in Meta");
        }
        String metaCatalogId = createResponse.get("id").asText();

        // Connect the newly created catalog
        return connectCatalog(tenantId, wabaId, metaCatalogId, accessToken, name);
    }

    @Transactional
    public CommerceCatalog connectCatalog(UUID tenantId, String wabaId, String metaCatalogId, String accessToken, String name) {
        // Find existing
        Optional<CommerceCatalog> existing = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId);
        if (existing.isPresent()) {
            CommerceCatalog catalog = existing.get();
            catalog.setStatus("ACTIVE");
            return catalogRepository.save(catalog);
        }

        // Call Meta to connect
        try {
            metaCommerceClient.connectCatalog(wabaId, metaCatalogId, accessToken);
        } catch (Exception e) {
            log.error("Failed to connect catalog to WABA {}: {}", wabaId, e.getMessage());
            // We must re-throw the exception so invalid catalogs (like "kk") are NOT saved to the database!
            throw new RuntimeException(e.getMessage());
        }

        String finalName = name;
        try {
            JsonNode catalogsResponse = discoverCatalogs(wabaId, accessToken);
            if (catalogsResponse != null && catalogsResponse.has("data")) {
                for (JsonNode node : catalogsResponse.get("data")) {
                    if (node.has("id") && node.get("id").asText().equals(metaCatalogId)) {
                        if (node.has("name")) {
                            finalName = node.get("name").asText();
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch real catalog name for {}: {}", metaCatalogId, e.getMessage());
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow(() -> new RuntimeException("Tenant not found"));
        
        CommerceCatalog catalog = CommerceCatalog.builder()
                .tenant(tenant)
                .wabaId(wabaId)
                .metaCatalogId(metaCatalogId)
                .name(finalName)
                .status("ACTIVE")
                .isVisible(false)
                .cartEnabled(true)
                .build();
        
        return catalogRepository.save(catalog);
    }

    @Transactional
    public void disconnectCatalog(UUID tenantId, String wabaId, String metaCatalogId, String accessToken) {
        Optional<CommerceCatalog> catalogOpt = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId);
        if (catalogOpt.isEmpty()) {
            return;
        }
        
        CommerceCatalog catalog = catalogOpt.get();
        catalogRepository.delete(catalog);
        
        try {
            metaCommerceClient.disconnectCatalog(wabaId, metaCatalogId, accessToken);
        } catch (Exception e) {
            log.error("Failed to disconnect catalog {} from WABA {}: {}", metaCatalogId, wabaId, e.getMessage());
        }
    }

    public JsonNode discoverCatalogs(String wabaId, String accessToken) {
        return metaCommerceClient.listConnectedCatalogs(wabaId, accessToken);
    }

    @Transactional
    public void updateCommerceSettings(UUID tenantId, String metaCatalogId, String phoneNumberId, String accessToken, boolean isVisible, boolean cartEnabled) {
        updateCommerceSettings(tenantId, metaCatalogId, phoneNumberId, accessToken, isVisible, cartEnabled, null, null);
    }

    @Transactional
    public void updateCommerceSettings(UUID tenantId, String metaCatalogId, String phoneNumberId, String accessToken, boolean isVisible, boolean cartEnabled, Boolean onlinePaymentEnabled, Boolean codEnabled) {
        CommerceCatalog catalog = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId)
                .orElseThrow(() -> new RuntimeException("Catalog not found"));

        if (phoneNumberId != null && accessToken != null) {
            metaCommerceClient.updateCommerceSettings(phoneNumberId, cartEnabled, isVisible, accessToken);
        }

        catalog.setVisible(isVisible);
        catalog.setCartEnabled(cartEnabled);
        if (onlinePaymentEnabled != null) {
            catalog.setOnlinePaymentEnabled(onlinePaymentEnabled);
        }
        if (codEnabled != null) {
            catalog.setCodEnabled(codEnabled);
        }
        catalog.setUpdatedAt(LocalDateTime.now());
        catalogRepository.save(catalog);
    }


    public JsonNode addProductToCatalog(UUID tenantId, String metaCatalogId, java.util.Map<String, Object> productData, String accessToken) {
        CommerceCatalog catalog = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId)
                .orElseThrow(() -> new RuntimeException("Catalog not found"));
        return metaCommerceClient.createProduct(catalog.getMetaCatalogId(), productData, accessToken);
    }

    public CatalogVerificationResult verifyCatalogAccess(UUID tenantId, String metaCatalogId) {
        Optional<CommerceCatalog> catalogOpt = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId);
        if (catalogOpt.isEmpty()) {
            return new CatalogVerificationResult(false, metaCatalogId, null, "Catalog not found in CRM settings.", null);
        }
        CommerceCatalog catalog = catalogOpt.get();

        Optional<WhatsAppConfig> configOpt = whatsappConfigRepository.findByTenantId(tenantId);
        if (configOpt.isEmpty() || configOpt.get().getAccessToken() == null || configOpt.get().getAccessToken().isBlank()) {
            return new CatalogVerificationResult(false, metaCatalogId, catalog.getName(), "WhatsApp configuration or access token missing.", null);
        }
        WhatsAppConfig config = configOpt.get();
        String wabaId = (catalog.getWabaId() != null && !catalog.getWabaId().isBlank()) ? catalog.getWabaId() : config.getWabaId();
        if (wabaId == null || wabaId.isBlank()) {
            return new CatalogVerificationResult(false, metaCatalogId, catalog.getName(), "WhatsApp Business Account ID (WABA ID) not configured.", null);
        }

        try {
            JsonNode catalogsResponse = metaCommerceClient.listConnectedCatalogs(wabaId, config.getAccessToken());
            boolean found = false;
            String resolvedName = catalog.getName();
            if (catalogsResponse != null && catalogsResponse.has("data") && catalogsResponse.get("data").isArray()) {
                for (JsonNode node : catalogsResponse.get("data")) {
                    if (node.has("id") && metaCatalogId.equals(node.get("id").asText())) {
                        found = true;
                        if (node.has("name")) {
                            resolvedName = node.get("name").asText();
                        }
                        break;
                    }
                }
            }

            if (!found) {
                String reason = "Catalog ID is not linked to this WABA on Meta. Verify catalog ownership, Business Manager assignment, and WABA configuration.";
                log.warn("[Catalog-Verify] Access check failed for tenant={} wabaId={} catalogId={}: {}",
                        tenantId, wabaId, metaCatalogId, reason);
                return new CatalogVerificationResult(false, metaCatalogId, resolvedName, reason, null);
            }

            log.info("[Catalog-Verify] Catalog {} successfully verified for tenant={} wabaId={}", metaCatalogId, tenantId, wabaId);
            return new CatalogVerificationResult(true, metaCatalogId, resolvedName, null, null);

        } catch (MetaCommerceClient.MetaCommerceApiException e) {
            log.error("[Catalog-Verify] Meta API error verifying catalog {} for tenant={}: {}", metaCatalogId, tenantId, e.getMessage());
            return new CatalogVerificationResult(false, metaCatalogId, catalog.getName(), e.getCommerceError().errorUserMsg(), e.getCommerceError().fbtraceId());
        } catch (Exception e) {
            log.error("[Catalog-Verify] Unexpected error verifying catalog {} for tenant={}: {}", metaCatalogId, tenantId, e.getMessage());
            return new CatalogVerificationResult(false, metaCatalogId, catalog.getName(), "Failed to verify catalog on Meta: " + e.getMessage(), null);
        }
    }
}
