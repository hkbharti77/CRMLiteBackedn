package com.chatcrmlite.backend.dtos.platform;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDetailedProfileDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID id;
    private String displayName;
    private String email;
    private String role;
    private String accountStatus;
    private String phone;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;

    // Tenant info
    private UUID tenantId;
    private String tenantBusinessName;
    private String tenantPlanType;

    // Performance & Workload Metrics (Attributed within period e.g. 30 Days)
    private String metricsPeriod;
    private int assignedLeadsCount;
    private int wonLeadsCount;
    private double leadConversionRate;
    private int assignedTicketsCount;
    private int resolvedTicketsCount;
    private int directChatsHandled;

    // Granular RBAC Permissions
    private List<String> permissions;
    private boolean isSuperAdmin;
}
