package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AddCommentRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String message;

    private Boolean internal;

    public AddCommentRequest() {}

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Boolean getInternal() { return internal; }
    public void setInternal(Boolean internal) { this.internal = internal; }
}
