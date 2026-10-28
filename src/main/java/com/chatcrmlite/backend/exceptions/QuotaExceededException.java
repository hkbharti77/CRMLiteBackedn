package com.chatcrmlite.backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class QuotaExceededException extends RuntimeException {

    private final String quotaType;
    private final long limit;
    private final long requested;
    private final long available;

    public QuotaExceededException(String message) {
        super(message);
        this.quotaType = "GENERAL";
        this.limit = 0;
        this.requested = 0;
        this.available = 0;
    }

    public QuotaExceededException(String quotaType, long limit, long requested, long available, String message) {
        super(message);
        this.quotaType = quotaType;
        this.limit = limit;
        this.requested = requested;
        this.available = available;
    }

    public String getQuotaType() {
        return quotaType;
    }

    public long getLimit() {
        return limit;
    }

    public long getRequested() {
        return requested;
    }

    public long getAvailable() {
        return available;
    }
}
