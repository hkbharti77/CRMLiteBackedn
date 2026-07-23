package com.chatcrmlite.backend.services.team;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class AgentAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AgentAssignmentService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired
    private ContactRepository contactRepository;

    // Tenant ID -> Round-Robin Counter
    private final Map<UUID, AtomicInteger> tenantCursors = new ConcurrentHashMap<>();

    /**
     * Retrieves available team members (ACTIVE & AVAILABLE) for a tenant.
     */
    public List<User> getAvailableAgentsForTenant(Tenant tenant) {
        if (tenant == null) return List.of();
        List<User> allUsers = userRepository.findAllByTenant(tenant);
        
        List<User> available = allUsers.stream()
                .filter(u -> u.getAccountStatus() == User.AccountStatus.ACTIVE)
                .filter(u -> u.getAvailabilityStatus() == User.AvailabilityStatus.AVAILABLE)
                .collect(Collectors.toList());

        if (!available.isEmpty()) {
            return available;
        }

        // Fallback to active OWNER/ADMIN if no agents are explicitly marked AVAILABLE
        return allUsers.stream()
                .filter(u -> u.getAccountStatus() == User.AccountStatus.ACTIVE)
                .filter(u -> u.getRole() == User.Role.OWNER || u.getRole() == User.Role.ADMIN)
                .collect(Collectors.toList());
    }

    /**
     * Atomically selects next agent in round-robin order for tenant.
     */
    public User getNextRoundRobinAgent(Tenant tenant) {
        List<User> agents = getAvailableAgentsForTenant(tenant);
        if (agents.isEmpty()) {
            log.warn("[RoundRobin] No active/available agents found for tenantId={}", tenant != null ? tenant.getId() : "null");
            return null;
        }

        UUID tenantId = tenant.getId();
        AtomicInteger cursor = tenantCursors.computeIfAbsent(tenantId, k -> new AtomicInteger(0));
        int index = Math.abs(cursor.getAndIncrement() % agents.size());
        User selectedAgent = agents.get(index);

        log.info("[RoundRobin] Selected agent email={} (role={}) for tenantId={}", 
                selectedAgent.getEmail(), selectedAgent.getRole(), tenantId);
        return selectedAgent;
    }

    /**
     * Automatically assigns a newly created lead to next round-robin agent.
     */
    @Transactional
    public User assignLeadRoundRobin(Lead lead) {
        if (lead == null || lead.getOwner() == null || lead.getOwner().getTenant() == null) {
            return null;
        }
        Tenant tenant = lead.getOwner().getTenant();
        User nextAgent = getNextRoundRobinAgent(tenant);
        if (nextAgent != null) {
            lead.setOwner(nextAgent);
            if (lead.getContact() != null) {
                lead.getContact().setOwner(nextAgent);
                contactRepository.save(lead.getContact());
            }
            leadRepository.save(lead);
            log.info("[RoundRobin] Assigned leadId={} to agentId={}", lead.getId(), nextAgent.getId());
        }
        return nextAgent;
    }

    /**
     * Automatically assigns a contact to next round-robin agent.
     */
    @Transactional
    public User assignContactRoundRobin(Contact contact) {
        if (contact == null || contact.getOwner() == null || contact.getOwner().getTenant() == null) {
            return null;
        }
        Tenant tenant = contact.getOwner().getTenant();
        User nextAgent = getNextRoundRobinAgent(tenant);
        if (nextAgent != null) {
            contact.setOwner(nextAgent);
            contactRepository.save(contact);
            log.info("[RoundRobin] Assigned contactId={} to agentId={}", contact.getId(), nextAgent.getId());
        }
        return nextAgent;
    }
}
