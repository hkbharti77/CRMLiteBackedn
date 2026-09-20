package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.clients.MetaWhatsAppClient;
import com.chatcrmlite.backend.dto.catalog.CatalogVerificationResult;
import com.chatcrmlite.backend.dto.catalog.ProductBuyersResponse;
import com.chatcrmlite.backend.dto.catalog.SendMultiProductRequest;
import com.chatcrmlite.backend.dto.catalog.SendProductRequest;
import com.chatcrmlite.backend.dto.catalog.SendProductResult;
import com.chatcrmlite.backend.models.CommerceCatalog;
import com.chatcrmlite.backend.models.CommerceProduct;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.repositories.CommerceCatalogRepository;
import com.chatcrmlite.backend.repositories.CommerceOrderRepository;
import com.chatcrmlite.backend.repositories.CommerceProductRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.security.TenantContext;
import com.chatcrmlite.backend.services.ContactResolutionService;
import com.chatcrmlite.backend.services.tenant.TenantResourceManager;
import com.chatcrmlite.backend.services.whatsapp.OutboundSendIdempotencyService;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.chatcrmlite.backend.services.whatsapp.catalog.CatalogSyncService;
import com.chatcrmlite.backend.services.whatsapp.catalog.CommerceCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/whatsapp/catalogs")
@RequiredArgsConstructor
public class CommerceCatalogController {

    private final CommerceCatalogService catalogService;
    private final CommerceCatalogRepository catalogRepository;
    private final CommerceProductRepository productRepository;
    private final CommerceOrderRepository orderRepository;
    private final WhatsAppConfigRepository configRepository;
    private final CatalogSyncService catalogSyncService;
    private final MetaWhatsAppClient metaWhatsAppClient;
    private final OutboundSendIdempotencyService idempotencyService;
    private final WhatsAppOutboundService whatsAppOutboundService;
    private final ContactResolutionService contactResolutionService;
    private final UserRepository userRepository;

    @Autowired(required = false)
    private TenantResourceManager resourceManager;

    @Value("${whatsapp.commerce.mpm.max-products:30}")
    private int maxMpmProducts;

