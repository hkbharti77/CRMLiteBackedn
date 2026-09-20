package com.chatcrmlite.backend.dto.whatsapp.inbound;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderMessageDto {
    private String catalogId;
    private String text;
    private List<OrderProductItemDto> productItems;
}
