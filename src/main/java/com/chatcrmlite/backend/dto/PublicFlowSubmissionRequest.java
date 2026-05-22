package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;

public class PublicFlowSubmissionRequest {

    @Size(max = 50, message = "Payload must not exceed 50 key-value pairs")
    private Map<
        @Size(max = 100, message = "Key must not exceed 100 characters") String,
        @Size(max = 500, message = "Value must not exceed 500 characters") String
    > data = new HashMap<>();

    public PublicFlowSubmissionRequest() {}

    public PublicFlowSubmissionRequest(Map<String, String> data) {
        this.data = data != null ? data : new HashMap<>();
    }

    public Map<String, String> getData() { return data; }
    public void setData(Map<String, String> data) { this.data = data; }
}
