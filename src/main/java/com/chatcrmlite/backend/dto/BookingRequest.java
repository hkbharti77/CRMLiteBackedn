package com.chatcrmlite.backend.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class BookingRequest {
    private UUID leadId;        // required
    private String service;     // required
    private String preferredSlot; // optional
}
