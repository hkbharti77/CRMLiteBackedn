package com.chatcrmlite.backend.dtos;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DryRunRequestDto {
    private String testPhoneNumber;
}
