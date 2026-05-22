package com.chatcrmlite.backend.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardrailResult implements Serializable {
    private Decision decision;
    private String reason;
    private String detectedIntent;
    private String contextKey;
    private String suggestion;
}