    private CommerceCatalog resolveCatalog(UUID tenantId, String catalogIdentifier) {
        if (catalogIdentifier == null || catalogIdentifier.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Catalog identifier is required");
        }
        // 1. Try finding by metaCatalogId
        Optional<CommerceCatalog> byMeta = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, catalogIdentifier);
        if (byMeta.isPresent()) {
            return byMeta.get();
        }
        // 2. Try finding by database UUID
        try {
            UUID id = UUID.fromString(catalogIdentifier);
            return catalogRepository.findByIdAndTenantId(id, tenantId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog not found for id: " + catalogIdentifier));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog not found for meta ID: " + catalogIdentifier);
        }
    }

    @GetMapping
    public ResponseEntity<List<CommerceCatalog>> getCatalogs() {
        UUID tenantId = TenantContext.getTenantId();
        List<CommerceCatalog> catalogs = catalogRepository.findAllByTenantId(tenantId);
        for (CommerceCatalog c : catalogs) {
            c.setProductsCount(productRepository.countByCatalog(c));
        }
        return ResponseEntity.ok(catalogs);
    }

    @PostMapping("/connect")
    public ResponseEntity<?> connectCatalog(@RequestBody Map<String, String> payload) {
        UUID tenantId = TenantContext.getTenantId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
                
        String metaCatalogId = payload.get("metaCatalogId");
        
        try {
            CommerceCatalog catalog = catalogService.connectCatalog(
                    tenantId, config.getWabaId(), metaCatalogId, config.getAccessToken(), "Connected Catalog " + metaCatalogId);
            return ResponseEntity.ok(catalog);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<?> createCatalog(@RequestBody Map<String, String> payload) {
        UUID tenantId = TenantContext.getTenantId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
                
        String name = payload.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Catalog name is required"));
        }
        
        try {
            CommerceCatalog catalog = catalogService.createAndConnectCatalog(
                    tenantId, config.getBusinessId(), config.getWabaId(), name, config.getAccessToken());
            return ResponseEntity.ok(catalog);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{metaCatalogId}/disconnect")
    public ResponseEntity<?> disconnectCatalog(@PathVariable String metaCatalogId) {
        UUID tenantId = TenantContext.getTenantId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
                
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            catalogService.disconnectCatalog(tenantId, config.getWabaId(), catalog.getMetaCatalogId(), config.getAccessToken());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PutMapping("/{metaCatalogId}/settings")
    public ResponseEntity<?> updateSettings(
            @PathVariable String metaCatalogId,
            @RequestBody Map<String, Boolean> settings) {
        
        UUID tenantId = TenantContext.getTenantId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
                
        boolean isVisible = settings.getOrDefault("isVisible", false);
        boolean cartEnabled = settings.getOrDefault("cartEnabled", false);
        Boolean onlinePaymentEnabled = settings.get("onlinePaymentEnabled");
        Boolean codEnabled = settings.get("codEnabled");
        
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            catalogService.updateCommerceSettings(tenantId, catalog.getMetaCatalogId(), config.getPhoneNumberId(), config.getAccessToken(), isVisible, cartEnabled, onlinePaymentEnabled, codEnabled);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/{metaCatalogId}/sync")
    public ResponseEntity<?> syncProducts(@PathVariable String metaCatalogId) {
        UUID tenantId = TenantContext.getTenantId();
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            catalogSyncService.syncCatalogProducts(tenantId, catalog.getId());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/{metaCatalogId}/products")
    public ResponseEntity<?> createProduct(@PathVariable String metaCatalogId, @RequestBody Map<String, Object> payload) {
        UUID tenantId = TenantContext.getTenantId();
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));

        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);

            // Auto-generate serial SKU if missing
            Object retailerIdObj = payload.get("retailer_id");
            if (retailerIdObj == null || String.valueOf(retailerIdObj).trim().isEmpty()) {
                long productCount = productRepository.countByCatalog(catalog);
                String serialSku = String.format("SKU-%05d", productCount + 1);
                payload.put("retailer_id", serialSku);
            }

            // Forward payload directly to Meta
            Object response = catalogService.addProductToCatalog(tenantId, catalog.getMetaCatalogId(), payload, config.getAccessToken());
            
            // Trigger a sync so the new product is fetched into the CRM DB
            catalogSyncService.syncCatalogProducts(tenantId, catalog.getId());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @GetMapping("/{metaCatalogId}/products")
    public ResponseEntity<?> getProducts(@PathVariable String metaCatalogId) {
        UUID tenantId = TenantContext.getTenantId();
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            
            List<Map<String, Object>> products = productRepository.findAllByCatalog(catalog).stream().map(p -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", p.getId());
                map.put("productRetailerId", p.getProductRetailerId());
                map.put("name", p.getName());
                map.put("description", p.getDescription());
                map.put("price", p.getPrice());
                map.put("salePrice", p.getSalePrice());
                map.put("currency", p.getCurrency());
                map.put("imageUrl", p.getImageUrl());
                map.put("category", p.getCategory());
                map.put("availability", p.getAvailability());
                map.put("condition", p.getCondition());
                map.put("url", p.getUrl());
                map.put("isActive", p.isActive());
                map.put("metaProductId", p.getMetaProductId());
                map.put("catalogId", catalog.getId());
                map.put("metaCatalogId", catalog.getMetaCatalogId());
                return map;
            }).toList();
            
            return ResponseEntity.ok(products);
        } catch (Exception e) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse);
        }
    }

    @PostMapping("/{metaCatalogId}/verify")
    public ResponseEntity<CatalogVerificationResult> verifyCatalog(@PathVariable String metaCatalogId) {
        UUID tenantId = TenantContext.getTenantId();
        CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
        CatalogVerificationResult result = catalogService.verifyCatalogAccess(tenantId, catalog.getMetaCatalogId());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{metaCatalogId}/send-product")
    public ResponseEntity<?> sendProduct(
            @PathVariable String metaCatalogId,
            @Valid @RequestBody SendProductRequest request,
            @AuthenticationPrincipal String email) {
        UUID tenantId = TenantContext.getTenantId();

        // 1. Rate Limit Check
        if (resourceManager != null && !resourceManager.canConsume(tenantId, TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Message rate limit exceeded. Please retry shortly."));
        }

        // 2. Compute Payload Hash & Execution Id
        String payloadString = metaCatalogId + ":" + request.getWaId() + ":" + request.getProductRetailerId() + ":" + (request.getBodyText() != null ? request.getBodyText() : "");
        String payloadHash = idempotencyService.computePayloadHash(payloadString);
        String executionId = UUID.randomUUID().toString();
        String idempotencyKey = idempotencyService.buildKey(tenantId, request.getRequestId());

        // 3. Resolve Owner & Config
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
        User owner = resolveOwner(tenantId, email);

        // 4. Contact Resolution
        Contact contact = contactResolutionService.resolveContact(request.getWaId(), null, owner);

        // 5. Atomic Idempotency Check & Claim
        OutboundSendIdempotencyService.ClaimResult claim = idempotencyService.claimOrCheck(idempotencyKey, payloadHash, executionId);
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", claim.conflictMessage()));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.IN_PROGRESS) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "in_progress",
                    "message", "Send request is already being processed"
            ));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.SUCCEEDED) {
            return ResponseEntity.ok(Map.of(
                    "status", "sent",
                    "waMessageId", claim.waMessageId(),
                    "messageId", claim.crmMessageId() != null ? claim.crmMessageId() : ""
            ));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.META_ACCEPTED) {
            // Recovery branch: Message was accepted by Meta, attempt DB persistence
            log.warn("[CommerceController] Recovering pending CRM persistence for key={} wamid={}", idempotencyKey, claim.waMessageId());
            try {
                SendProductResult rec = whatsAppOutboundService.recoverPendingCrmPersistence(
                        contact, owner, "Product: " + request.getProductRetailerId(), claim.waMessageId(), "PRODUCT", idempotencyKey);
                return ResponseEntity.ok(Map.of(
                        "status", "sent",
                        "waMessageId", rec.waMessageId(),
                        "messageId", rec.messageId()
                ));
            } catch (Exception ex) {
                log.error("[CommerceController] DB recovery failed for key={} wamid={}: {}", idempotencyKey, claim.waMessageId(), ex.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", "Message was accepted by Meta, but CRM persistence is temporarily pending retry.",
                        "waMessageId", claim.waMessageId()
                ));
            }
        }

        // Claim ACQUIRED -> Proceed with pre-send validations
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            String actualMetaCatalogId = catalog.getMetaCatalogId();

            // 6. Hard Gate: Catalog Verification Precondition
            CatalogVerificationResult verifyResult = catalogService.verifyCatalogAccess(tenantId, actualMetaCatalogId);
            if (!verifyResult.accessible()) {
                idempotencyService.markFailed(idempotencyKey, executionId, verifyResult.failureReason());
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(verifyResult);
            }

            // 7. Product Freshness Check
            CommerceProduct product = productRepository.findByCatalogAndProductRetailerId(catalog, request.getProductRetailerId())
                    .orElse(null);
            if (product == null || !product.isActive()) {
                String errMsg = "Product '" + request.getProductRetailerId() + "' not found in catalog or is inactive.";
                idempotencyService.markFailed(idempotencyKey, executionId, errMsg);
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", errMsg));
            }

            // 8. Execute Send
            SendProductResult result = whatsAppOutboundService.sendSingleProduct(
                    contact, actualMetaCatalogId, product.getProductRetailerId(), product.getName(),
                    request.getBodyText(), config, owner, idempotencyKey, executionId);

            return ResponseEntity.ok(Map.of(
                    "status", "sent",
                    "waMessageId", result.waMessageId(),
                    "messageId", result.messageId()
            ));

        } catch (Exception ex) {
            log.error("[CommerceController] Failed to send product message: {}", ex.getMessage(), ex);
            idempotencyService.markFailed(idempotencyKey, executionId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{metaCatalogId}/send-multi-product")
    public ResponseEntity<?> sendMultiProduct(
            @PathVariable String metaCatalogId,
            @Valid @RequestBody SendMultiProductRequest request,
            @AuthenticationPrincipal String email) {
        UUID tenantId = TenantContext.getTenantId();

        // 1. MPM Product Limit Check
        if (request.getProductRetailerIds().size() > maxMpmProducts) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", "Maximum allowed products in a multi-product message is " + maxMpmProducts +
                             ". Received: " + request.getProductRetailerIds().size()
            ));
        }

        // 2. Rate Limit Check
        if (resourceManager != null && !resourceManager.canConsume(tenantId, TenantResourceManager.ResourceType.MESSAGES_PER_SECOND, 1)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "Message rate limit exceeded. Please retry shortly."));
        }

        // 3. Compute Payload Hash & Execution Id
        String payloadString = metaCatalogId + ":" + request.getWaId() + ":" + String.join(",", request.getProductRetailerIds()) +
                ":" + (request.getHeaderText() != null ? request.getHeaderText() : "") +
                ":" + (request.getBodyText() != null ? request.getBodyText() : "") +
                ":" + (request.getFooterText() != null ? request.getFooterText() : "");
        String payloadHash = idempotencyService.computePayloadHash(payloadString);
        String executionId = UUID.randomUUID().toString();
        String idempotencyKey = idempotencyService.buildKey(tenantId, request.getRequestId());

        // 4. Resolve Owner & Config
        WhatsAppConfig config = configRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp configuration not found for tenant"));
        User owner = resolveOwner(tenantId, email);

        // 5. Contact Resolution
        Contact contact = contactResolutionService.resolveContact(request.getWaId(), null, owner);

        // 6. Atomic Idempotency Check & Claim
        OutboundSendIdempotencyService.ClaimResult claim = idempotencyService.claimOrCheck(idempotencyKey, payloadHash, executionId);
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.CONFLICT) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", claim.conflictMessage()));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.IN_PROGRESS) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                    "status", "in_progress",
                    "message", "Send request is already being processed"
            ));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.SUCCEEDED) {
            return ResponseEntity.ok(Map.of(
                    "status", "sent",
                    "waMessageId", claim.waMessageId(),
                    "messageId", claim.crmMessageId() != null ? claim.crmMessageId() : ""
            ));
        }
        if (claim.status() == OutboundSendIdempotencyService.ClaimStatus.META_ACCEPTED) {
            // Recovery branch: Message was accepted by Meta, attempt DB persistence
            log.warn("[CommerceController] Recovering pending CRM persistence for MPM key={} wamid={}", idempotencyKey, claim.waMessageId());
            try {
                SendProductResult rec = whatsAppOutboundService.recoverPendingCrmPersistence(
                        contact, owner, "Products: " + String.join(", ", request.getProductRetailerIds()),
                        claim.waMessageId(), "PRODUCT_LIST", idempotencyKey);
                return ResponseEntity.ok(Map.of(
                        "status", "sent",
                        "waMessageId", rec.waMessageId(),
                        "messageId", rec.messageId()
                ));
            } catch (Exception ex) {
                log.error("[CommerceController] DB recovery failed for MPM key={} wamid={}: {}", idempotencyKey, claim.waMessageId(), ex.getMessage());
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "error", "Message was accepted by Meta, but CRM persistence is temporarily pending retry.",
                        "waMessageId", claim.waMessageId()
                ));
            }
        }

        // Claim ACQUIRED -> Proceed with pre-send validations
        try {
            CommerceCatalog catalog = resolveCatalog(tenantId, metaCatalogId);
            String actualMetaCatalogId = catalog.getMetaCatalogId();

            // 7. Hard Gate: Catalog Verification Precondition
            CatalogVerificationResult verifyResult = catalogService.verifyCatalogAccess(tenantId, actualMetaCatalogId);
            if (!verifyResult.accessible()) {
                idempotencyService.markFailed(idempotencyKey, executionId, verifyResult.failureReason());
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(verifyResult);
            }

            // 8. Products Freshness & Availability Check
            List<String> productNames = new ArrayList<>();
            List<Map<String, Object>> productItems = new ArrayList<>();
            for (String retailerId : request.getProductRetailerIds()) {
                CommerceProduct product = productRepository.findByCatalogAndProductRetailerId(catalog, retailerId)
                        .orElse(null);
                if (product == null || !product.isActive()) {
                    String errMsg = "Product '" + retailerId + "' not found in catalog or is inactive.";
                    idempotencyService.markFailed(idempotencyKey, executionId, errMsg);
                    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", errMsg));
                }
                productNames.add(product.getName());
                productItems.add(Map.of("product_retailer_id", retailerId));
            }

            Map<String, Object> section = new HashMap<>();
            section.put("title", "Products");
            section.put("product_items", productItems);
            List<Map<String, Object>> sections = List.of(section);

            // 9. Execute Send
            SendProductResult result = whatsAppOutboundService.sendMultiProduct(
                    contact, actualMetaCatalogId, request.getHeaderText(), request.getBodyText(),
                    request.getFooterText(), sections, productNames, config, owner,
                    idempotencyKey, executionId);

            return ResponseEntity.ok(Map.of(
                    "status", "sent",
                    "waMessageId", result.waMessageId(),
                    "messageId", result.messageId()
            ));

        } catch (Exception ex) {
            log.error("[CommerceController] Failed to send multi-product message: {}", ex.getMessage(), ex);
            idempotencyService.markFailed(idempotencyKey, executionId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", ex.getMessage()));
        }
    }

    private User resolveOwner(UUID tenantId, String email) {
        if (email != null && !email.isBlank()) {
            Optional<User> userOpt = userRepository.findByEmail(email);
            if (userOpt.isPresent()) return userOpt.get();
        }
        return userRepository.findFirstByTenantIdAndRole(tenantId, User.Role.ADMIN)
                .orElseGet(() -> userRepository.findAll().stream()
                        .filter(u -> u.getTenant() != null && tenantId.equals(u.getTenant().getId()))
                        .findFirst().orElse(null));
    }

    /**
     * GET /api/v1/whatsapp/catalogs/{metaCatalogId}/products/{sku}/buyers
     * Returns paginated list of customers who ordered this product, plus summary counts.
     * Full validation chain: tenant → catalog → product → query.
     */
    @GetMapping("/{metaCatalogId}/products/{sku}/buyers")
    public ResponseEntity<ProductBuyersResponse> getProductBuyers(
            @PathVariable String metaCatalogId,
            @PathVariable String sku,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID tenantId = TenantContext.getTenantId();

        // Step 1: catalog belongs to this tenant?
        CommerceCatalog catalog = catalogRepository.findByTenantIdAndMetaCatalogId(tenantId, metaCatalogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Catalog not found: " + metaCatalogId));

        // Step 2: product belongs to this catalog?
        CommerceProduct product = productRepository.findByCatalogAndProductRetailerId(catalog, sku)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Product SKU not found in catalog: " + sku));

        // Step 3: aggregate summary (no full entity load)
        Map<String, Object> summaryRow = orderRepository.getProductBuyersSummary(tenantId, sku);
        long orderCount      = summaryRow.get("orderCount") != null ? ((Number) summaryRow.get("orderCount")).longValue() : 0L;
        long uniqueBuyerCount = summaryRow.get("uniqueBuyerCount") != null ? ((Number) summaryRow.get("uniqueBuyerCount")).longValue() : 0L;

        // Step 4: paginated buyer list (projection only)
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(
                        Math.max(0, page), Math.min(100, Math.max(1, size)));
        org.springframework.data.domain.Page<Map<String, Object>> buyerPage =
                orderRepository.findBuyersByProductSku(tenantId, sku, pageable);

        List<ProductBuyersResponse.BuyerEntry> buyers = buyerPage.getContent().stream()
                .map(row -> ProductBuyersResponse.BuyerEntry.builder()
                        .orderId(row.get("orderId") instanceof UUID ? (UUID) row.get("orderId") :
                                 row.get("orderId") != null ? UUID.fromString(row.get("orderId").toString()) : null)
                        .customerName(row.get("customerName") != null ? row.get("customerName").toString() : null)
                        .waId(row.get("waId") != null ? row.get("waId").toString() : null)
                        .total(row.get("total") instanceof BigDecimal ? (BigDecimal) row.get("total") :
                               row.get("total") != null ? new BigDecimal(row.get("total").toString()) : BigDecimal.ZERO)
                        .orderStatus(row.get("orderStatus") != null ? row.get("orderStatus").toString() : null)
                        .paymentStatus(row.get("paymentStatus") != null ? row.get("paymentStatus").toString() : null)
                        .createdAt(row.get("createdAt") instanceof LocalDateTime ? (LocalDateTime) row.get("createdAt") : null)
                        .build())
                .toList();

        ProductBuyersResponse response = ProductBuyersResponse.builder()
                .product(ProductBuyersResponse.ProductSummary.builder()
                        .sku(product.getProductRetailerId())
                        .name(product.getName())
                        .imageUrl(product.getImageUrl())
                        .build())
                .summary(ProductBuyersResponse.OrderSummary.builder()
                        .orderCount(orderCount)
                        .uniqueBuyerCount(uniqueBuyerCount)
                        .build())
                .buyers(buyers)
                .page(buyerPage.getNumber())
                .size(buyerPage.getSize())
                .totalElements(buyerPage.getTotalElements())
                .totalPages(buyerPage.getTotalPages())
                .build();

        return ResponseEntity.ok(response);
    }
}
