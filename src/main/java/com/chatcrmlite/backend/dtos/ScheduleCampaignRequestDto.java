package com.chatcrmlite.backend.dtos;

import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleCampaignRequestDto {
    private LocalDateTime scheduleTime;
}
