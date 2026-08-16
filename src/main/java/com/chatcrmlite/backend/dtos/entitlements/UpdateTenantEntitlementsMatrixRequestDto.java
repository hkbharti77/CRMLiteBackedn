package com.chatcrmlite.backend.dtos.entitlements;

import com.chatcrmlite.backend.models.entitlements.OverrideAction;
import lombok.*;

import java.io.Serializable;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTenantEntitlementsMatrixRequestDto implements Serializable {
    private static final long serialVersionUID = 1L;

    private Map<String, OverrideAction> pageOverrides;
    private Map<String, OverrideAction> settingOverrides;
    private Map<String, OverrideAction> serviceOverrides;
    private String reason;
}
