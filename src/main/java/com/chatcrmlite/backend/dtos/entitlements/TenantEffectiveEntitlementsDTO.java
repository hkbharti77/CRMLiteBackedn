package com.chatcrmlite.backend.dtos.entitlements;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO.LimitsDTO;
import lombok.*;

import java.io.Serializable;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantEffectiveEntitlementsDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String tenantId;
    private String planId;
    private String planName;
    private Integer entitlementVersion;
    private Map<String, Boolean> pages;
    private Map<String, Boolean> settings;
    private Map<String, Boolean> services;
    private LimitsDTO limits;
}
