package com.chatcrmlite.backend.dto;

import java.io.Serializable;

public class EnquiryDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String id;
    private String type;
    private String message;
    private String source;
    private String status;
    private String createdAt;

    public EnquiryDTO() {}

    public EnquiryDTO(String id, String type, String message, String source, String status, String createdAt) {
        this.id = id;
        this.type = type;
        this.message = message;
        this.source = source;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getMessage() { return message; }
    public String getSource() { return source; }
    public String getStatus() { return status; }
    public String getCreatedAt() { return createdAt; }

    public void setId(String id) { this.id = id; }
    public void setType(String type) { this.type = type; }
    public void setMessage(String message) { this.message = message; }
    public void setSource(String source) { this.source = source; }
    public void setStatus(String status) { this.status = status; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static EnquiryDTOBuilder builder() { return new EnquiryDTOBuilder(); }

    public static class EnquiryDTOBuilder {
        private String id;
        private String type;
        private String message;
        private String source;
        private String status;
        private String createdAt;

        public EnquiryDTOBuilder id(String id) { this.id = id; return this; }
        public EnquiryDTOBuilder type(String type) { this.type = type; return this; }
        public EnquiryDTOBuilder message(String message) { this.message = message; return this; }
        public EnquiryDTOBuilder source(String source) { this.source = source; return this; }
        public EnquiryDTOBuilder status(String status) { this.status = status; return this; }
        public EnquiryDTOBuilder createdAt(String createdAt) { this.createdAt = createdAt; return this; }

        public EnquiryDTO build() {
            return new EnquiryDTO(id, type, message, source, status, createdAt);
        }
    }
}
