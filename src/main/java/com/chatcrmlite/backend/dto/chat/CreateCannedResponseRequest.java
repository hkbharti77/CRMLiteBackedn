package com.chatcrmlite.backend.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCannedResponseRequest {

    @NotBlank(message = "Title is required")
    @Size(max = 150, message = "Title cannot exceed 150 characters")
    private String title;

    @NotBlank(message = "Shortcut is required")
    @Size(max = 50, message = "Shortcut cannot exceed 50 characters")
    private String shortcut;

    @NotBlank(message = "Content is required")
    private String content;

    private String category;

    @Builder.Default
    private boolean isShared = true;
}
