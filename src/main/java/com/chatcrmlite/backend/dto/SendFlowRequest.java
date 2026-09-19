package com.chatcrmlite.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendFlowRequest {
    
    @NotNull(message = "flowId is required")
    private UUID flowId;
    
    private String headerText;
    private String bodyText;
    private String footerText;
    private String ctaText;
    private String screen;
}
