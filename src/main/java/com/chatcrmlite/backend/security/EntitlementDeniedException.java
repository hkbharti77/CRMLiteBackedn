package com.chatcrmlite.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class EntitlementDeniedException extends RuntimeException {

    private final String code;
    private final String feature;

    public EntitlementDeniedException(String code, String feature, String message) {
        super(message);
        this.code = code;
        this.feature = feature;
    }

    public String getCode() {
        return code;
    }

    public String getFeature() {
        return feature;
    }
}
