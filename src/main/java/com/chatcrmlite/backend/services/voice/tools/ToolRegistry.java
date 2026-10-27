package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.TenantRepository;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.chatcrmlite.backend.models.ConversationState;
import com.chatcrmlite.backend.repositories.TenantFlowConfigRepository;
import java.util.stream.Collectors;

@Service
public class ToolRegistry {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, VoiceTool> tools;
    private final TenantRepository tenantRepository;
    private final com.chatcrmlite.backend.repositories.UserRepository userRepository;
    private final TenantFlowConfigRepository tenantFlowConfigRepository;
    private final VoiceFormConfigAdapter voiceFormConfigAdapter;

    public ToolRegistry(List<VoiceTool> toolBeans,
                        TenantRepository tenantRepository,
                        com.chatcrmlite.backend.repositories.UserRepository userRepository,
                        TenantFlowConfigRepository tenantFlowConfigRepository,
                        VoiceFormConfigAdapter voiceFormConfigAdapter) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toMap(VoiceTool::getName, tool -> tool));
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.tenantFlowConfigRepository = tenantFlowConfigRepository;
        this.voiceFormConfigAdapter = voiceFormConfigAdapter;
    }

    public VoiceTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Returns VoiceTool instances enabled for this tenant (used by ToolRouter for execution).
     */
    public List<VoiceTool> getEnabledToolsForTenant(UUID tenantId) {
        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isEmpty()) {
            return List.of();
        }
        Tenant tenant = tenantOpt.get();
        return tools.values().stream()
                .filter(tool -> isToolEnabled(tool.getName(), tenant))
                .collect(Collectors.toList());
    }

    /**
     * Returns DYNAMIC ToolSpecifications built from FlowConfigService (same DB config as WhatsApp/chat bots).
     * Used by ConversationOrchestrator to tell the LLM what questions to ask.
     * No hardcoded questions — all driven by admin panel settings.
     */
    public List<ToolSpecification> getEnabledToolSpecsForTenant(UUID tenantId) {
        return getEnabledToolSpecsForTenant(tenantId, null, false);
    }

    public List<ToolSpecification> getEnabledToolSpecsForTenant(UUID tenantId, boolean includePhone) {
        return getEnabledToolSpecsForTenant(tenantId, null, includePhone);
    }

    public List<ToolSpecification> getEnabledToolSpecsForTenant(UUID tenantId, UUID userId, boolean includePhone) {
        Tenant tenant = resolveTenant(tenantId);
        if (tenant == null) {
            log.warn("[ToolRegistry] Tenant not found for id={}, returning empty tool specs", tenantId);
            return List.of();
        }
        UUID effectiveTenantId = tenant.getId();
        List<ToolSpecification> specs = new ArrayList<>();

        if (isToolEnabled("create_lead", tenant)) {
            try {
                specs.add(voiceFormConfigAdapter.buildLeadToolSpec(effectiveTenantId, userId, includePhone));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build lead tool spec for tenant={}: {}", effectiveTenantId, e.getMessage());
            }
        }
        if (isToolEnabled("book_appointment", tenant)) {
            try {
                specs.add(voiceFormConfigAdapter.buildAppointmentToolSpec(effectiveTenantId, userId, includePhone));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build appointment tool spec for tenant={}: {}", effectiveTenantId, e.getMessage());
            }
        }
        if (isToolEnabled("create_booking", tenant)) {
            try {
                specs.add(voiceFormConfigAdapter.buildBookingToolSpec(effectiveTenantId, userId, includePhone));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build booking tool spec for tenant={}: {}", effectiveTenantId, e.getMessage());
            }
        }
        if (isToolEnabled("submit_support_ticket", tenant)) {
            try {
                specs.add(voiceFormConfigAdapter.buildSupportToolSpec(effectiveTenantId, userId, includePhone));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build support tool spec for tenant={}: {}", effectiveTenantId, e.getMessage());
            }
        }

        log.info("[ToolRegistry] Built {} dynamic tool specs for tenant={} userId={} (includePhone={})", specs.size(), effectiveTenantId, userId, includePhone);
        return specs;
    }

    public boolean isToolEnabledForTenant(String toolName, UUID tenantId) {
        Tenant tenant = resolveTenant(tenantId);
        if (tenant == null) {
            return false;
        }
        return isToolEnabled(toolName, tenant);
    }

    private Tenant resolveTenant(UUID tenantId) {
        if (tenantId == null) return tenantRepository.findAll().stream().findFirst().orElse(null);
        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isPresent()) return tenantOpt.get();

        Optional<User> userOpt = userRepository.findById(tenantId);
        if (userOpt.isPresent() && userOpt.get().getTenant() != null) {
            return userOpt.get().getTenant();
        }

        return tenantRepository.findAll().stream().findFirst().orElse(null);
    }

    private boolean isToolEnabled(String toolName, Tenant tenant) {
        switch (toolName) {
            case "create_lead":
                return !Boolean.FALSE.equals(tenant.getForceShowLeads());
            case "book_appointment":
                if (!Boolean.FALSE.equals(tenant.getForceShowAppointment())) return true;
                return hasFlowConfig(tenant, ConversationState.FlowType.APPOINTMENT);
            case "create_booking":
                if (!Boolean.FALSE.equals(tenant.getForceShowBooking())) return true;
                return hasFlowConfig(tenant, ConversationState.FlowType.BOOKING);
            case "submit_support_ticket":
                return true;
            default:
                return false;
        }
    }

    private boolean hasFlowConfig(Tenant tenant, ConversationState.FlowType flowType) {
        if (tenant == null || tenantFlowConfigRepository == null) return false;
        try {
            User owner = userRepository.findById(tenant.getId())
                    .or(() -> userRepository.findFirstByTenantIdAndRole(tenant.getId(), User.Role.ADMIN))
                    .orElseGet(() -> {
                        var users = userRepository.findUserIdsByTenantId(tenant.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
                        return (users != null && !users.isEmpty()) ? userRepository.findById(users.get(0)).orElse(null) : null;
                    });
            if (owner == null) return false;
            return tenantFlowConfigRepository.findByTenantAndFlowType(owner, flowType).isPresent();
        } catch (Exception e) {
            log.warn("[ToolRegistry] Error checking flow config for flowType={}: {}", flowType, e.getMessage());
            return false;
        }
    }
}
