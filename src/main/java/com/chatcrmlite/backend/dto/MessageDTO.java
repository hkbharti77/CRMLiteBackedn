package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.Message;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
public class MessageDTO {
    private UUID id;
    private String content;
    private Message.Direction direction;
    private LocalDateTime timestamp;
    private String waMessageId;
}
