package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

import java.io.Serializable;

@Data
@Builder
public class ConversationContext implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Contact contact;
    private final User owner;
    private final Map<String, Object> metadata;
    private boolean flowInProgress;
    private String currentFlowStep;
}
