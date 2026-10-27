package com.chatcrmlite.backend.dto.chat;

import com.chatcrmlite.backend.models.chat.CannedResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CannedResponseDto {
    private UUID id;
    private String title;
    private String shortcut;
    private String content;
    private String category;
    private boolean isShared;
    private Long usageCount;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CannedResponseDto fromEntity(CannedResponse entity) {
        if (entity == null) return null;
        return CannedResponseDto.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .shortcut(entity.getShortcut())
                .content(entity.getContent())
                .category(entity.getCategory())
                .isShared(entity.isShared())
                .usageCount(entity.getUsageCount() != null ? entity.getUsageCount() : 0L)
                .createdByName(entity.getCreatedBy() != null ? entity.getCreatedBy().getName() : "System")
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
