package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.fasterxml.jackson.databind.JsonNode;

public interface ContactResolutionService {
    Contact resolveContact(String waId, String profileName, User owner);
    String extractProfileName(JsonNode contactsNode, String from);
}
