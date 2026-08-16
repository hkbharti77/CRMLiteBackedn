package com.chatcrmlite.backend.dtos.entitlements;

import com.chatcrmlite.backend.models.entitlements.EntitlementDefinition;
import com.chatcrmlite.backend.models.entitlements.OverrideAction;
import lombok.*;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlatformTenantEntitlementMatrixDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private UUID tenantId;
    private String businessName;
    private String planId;
    private String planName;
    private Integer entitlementVersion;

    private Map<String, OverrideAction> pageOverrides;
    private Map<String, OverrideAction> settingOverrides;
    private Map<String, OverrideAction> serviceOverrides;

    private Map<String, Boolean> effectivePages;
    private Map<String, Boolean> effectiveSettings;
    private Map<String, Boolean> effectiveServices;

    private List<EntitlementDefinition> catalog;
}
