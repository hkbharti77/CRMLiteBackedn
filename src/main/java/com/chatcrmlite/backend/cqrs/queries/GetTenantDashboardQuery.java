package com.chatcrmlite.backend.cqrs.queries;

import lombok.Data;
import java.util.UUID;

@Data
public class GetTenantDashboardQuery implements Query {
    private final UUID tenantId;
}
