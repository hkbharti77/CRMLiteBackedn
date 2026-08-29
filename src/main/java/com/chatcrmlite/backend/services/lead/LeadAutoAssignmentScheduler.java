package com.chatcrmlite.backend.services.lead;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.LeadAssignmentStatus;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeadAutoAssignmentScheduler {

    private final LeadRepository leadRepository;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final LeadService leadService; // Note: to use atomic assign, we might just call a new method in LeadService to handle transaction properly

    /**
     * Runs every 1 minute to check for unassigned leads and assign them to eligible agents.
     */
    @Scheduled(fixedDelay = 60000)
    public void processAutoAssignments() {
        log.debug("[Lead-Auto-Assignment] Starting auto-assignment job...");

        List<Tenant> activeTenants = tenantRepository.findAll();

        for (Tenant tenant : activeTenants) {
            int delayMinutes = tenant.getAutoAssignmentDelayMinutes() != null ? tenant.getAutoAssignmentDelayMinutes() : 5;
            LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(delayMinutes);

            // Fetch leads for this tenant that are UNASSIGNED or LIMIT_REACHED and older than cutoff
            List<UUID> leadsToProcess = leadRepository.findLeadsForAutoAssignment(tenant.getId(), cutoffTime);

            if (!leadsToProcess.isEmpty()) {
                log.info("[Lead-Auto-Assignment] Found {} leads to auto-assign for tenant {}", leadsToProcess.size(), tenant.getId());
            }

            for (UUID leadId : leadsToProcess) {
                try {
                    leadService.autoAssignLead(leadId, tenant);
                } catch (Exception e) {
                    log.error("[Lead-Auto-Assignment] Error assigning lead {}: {}", leadId, e.getMessage());
                }
            }
        }
    }
}
