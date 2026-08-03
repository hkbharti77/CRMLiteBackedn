package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.LeadAttachment;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadAttachmentResponseDTO {
    private String id;
    private String leadId;
    private String fileName;
    private long fileSize;
    private String fileType;
    private String storageType;
    private String uploaderName;
    private LocalDateTime createdAt;
    private String downloadUrl;

    public static LeadAttachmentResponseDTO from(LeadAttachment attachment) {
        if (attachment == null) return null;
        String name = attachment.getUploader() != null ? attachment.getUploader().getDisplayName() : null;
        if (name == null || name.isBlank()) {
            name = attachment.getUploader() != null ? attachment.getUploader().getEmail() : "Unknown User";
        }
        String attId = attachment.getId() != null ? attachment.getId().toString() : "";
        String leadIdStr = attachment.getLead() != null && attachment.getLead().getId() != null ? attachment.getLead().getId().toString() : "";

        return LeadAttachmentResponseDTO.builder()
                .id(attId)
                .leadId(leadIdStr)
                .fileName(attachment.getFileName())
                .fileSize(attachment.getFileSize())
                .fileType(attachment.getFileType())
                .storageType(attachment.getStorageType() != null ? attachment.getStorageType().name() : "LOCAL")
                .uploaderName(name)
                .createdAt(attachment.getCreatedAt())
                .downloadUrl("/api/v1/leads/" + leadIdStr + "/attachments/" + attId + "/download")
                .build();
    }
}
