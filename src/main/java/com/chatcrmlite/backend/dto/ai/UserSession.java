package com.chatcrmlite.backend.dto.ai;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSession implements Serializable {
    private String lastMessage;
    private String lastDecisionReason;
    private Decision lastDecision;
    private String lastContextKey;
    private long lastUpdated;
    private AtomicInteger junkCount = new AtomicInteger(0);
    private AtomicInteger userFailures = new AtomicInteger(0);
}
