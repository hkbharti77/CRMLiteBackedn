package com.chatcrmlite.backend.dto.whatsapp.inbound;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderProductItemDto {
    private String productRetailerId;
    private int quantity;
    private BigDecimal itemPrice;
    private String currency;
}
