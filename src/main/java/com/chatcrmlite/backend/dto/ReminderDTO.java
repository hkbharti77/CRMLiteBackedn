package com.chatcrmlite.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class ReminderDTO {
    private UUID id;
    private UUID leadId;
    private String message;
    private LocalDateTime dueDate;
    private boolean isCompleted;
}
