package com.chatcrmlite.backend.exceptions;

import lombok.Getter;
import java.util.List;

@Getter
public class SubscriptionFeatureException extends RuntimeException {

    private final String errorCode;
    private final String requestedFeature;
    private final String planId;
    private final List<String> allowedValues;

    public SubscriptionFeatureException(String errorCode, String requestedFeature, String planId, List<String> allowedValues, String message) {
        super(message);
        this.errorCode = errorCode;
        this.requestedFeature = requestedFeature;
        this.planId = planId;
        this.allowedValues = allowedValues;
    }

    public SubscriptionFeatureException(String message) {
        super(message);
        this.errorCode = "FEATURE_NOT_ALLOWED";
        this.requestedFeature = "UNKNOWN";
        this.planId = "UNKNOWN";
        this.allowedValues = List.of();
    }
}
