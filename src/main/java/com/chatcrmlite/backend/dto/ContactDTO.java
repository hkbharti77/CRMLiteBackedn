package com.chatcrmlite.backend.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ContactDTO {
    private UUID id;
    private String waId;
    private String name;
    private List<String> tags;
    private String source;
}
