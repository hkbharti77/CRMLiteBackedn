package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class IncomingMessageContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String waMessageId;
    private final String from;
    private final String type;
    private final String text;
    private final String selectionId;
    private final boolean isInteractiveSelection;
    private final long timestamp;
    private final String phoneNumberId;
    private final JsonNode rawMessageNode;
    private final User owner;
    private final WhatsAppConfig config;
}
