package com.chatcrmlite.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessServiceDTO {
    private UUID id;
    private String name;
    private String description;
    private boolean hasImage;
    private String imageUrl; // If it's an external link
}
