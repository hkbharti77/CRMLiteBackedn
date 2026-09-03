package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.repositories.TenantRepository;
import dev.langchain4j.agent.tool.ToolSpecification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolRegistry {

    private final Map<String, VoiceTool> tools;
    private final TenantRepository tenantRepository;
    private final VoiceFormConfigAdapter voiceFormConfigAdapter;

    public ToolRegistry(List<VoiceTool> toolBeans, TenantRepository tenantRepository, VoiceFormConfigAdapter voiceFormConfigAdapter) {
        this.tools = toolBeans.stream()
                .collect(Collectors.toMap(VoiceTool::getName, tool -> tool));
        this.tenantRepository = tenantRepository;
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
        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isEmpty()) {
            log.warn("[ToolRegistry] Tenant not found for id={}, returning empty tool specs", tenantId);
            return List.of();
        }
        Tenant tenant = tenantOpt.get();
        List<ToolSpecification> specs = new ArrayList<>();

        if (Boolean.TRUE.equals(tenant.getForceShowLeads())) {
            try {
                specs.add(voiceFormConfigAdapter.buildLeadToolSpec(tenantId));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build lead tool spec for tenant={}: {}", tenantId, e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(tenant.getForceShowAppointment())) {
            try {
                specs.add(voiceFormConfigAdapter.buildAppointmentToolSpec(tenantId));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build appointment tool spec for tenant={}: {}", tenantId, e.getMessage());
            }
        }
        if (Boolean.TRUE.equals(tenant.getForceShowBooking())) {
            try {
                specs.add(voiceFormConfigAdapter.buildBookingToolSpec(tenantId));
            } catch (Exception e) {
                log.error("[ToolRegistry] Failed to build booking tool spec for tenant={}: {}", tenantId, e.getMessage());
            }
        }
        // Support tickets always available
        try {
            specs.add(voiceFormConfigAdapter.buildSupportToolSpec(tenantId));
        } catch (Exception e) {
            log.error("[ToolRegistry] Failed to build support tool spec for tenant={}: {}", tenantId, e.getMessage());
        }

        log.info("[ToolRegistry] Built {} dynamic tool specs for tenant={}", specs.size(), tenantId);
        return specs;
    }

    public boolean isToolEnabledForTenant(String toolName, UUID tenantId) {
        Optional<Tenant> tenantOpt = tenantRepository.findById(tenantId);
        if (tenantOpt.isEmpty()) {
            return false;
        }
        return isToolEnabled(toolName, tenantOpt.get());
    }

    private boolean isToolEnabled(String toolName, Tenant tenant) {
        switch (toolName) {
            case "create_lead":
                return Boolean.TRUE.equals(tenant.getForceShowLeads());
            case "book_appointment":
                return Boolean.TRUE.equals(tenant.getForceShowAppointment());
            case "create_booking":
                return Boolean.TRUE.equals(tenant.getForceShowBooking());
            case "submit_support_ticket":
                return true;
            default:
                return false;
        }
    }
}
