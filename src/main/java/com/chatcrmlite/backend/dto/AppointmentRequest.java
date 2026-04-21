package com.chatcrmlite.backend.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class AppointmentRequest {
    private UUID leadId;                        // required
    private LocalDateTime appointmentDateTime;  // required
    private String title;                       // required
    private String meetingLink;                 // optional
}
