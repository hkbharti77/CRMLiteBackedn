package com.chatcrmlite.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContactCreateRequestDTO {
    
    private String name;
    
    @Email(message = "Email should be valid")
    private String email;
    
    @NotBlank(message = "WhatsApp number is required")
    private String waId;
    
    private List<String> tags;
}
