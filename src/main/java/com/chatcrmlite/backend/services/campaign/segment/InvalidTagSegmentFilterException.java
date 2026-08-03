package com.chatcrmlite.backend.services.campaign.segment;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTagSegmentFilterException extends RuntimeException {
    public InvalidTagSegmentFilterException(String message) {
        super(message);
    }
    
    public InvalidTagSegmentFilterException(String message, Throwable cause) {
        super(message, cause);
    }
}
