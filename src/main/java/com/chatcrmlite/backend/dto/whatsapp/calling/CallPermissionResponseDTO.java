package com.chatcrmlite.backend.dto.whatsapp.calling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CallPermissionResponseDTO {
    private UUID id;
    private String phoneNumberId;
    private String userWaId;
    private String status;
    private String permissionType;
    private Boolean isPermanent;
    private Instant grantedAt;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant lastPermissionRequestAt;
    private boolean active;
    private boolean canInitiateCall;
}
