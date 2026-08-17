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
public class TenantMemberRosterDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private TeamSummary summary;
    private List<MemberItem> members;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TeamSummary implements Serializable {
        private int totalMembers;
        private int ownersCount;
        private int adminsCount;
        private int agentsCount;
        private int viewersCount;
        private int activeCount;
        private int suspendedCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class MemberItem implements Serializable {
        private UUID id;
        private String displayName;
        private String email;
        private String role; // OWNER, ADMIN, AGENT, VIEWER
        private String accountStatus; // ACTIVE, SUSPENDED, LOCKED
        private String phone;
        private LocalDateTime createdAt;
        private LocalDateTime lastActiveAt;
        private int assignedLeadsCount;
        private int assignedTicketsCount;
        private int resolvedTicketsCount;
    }
}
