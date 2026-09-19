package com.chatcrmlite.backend.dto.payments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WhatsAppOrderItemDto {
    private UUID id;
    private String sku;
    private String name;
    private String description;
    private Integer quantity;
    private Long unitPriceMinor;
    private Long taxMinor;
    private Long discountMinor;
    private Long lineTotalMinor;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
