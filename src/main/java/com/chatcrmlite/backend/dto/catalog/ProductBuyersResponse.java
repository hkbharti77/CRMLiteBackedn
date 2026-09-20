package com.chatcrmlite.backend.dto.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Paginated response for the "who ordered this product" feature.
 * Returns only the fields needed by the UI — no lazy-loaded entity graph.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProductBuyersResponse {

    private ProductSummary product;
    private OrderSummary   summary;
    private List<BuyerEntry> buyers;
    private int  page;
    private int  size;
    private long totalElements;
    private int  totalPages;

    @Data
    @Builder
    public static class ProductSummary {
        private String sku;
        private String name;
        private String imageUrl;
    }

    @Data
    @Builder
    public static class OrderSummary {
        /** Total valid orders (excludes CANCELLED, PAYMENT_FAILED) */
        private long orderCount;
        /** Distinct customer wa_ids who placed at least one valid order */
        private long uniqueBuyerCount;
    }

    @Data
    @Builder
    public static class BuyerEntry {
        private UUID          orderId;
        private String        customerName;
        private String        waId;
        private BigDecimal    total;
        private String        orderStatus;
        private String        paymentStatus;
        private LocalDateTime createdAt;
    }
}
