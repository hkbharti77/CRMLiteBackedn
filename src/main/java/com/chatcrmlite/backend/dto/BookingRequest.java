package com.chatcrmlite.backend.dto;

import java.util.UUID;

public class BookingRequest {
    private UUID contactId;
    private String service;
    private String preferredSlot;

    public BookingRequest() {}

    public BookingRequest(UUID contactId, String service, String preferredSlot) {
        this.contactId = contactId;
        this.service = service;
        this.preferredSlot = preferredSlot;
    }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }
    public String getService() { return service; }
    public void setService(String service) { this.service = service; }
    public String getPreferredSlot() { return preferredSlot; }
    public void setPreferredSlot(String preferredSlot) { this.preferredSlot = preferredSlot; }
}