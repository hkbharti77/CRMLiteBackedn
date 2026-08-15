package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.livechat.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.whatsapp.WhatsAppOutboundService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class LiveSupportService {
    private static final Logger log = LoggerFactory.getLogger(LiveSupportService.class);

    @Autowired private ContactRepository contactRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private LiveChatAssignmentRepository assignmentRepository;
    @Autowired private LiveChatQueueRepository queueRepository;
    @Autowired private TenantLiveChatSettingsRepository settingsRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private LiveChatAuditService auditService;
    @Autowired private LiveChatPresenceService presenceService;
    @Autowired private LiveChatAuthorizationService authorizationService;
    @Autowired private WhatsAppOutboundService outboundService;
    @Autowired private ObjectMapper objectMapper;

    /**
     * Request Human Support (WhatsApp menu / button triggered)
     */
    public SupportState requestHumanSupport(Contact contact, String requestId) {
        if (contact == null) return SupportState.IDLE;

        Tenant tenant = contact.getTenant() != null ? contact.getTenant() : (contact.getOwner() != null ? contact.getOwner().getTenant() : null);
        if (tenant == null) {
            log.error("Cannot process support request: Tenant is null for contact {}", contact.getId());
            return SupportState.IDLE;
        }

        // Idempotency Check: If contact is already QUEUED or ASSIGNED, return current state
        if (contact.getSupportState() == SupportState.QUEUED || contact.getSupportState() == SupportState.ASSIGNED) {
            log.info("Idempotency: Contact {} already in state {}", contact.getId(), contact.getSupportState());
            return contact.getSupportState();
        }

        // Always pause bot on human support request
        contact.setBotPaused(true);

        TenantLiveChatSettings settings = getOrCreateSettings(tenant);
        int heartbeatTimeout = settings.getHeartbeatTimeoutSeconds();
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(heartbeatTimeout);

        // Record Audit for request
        auditService.recordAudit(tenant.getId(), contact.getId(), null, LiveChatAuditLog.AuditAction.CHAT_REQUESTED, null, null, requestId, null);

        // Hierarchy search: 1. AGENTS
        User selectedStaff = findEligibleStaff(tenant, User.Role.AGENT, threshold, settings.getMaxConcurrentChats());

        // If no agent, check routing strategy for ADMIN -> OWNER
        if (selectedStaff == null && settings.getRoutingStrategy() == TenantLiveChatSettings.RoutingStrategy.AGENT_ADMIN_OWNER) {
            selectedStaff = findEligibleStaff(tenant, User.Role.ADMIN, threshold, settings.getMaxConcurrentChats());
            if (selectedStaff == null) {
                selectedStaff = findEligibleStaff(tenant, User.Role.OWNER, threshold, settings.getMaxConcurrentChats());
            }
        }

        if (selectedStaff != null) {
            // Assign to staff
            assignContactToUser(tenant, contact, selectedStaff, null, false, null, requestId);
            
            // Notify WhatsApp customer
            try {
                outboundService.sendText(contact, "You are now connected with support agent " + selectedStaff.getDisplayName() + "! How can we assist you today?", contact.getWhatsappConfig(), contact.getOwner());
            } catch (Exception e) {
                log.error("Failed sending WA assignment notice", e);
            }
            return SupportState.ASSIGNED;
        } else {
            // Place in Queue
            LocalDateTime slaExpiresAt = LocalDateTime.now().plusMinutes(settings.getSlaMinutes());
            LiveChatQueue queue = new LiveChatQueue(tenant, contact, slaExpiresAt);
            queueRepository.save(queue);

            contact.setSupportState(SupportState.QUEUED);
            contactRepository.save(contact);

            long position = queueRepository.countQueuedBefore(tenant, queue.getQueuedAt(), queue.getId()) + 1;

            auditService.recordAudit(tenant.getId(), contact.getId(), null, LiveChatAuditLog.AuditAction.CHAT_QUEUED, null, null, requestId, "Queue Position #" + position);

            // Create Outbox Event
            createOutboxEvent(tenant.getId(), "CHAT_QUEUED", contact.getId().toString(), Map.of(
                    "contactId", contact.getId().toString(),
                    "contactName", contact.getName() != null ? contact.getName() : "Customer",
                    "contactPhone", contact.getWaId(),
                    "queuePosition", position,
                    "slaMinutes", settings.getSlaMinutes()
            ));

            // Notify WhatsApp customer
            try {
                outboundService.sendText(contact, "All support agents are currently busy. You have been placed in queue at position #" + position + ". Expected wait time: 30 to 60 minutes. Please hold on!", contact.getWhatsappConfig(), contact.getOwner());
            } catch (Exception e) {
                log.error("Failed sending WA queue notice", e);
            }
            return SupportState.QUEUED;
        }
    }

    /**
     * Process Queue for Tenant: Called whenever an agent resolves a chat or capacity opens up
     */
    public void processQueue(Tenant tenant) {
        if (tenant == null) return;

        List<LiveChatQueue> queuedItems = queueRepository.findQueuedByTenantForUpdate(tenant);
        if (queuedItems.isEmpty()) return;

        TenantLiveChatSettings settings = getOrCreateSettings(tenant);
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(settings.getHeartbeatTimeoutSeconds());

        for (LiveChatQueue queueItem : queuedItems) {
            User availableStaff = findEligibleStaff(tenant, User.Role.AGENT, threshold, settings.getMaxConcurrentChats());
            if (availableStaff == null && settings.getRoutingStrategy() == TenantLiveChatSettings.RoutingStrategy.AGENT_ADMIN_OWNER) {
                availableStaff = findEligibleStaff(tenant, User.Role.ADMIN, threshold, settings.getMaxConcurrentChats());
                if (availableStaff == null) {
                    availableStaff = findEligibleStaff(tenant, User.Role.OWNER, threshold, settings.getMaxConcurrentChats());
                }
            }

            if (availableStaff == null) break; // All staff still busy

            // Claim queue item
            queueItem.setStatus(LiveChatQueue.QueueStatus.ASSIGNED);
            queueRepository.save(queueItem);

            Contact contact = queueItem.getContact();
            assignContactToUser(tenant, contact, availableStaff, null, false, null, "AUTO-DEQUEUE");

            // Notify WhatsApp customer
            try {
                outboundService.sendText(contact, "An agent (" + availableStaff.getDisplayName() + ") is now available and has joined your chat!", contact.getWhatsappConfig(), contact.getOwner());
            } catch (Exception e) {
                log.error("Failed sending WA dequeue notice", e);
            }
        }
    }

    /**
     * Takeover Chat (Admin / Owner override)
     */
    public void takeoverChat(UUID contactId, User takeoverUser, String reason, boolean forceTakeover, String requestId) {
        Contact contact = contactRepository.findById(contactId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        Tenant tenant = contact.getTenant() != null ? contact.getTenant() : contact.getOwner().getTenant();

        if (!authorizationService.canTakeover(contact, takeoverUser)) {
            throw new SecurityException("User lacks permission to takeover chat");
        }

        TenantLiveChatSettings settings = getOrCreateSettings(tenant);
        long activeCount = assignmentRepository.countActiveAssignmentsByUserAndTenant(takeoverUser, tenant);

        if (activeCount >= settings.getMaxConcurrentChats() && !forceTakeover) {
            throw new IllegalStateException("Takeover user is at capacity limit (" + activeCount + "/" + settings.getMaxConcurrentChats() + "). Use Force Takeover with reason.");
        }

        if (forceTakeover && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("Reason is mandatory for Forced Takeover");
        }

        User previousAgent = contact.getAssignedAgent();

        // Release old assignment
        Optional<LiveChatAssignment> activeOpt = assignmentRepository.findByContactAndTenantAndStatus(contact, tenant, LiveChatAssignment.AssignmentStatus.ACTIVE);
        activeOpt.ifPresent(a -> {
            a.setStatus(LiveChatAssignment.AssignmentStatus.CANCELLED);
            a.setReleasedAt(LocalDateTime.now());
            assignmentRepository.save(a);
        });

        // Assign to takeover user
        assignContactToUser(tenant, contact, takeoverUser, previousAgent, forceTakeover, reason, requestId);

        auditService.recordAudit(tenant.getId(), contact.getId(), takeoverUser.getId(), LiveChatAuditLog.AuditAction.CHAT_TAKEN_OVER, previousAgent != null ? previousAgent.getId() : null, takeoverUser.getId(), requestId, "Takeover: " + (reason != null ? reason : "Direct Takeover"));

        // Create Outbox event for Takeover Email to ALL staff
        createOutboxEvent(tenant.getId(), "CHAT_TAKEN_OVER", contact.getId().toString(), Map.of(
                "contactId", contact.getId().toString(),
                "contactName", contact.getName() != null ? contact.getName() : "Customer",
                "contactPhone", contact.getWaId(),
                "takeoverByName", takeoverUser.getDisplayName(),
                "reason", reason != null ? reason : "N/A"
        ));

        // Notify WhatsApp customer
        try {
            outboundService.sendText(contact, "Your chat session has been taken over by supervisor " + takeoverUser.getDisplayName() + ".", contact.getWhatsappConfig(), contact.getOwner());
        } catch (Exception e) {
            log.error("Failed sending WA takeover notice", e);
        }
    }

    /**
     * Transfer Chat to another staff member
     */
    public void transferChat(UUID contactId, User targetUser, User currentUser, String reason, String requestId) {
        Contact contact = contactRepository.findById(contactId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        Tenant tenant = contact.getTenant() != null ? contact.getTenant() : contact.getOwner().getTenant();

        if (!authorizationService.canTransfer(contact, currentUser)) {
            throw new SecurityException("User lacks permission to transfer this chat");
        }

        TenantLiveChatSettings settings = getOrCreateSettings(tenant);
        long targetActiveCount = assignmentRepository.countActiveAssignmentsByUserAndTenant(targetUser, tenant);

        if (targetActiveCount >= settings.getMaxConcurrentChats()) {
            throw new IllegalStateException("Target staff member " + targetUser.getDisplayName() + " is at full capacity.");
        }

        User previousAgent = contact.getAssignedAgent();

        // Release old assignment
        Optional<LiveChatAssignment> activeOpt = assignmentRepository.findByContactAndTenantAndStatus(contact, tenant, LiveChatAssignment.AssignmentStatus.ACTIVE);
        activeOpt.ifPresent(a -> {
            a.setStatus(LiveChatAssignment.AssignmentStatus.RESOLVED);
            a.setReleasedAt(LocalDateTime.now());
            a.setTransferReason(reason);
            assignmentRepository.save(a);
        });

        // Assign to target
        assignContactToUser(tenant, contact, targetUser, previousAgent, false, reason, requestId);

        auditService.recordAudit(tenant.getId(), contact.getId(), currentUser.getId(), LiveChatAuditLog.AuditAction.CHAT_TRANSFERRED, previousAgent != null ? previousAgent.getId() : null, targetUser.getId(), requestId, "Transfer reason: " + (reason != null ? reason : "N/A"));

        // Create Outbox Event for Transfer email
        createOutboxEvent(tenant.getId(), "CHAT_TRANSFERRED", contact.getId().toString(), Map.of(
                "contactId", contact.getId().toString(),
                "contactName", contact.getName() != null ? contact.getName() : "Customer",
                "contactPhone", contact.getWaId(),
                "targetUserEmail", targetUser.getEmail(),
                "targetUserName", targetUser.getDisplayName(),
                "transferredByName", currentUser.getDisplayName()
        ));

        // Notify WhatsApp customer
        try {
            outboundService.sendText(contact, "Your chat has been transferred to agent " + targetUser.getDisplayName() + ".", contact.getWhatsappConfig(), contact.getOwner());
        } catch (Exception e) {
            log.error("Failed sending WA transfer notice", e);
        }
    }

    /**
     * Resolve Chat
     */
    public void resolveChat(UUID contactId, User currentUser, String requestId) {
        Contact contact = contactRepository.findById(contactId).orElseThrow(() -> new IllegalArgumentException("Contact not found"));
        Tenant tenant = contact.getTenant() != null ? contact.getTenant() : contact.getOwner().getTenant();

        if (!authorizationService.canResolve(contact, currentUser)) {
            throw new SecurityException("User lacks permission to resolve this chat");
        }

        // Release active assignment
        Optional<LiveChatAssignment> activeOpt = assignmentRepository.findByContactAndTenantAndStatus(contact, tenant, LiveChatAssignment.AssignmentStatus.ACTIVE);
        activeOpt.ifPresent(a -> {
            a.setStatus(LiveChatAssignment.AssignmentStatus.RESOLVED);
            a.setReleasedAt(LocalDateTime.now());
            assignmentRepository.save(a);
        });

        // Update contact support state and resume bot
        contact.setSupportState(SupportState.RESOLVED);
        TenantLiveChatSettings settings = getOrCreateSettings(tenant);
        if (settings.getAutoResumeBotOnResolve()) {
            contact.setBotPaused(false);
        }
        contact.setAssignedAgent(null);
        contactRepository.save(contact);

        auditService.recordAudit(tenant.getId(), contact.getId(), currentUser.getId(), LiveChatAuditLog.AuditAction.CHAT_RESOLVED, currentUser.getId(), null, requestId, "Support chat resolved by agent");

        createOutboxEvent(tenant.getId(), "CHAT_RESOLVED", contact.getId().toString(), Map.of(
                "contactId", contact.getId().toString(),
                "resolvedByName", currentUser.getDisplayName()
        ));

        // Notify WhatsApp customer
        try {
            outboundService.sendText(contact, "Thank you for contacting support! Your chat session has been resolved. The AI assistant is now re-enabled.", contact.getWhatsappConfig(), contact.getOwner());
        } catch (Exception e) {
            log.error("Failed sending WA resolve notice", e);
        }

        // Auto-process Queue to assign waiting customers!
        processQueue(tenant);
    }

    /**
     * Handle Customer Re-engagement when Messaging After Resolution
     */
    public void handleIncomingCustomerMessage(Contact contact) {
        if (contact == null) return;

        if (contact.getSupportState() == SupportState.RESOLVED) {
            contact.setSupportState(SupportState.REOPENED);
            contactRepository.save(contact);
            log.info("Contact {} transition state to REOPENED on new message", contact.getId());
        }
    }

    private User findEligibleStaff(Tenant tenant, User.Role role, LocalDateTime threshold, int maxCapacity) {
        List<User> candidates = userRepository.findCandidateStaffWithLock(tenant, role, threshold);
        for (User u : candidates) {
            long activeCount = assignmentRepository.countActiveAssignmentsByUserAndTenant(u, tenant);
            if (activeCount < maxCapacity) {
                return u;
            }
        }
        return null;
    }

    private void assignContactToUser(Tenant tenant, Contact contact, User staffUser, User transferFrom, boolean capacityOverride, String reason, String requestId) {
        LiveChatAssignment assignment = new LiveChatAssignment(tenant, contact, staffUser, staffUser, LiveChatAssignment.AssignmentStatus.ACTIVE);
        assignment.setTransferFrom(transferFrom);
        assignment.setTransferReason(reason);
        assignment.setCapacityOverride(capacityOverride);
        assignmentRepository.save(assignment);

        contact.setAssignedAgent(staffUser);
        contact.setSupportState(SupportState.ASSIGNED);
        contact.setBotPaused(true);
        contactRepository.save(contact);

        auditService.recordAudit(tenant.getId(), contact.getId(), staffUser.getId(), LiveChatAuditLog.AuditAction.CHAT_ASSIGNED, transferFrom != null ? transferFrom.getId() : null, staffUser.getId(), requestId, "Assigned to " + staffUser.getDisplayName());

        // Create Outbox Event for Email & WebSockets
        createOutboxEvent(tenant.getId(), "CHAT_ASSIGNED", contact.getId().toString(), Map.of(
                "contactId", contact.getId().toString(),
                "contactName", contact.getName() != null ? contact.getName() : "Customer",
                "contactPhone", contact.getWaId(),
                "assignedToId", staffUser.getId().toString(),
                "assignedToName", staffUser.getDisplayName(),
                "assignedToEmail", staffUser.getEmail() != null ? staffUser.getEmail() : ""
        ));
    }

    private TenantLiveChatSettings getOrCreateSettings(Tenant tenant) {
        return settingsRepository.findByTenant(tenant)
                .orElseGet(() -> settingsRepository.save(new TenantLiveChatSettings(tenant)));
    }

    private void createOutboxEvent(UUID tenantId, String eventType, String aggregateId, Map<String, Object> payloadMap) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(payloadMap);
            OutboxEvent outboxEvent = new OutboxEvent(tenantId, eventType, aggregateId, jsonPayload);
            outboxEventRepository.save(outboxEvent);
        } catch (Exception e) {
            log.error("Failed creating outbox event {}", eventType, e);
        }
    }
}
