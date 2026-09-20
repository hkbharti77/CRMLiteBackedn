package com.chatcrmlite.backend.services.whatsapp.catalog;

import com.chatcrmlite.backend.clients.MetaCommerceClient;
import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.CommerceProduct;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CommerceCatalogRepository;
import com.chatcrmlite.backend.repositories.CommerceProductRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogSyncService {

    private final CommerceCatalogRepository catalogRepository;
    private final CommerceProductRepository productRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final MetaCommerceClient metaCommerceClient;

    @Transactional
    public void syncCatalogProducts(UUID tenantId, UUID catalogId) {
        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp config not found for tenant"));

        CommerceCatalog catalog = catalogRepository.findById(catalogId)
                .orElseThrow(() -> new IllegalStateException("Catalog not found"));

        if (config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            log.error("Cannot sync catalog {}: missing access token", catalogId);
            return;
        }

        log.info("Starting product sync for catalog {}", catalog.getMetaCatalogId());

        try {
            JsonNode response = metaCommerceClient.syncProducts(catalog.getMetaCatalogId(), config.getAccessToken());
            
            Set<String> activeRetailerIds = new HashSet<>();
            
            // Note: In production we would handle pagination via response.path("paging").path("next")
            JsonNode data = response.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    syncProduct(catalog, item, activeRetailerIds);
                }
            }
            
            // Deactivate products that are no longer in Meta
            List<CommerceProduct> existingProducts = productRepository.findAllByCatalog(catalog);
            for (CommerceProduct p : existingProducts) {
                if (!activeRetailerIds.contains(p.getProductRetailerId()) && p.isActive()) {
                    p.setActive(false);
                    productRepository.save(p);
                    log.info("Deactivated product {}", p.getProductRetailerId());
                }
            }

            log.info("Completed product sync for catalog {}. Synced {} products.", catalog.getMetaCatalogId(), activeRetailerIds.size());
            
        } catch (Exception e) {
            log.error("Failed to sync catalog products for {}", catalogId, e);
            throw new RuntimeException("Failed to sync catalog products: " + e.getMessage(), e);
        }
    }

    private void syncProduct(CommerceCatalog catalog, JsonNode item, Set<String> activeRetailerIds) {
        String retailerId = item.path("retailer_id").asText(null);
        if (retailerId == null) {
            retailerId = item.path("id").asText(); // fallback to meta id if retailer_id is absent
        }
        
        activeRetailerIds.add(retailerId);

        String name = item.path("name").asText("Unnamed Product");
        String description = item.path("description").asText("");
        String imageUrl = item.path("image_url").asText(null);
        String currency = item.path("currency").asText("USD");
        String priceStr = item.path("price").asText("0");
        String salePriceStr = item.path("sale_price").asText(null);
        String category = item.path("category").asText(null);
        String availability = item.path("availability").asText("in stock");
        String condition = item.path("condition").asText("new");
        String url = item.path("url").asText(null);
        String metaProductId = item.path("id").asText(null);
        
        BigDecimal price = BigDecimal.ZERO;
        if (priceStr != null && !priceStr.isBlank()) {
            try {
                String cleaned = priceStr.replaceAll("[^0-9.]", "");
                if (!cleaned.isEmpty()) {
                    price = new BigDecimal(cleaned);
                }
            } catch (Exception e) {
                log.warn("Could not parse price string '{}': {}", priceStr, e.getMessage());
            }
        }

        BigDecimal salePrice = null;
        if (salePriceStr != null && !salePriceStr.isBlank()) {
            try {
                String cleaned = salePriceStr.replaceAll("[^0-9.]", "");
                if (!cleaned.isEmpty()) {
                    salePrice = new BigDecimal(cleaned);
                }
            } catch (Exception e) {
                log.warn("Could not parse sale price string '{}': {}", salePriceStr, e.getMessage());
            }
        }

        final String finalRetailerId = retailerId;
        CommerceProduct product = productRepository.findByCatalogAndProductRetailerId(catalog, finalRetailerId)
                .orElseGet(() -> CommerceProduct.builder()
                        .catalog(catalog)
                        .productRetailerId(finalRetailerId)
                        .build());

        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setSalePrice(salePrice);
        product.setCurrency(currency);
        product.setImageUrl(imageUrl);
        product.setCategory(category);
        product.setAvailability(availability);
        product.setCondition(condition);
        product.setUrl(url);
        product.setActive(true);
        product.setMetaProductId(metaProductId);

        productRepository.save(product);
    }
}
