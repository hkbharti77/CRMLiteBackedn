package com.chatcrmlite.backend.dto;

import com.chatcrmlite.backend.models.LeadNote;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LeadNoteResponseDTO {
    private String id;
    private String leadId;
    private String content;
    private String authorName;
    private String authorEmail;
    private LocalDateTime createdAt;

    public static LeadNoteResponseDTO from(LeadNote note) {
        if (note == null) return null;
        String name = note.getAuthor() != null ? note.getAuthor().getDisplayName() : null;
        if (name == null || name.isBlank()) {
            name = note.getAuthor() != null ? note.getAuthor().getEmail() : "Unknown User";
        }
        return LeadNoteResponseDTO.builder()
                .id(note.getId() != null ? note.getId().toString() : null)
                .leadId(note.getLead() != null && note.getLead().getId() != null ? note.getLead().getId().toString() : null)
                .content(note.getContent())
                .authorName(name)
                .authorEmail(note.getAuthor() != null ? note.getAuthor().getEmail() : null)
                .createdAt(note.getCreatedAt())
                .build();
    }
}
