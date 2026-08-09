package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dtos.EffectiveEntitlementsDTO;
import com.chatcrmlite.backend.models.PermissionKey;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.tenant.EntitlementResolverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component("perm")
public class PermissionSecurityService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntitlementResolverService entitlementResolverService;

    public boolean has(Authentication authentication, String permissionKeyString) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            return false;
        }

        String email = authentication.getName() != null ? authentication.getName().trim().toLowerCase() : "";
        User currentUser = userRepository.findByEmailWithTenant(email)
                .orElseGet(() -> userRepository.findByEmail(email).orElse(null));

        if (currentUser == null) {
            log.warn("🔒 Permission check denied: User not found for email '{}'", email);
            return false;
        }

        // Pipeline Step 1: User Account Status Active?
        if (currentUser.getAccountStatus() != User.AccountStatus.ACTIVE) {
            log.warn("🔒 Permission check denied for suspended user: email={}, status={}", currentUser.getEmail(), currentUser.getAccountStatus());
            return false;
        }

        // Pipeline Step 2: Tenant Active Status Check
        if (currentUser.getTenant() == null) {
            log.warn("🔒 Permission check denied: User {} has no tenant assigned", currentUser.getEmail());
            return false;
        }

        // Pipeline Step 3: Parse & Validate Permission Key
        Optional<PermissionKey> keyOpt = PermissionKey.fromString(permissionKeyString);
        if (keyOpt.isEmpty()) {
            log.warn("🔒 Unknown permission key string evaluated: '{}'", permissionKeyString);
            return false;
        }
        PermissionKey key = keyOpt.get();

        // Pipeline Step 4: Role Evaluation & Explicit Agent Permission Evaluation
        boolean roleAuthorized = false;
        if (currentUser.getRole() == User.Role.OWNER || currentUser.getRole() == User.Role.ADMIN || currentUser.getRole() == User.Role.SUPER_ADMIN) {
            roleAuthorized = true; // Implicit Role Authorization for Owner, Admin, and Platform Super Admin
        } else if (currentUser.getRole() == User.Role.AGENT) {
            if (key.isAdminOnly()) {
                log.warn("🔒 Permission check denied: {} is Admin/Owner only, requested by Agent {}", key.name(), currentUser.getEmail());
                return false;
            }

            List<String> agentPerms = currentUser.getPermissions();
            if (agentPerms == null || agentPerms.isEmpty()) {
                log.warn("🔒 Permission check denied: Agent {} has no permissions assigned in DB", currentUser.getEmail());
                return false;
            }

            // Parent Permission Hierarchy Check
            if (key.getParentKey() != null) {
                if (!agentPerms.contains(key.getParentKey().name())) {
                    log.warn("🔒 Permission check denied: Child key {} requires parent key {} for Agent {}", key.name(), key.getParentKey().name(), currentUser.getEmail());
                    return false;
                }
            }

            roleAuthorized = agentPerms.contains(key.name());
            if (!roleAuthorized) {
                log.warn("🔒 Permission check denied: Agent {} missing explicit permission key {}", currentUser.getEmail(), key.name());
            }
        }

        if (!roleAuthorized) {
            return false;
        }

        // Pipeline Step 5: Subscription Plan Entitlement Composite Check
        EffectiveEntitlementsDTO entitlements = entitlementResolverService.getEffectiveEntitlements(currentUser.getTenant().getId());
        if (entitlements == null) {
            log.warn("🔒 Permission check denied: Entitlements resolution returned null for tenantId={}", currentUser.getTenant().getId());
            return false;
        }

        if (key == PermissionKey.MODULE_CAMPAIGNS) {
            if (entitlements.getFeatures() == null || !entitlements.getFeatures().isHasWhatsappCampaign()) {
                log.warn("🔒 Subscription entitlement check denied: Tenant {} plan does not entitle WhatsApp campaigns", currentUser.getTenant().getId());
                return false;
            }
        }

        if (key == PermissionKey.SETTINGS_WHATSAPP) {
            if (entitlements.getFeatures() == null || !entitlements.getFeatures().isHasWhatsapp()) {
                log.warn("🔒 Subscription entitlement check denied: Tenant {} plan does not entitle WhatsApp integration", currentUser.getTenant().getId());
                return false;
            }
        }

        if (key == PermissionKey.SETTINGS_WIDGET) {
            if (entitlements.getFeatures() == null || !entitlements.getFeatures().isHasCustomWidget()) {
                log.warn("🔒 Subscription entitlement check denied: Tenant {} plan does not entitle Custom Widget", currentUser.getTenant().getId());
                return false;
            }
        }

        return true;
    }
}
