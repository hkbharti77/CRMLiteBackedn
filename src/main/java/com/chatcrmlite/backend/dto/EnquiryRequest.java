package com.chatcrmlite.backend.dto;

public class EnquiryRequest {
    private String type;
    private String message;
    private String source;
    private String status;

    public EnquiryRequest() {}

    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getSource() { return source; }
    public String getStatus() { return status; }

    public void setType(String type) { this.type = type; }
    public void setMessage(String message) { this.message = message; }
    public void setSource(String source) { this.source = source; }
    public void setStatus(String status) { this.status = status; }
}
