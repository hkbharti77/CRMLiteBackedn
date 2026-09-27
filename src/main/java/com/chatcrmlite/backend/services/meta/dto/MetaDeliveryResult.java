package com.chatcrmlite.backend.services.meta.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MetaDeliveryResult {
    private boolean transportSuccess;
    private int httpStatus;
    private int eventsReceived;
    private int eventsFailed;
    private String fbtraceId;
    private String errorMessage;
}
