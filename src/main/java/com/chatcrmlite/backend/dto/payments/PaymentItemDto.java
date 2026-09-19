package com.chatcrmlite.backend.dto.payments;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentItemDto {

    private String sku;

    @NotBlank(message = "Item name is required")
    private String name;

    private String description;

    @NotNull(message = "Quantity is required")
    @Min(value = 1, message = "Quantity must be at least 1")
    private Integer quantity;

    @NotNull(message = "Unit price in minor units is required")
    @Min(value = 0, message = "Unit price cannot be negative")
    private Long unitPriceMinor;

    @Builder.Default
    private Long taxMinor = 0L;

    @Builder.Default
    private Long discountMinor = 0L;

    private Map<String, Object> metadata;
}
