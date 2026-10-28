package com.chatcrmlite.backend.services.crm;

import com.chatcrmlite.backend.dto.crm.ContactMergeHistoryDTO;
import com.chatcrmlite.backend.dto.crm.DuplicateCandidateDTO;
import com.chatcrmlite.backend.dto.crm.MergeContactRequestDTO;
import com.chatcrmlite.backend.dto.crm.MergeResultDTO;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tag;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.crm.ContactMergeHistory;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.crm.ContactMergeHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactMergeService {

    private final ContactRepository contactRepository;
    private final ContactMergeHistoryRepository mergeHistoryRepository;
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Search for duplicate contact candidates within the same tenant.
     * Evaluates matches on phone (waId), email, and normalized name.
     */
    @Transactional(readOnly = true)
    public List<DuplicateCandidateDTO> findDuplicateCandidates(UUID contactId, User actor) {
        Contact primary = getOwnedContactOrThrow(contactId, actor);

        UUID tenantId = actor.getTenant() != null ? actor.getTenant().getId() : null;
        if (tenantId == null) {
            return Collections.emptyList();
        }

        String phone = primary.getWaId();
        String email = primary.getEmail();
        String name = primary.getName();

        List<Contact> potentialDuplicates = contactRepository.findPotentialDuplicates(
                tenantId,
                contactId,
                (phone != null && !phone.isBlank()) ? phone.trim() : null,
                (email != null && !email.isBlank()) ? email.trim() : null,
                (name != null && !name.isBlank()) ? name.trim() : null
        );

        List<DuplicateCandidateDTO> candidates = new ArrayList<>();
        for (Contact duplicate : potentialDuplicates) {
            int score = 0;
            List<String> matchReasons = new ArrayList<>();

            if (phone != null && phone.equalsIgnoreCase(duplicate.getWaId())) {
                score += 50;
                matchReasons.add("PHONE_EXACT");
            }
            if (email != null && email.equalsIgnoreCase(duplicate.getEmail())) {
                score += 40;
                matchReasons.add("EMAIL_EXACT");
            }
            if (name != null && name.equalsIgnoreCase(duplicate.getName())) {
                score += 20;
                matchReasons.add("NAME_EXACT");
            }

            int finalScore = Math.min(score, 100);
            String primaryReason = matchReasons.isEmpty() ? "NAME_SIMILAR" : String.join(", ", matchReasons);

            long messageCount = countEntityForContact("chat_messages", duplicate.getId());
            long leadCount = countEntityForContact("leads", duplicate.getId());
            long appointmentCount = countEntityForContact("appointments", duplicate.getId());

            candidates.add(DuplicateCandidateDTO.builder()
                    .contactId(duplicate.getId())
                    .name(duplicate.getName())
                    .phone(duplicate.getWaId())
                    .email(duplicate.getEmail())
                    .source(duplicate.getSource())
                    .tags(duplicate.getTags().stream().map(Tag::getName).collect(Collectors.toList()))
                    .createdAt(null)
                    .matchReason(primaryReason)
                    .confidenceScore(finalScore)
                    .messageCount(messageCount)
                    .leadCount(leadCount)
                    .appointmentCount(appointmentCount)
                    .build());
        }

        candidates.sort((a, b) -> Integer.compare(b.getConfidenceScore(), a.getConfidenceScore()));
        return candidates;
    }

    /**
     * Atomically merges a secondary contact into the primary contact.
     * Re-parents all child entities, applies field resolutions, flags secondary as merged,
     * and logs the complete audit snapshot to contact_merge_history.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public MergeResultDTO executeContactMerge(UUID primaryContactId, MergeContactRequestDTO request, User actor) {
        if (primaryContactId == null || request.getSecondaryContactId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both primary and secondary contact IDs are required");
        }

        if (primaryContactId.equals(request.getSecondaryContactId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot merge a contact into itself");
        }

        Contact primary = getOwnedContactOrThrow(primaryContactId, actor);
        Contact secondary = getOwnedContactOrThrow(request.getSecondaryContactId(), actor);

        // Security check: both must belong to same tenant
        if (primary.getTenant() == null || secondary.getTenant() == null ||
                !primary.getTenant().getId().equals(secondary.getTenant().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-tenant contact merge is strictly prohibited");
        }

        if (primary.isMerged()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Primary contact has already been merged into another contact: " + primary.getMergedIntoId());
        }

        if (secondary.isMerged()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Secondary contact has already been merged into another contact: " + secondary.getMergedIntoId());
        }

        // Prevent circular merges
        if (primaryContactId.equals(secondary.getMergedIntoId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Circular merge detected");
        }

        log.info("[ContactMerge] Starting merge of secondary contactId={} into primary contactId={} by user={}",
                secondary.getId(), primary.getId(), actor.getEmail());

        // Capture pre-merge snapshots
        Map<String, Object> preMergeSnapshot = new LinkedHashMap<>();
        preMergeSnapshot.put("primary", snapshotContact(primary));
        preMergeSnapshot.put("secondary", snapshotContact(secondary));

        Map<String, Integer> transferredCounts = new LinkedHashMap<>();

        // 1. Re-parent relational child entities
        transferredCounts.put("messages", executeUpdate("UPDATE Message m SET m.contact = :primary WHERE m.contact = :secondary", primary, secondary));
        transferredCounts.put("leads", executeUpdate("UPDATE Lead l SET l.contact = :primary WHERE l.contact = :secondary", primary, secondary));
        transferredCounts.put("appointments", executeUpdate("UPDATE Appointment a SET a.contact = :primary WHERE a.contact = :secondary", primary, secondary));
        transferredCounts.put("bookings", executeUpdate("UPDATE Booking b SET b.contact = :primary WHERE b.contact = :secondary", primary, secondary));
        transferredCounts.put("tickets", executeUpdate("UPDATE Ticket t SET t.contact = :primary WHERE t.contact = :secondary", primary, secondary));
        transferredCounts.put("activityLogs", executeUpdate("UPDATE ActivityLog al SET al.contact = :primary WHERE al.contact = :secondary", primary, secondary));
        transferredCounts.put("liveChatQueues", executeUpdate("UPDATE LiveChatQueue q SET q.contact = :primary WHERE q.contact = :secondary", primary, secondary));
        transferredCounts.put("liveChatAssignments", executeUpdate("UPDATE LiveChatAssignment a SET a.contact = :primary WHERE a.contact = :secondary", primary, secondary));
        transferredCounts.put("flowSubmissions", executeUpdate("UPDATE FlowSubmission fs SET fs.contact = :primary WHERE fs.contact = :secondary", primary, secondary));
        transferredCounts.put("flowSendSessions", executeUpdate("UPDATE FlowSendSession fss SET fss.contact = :primary WHERE fss.contact = :secondary", primary, secondary));
        transferredCounts.put("dripParticipants", executeUpdate("UPDATE DripParticipant dp SET dp.contact = :primary WHERE dp.contact = :secondary", primary, secondary));
        transferredCounts.put("campaignRecipients", executeUpdate("UPDATE WhatsAppCampaignRecipient r SET r.contact = :primary WHERE r.contact = :secondary", primary, secondary));

        // 2. Handle unique conversation_states constraint
        handleConversationStates(primary, secondary);

        // 3. Resolve Tags
        resolveTags(primary, secondary, request.getTagResolution());

        // 4. Resolve Scalar Fields according to request
        Map<String, String> fieldResolutions = request.getFieldResolutions() != null ? request.getFieldResolutions() : Collections.emptyMap();
        applyFieldResolutions(primary, secondary, fieldResolutions);

        // 5. Flag secondary contact as merged
        secondary.setMerged(true);
        secondary.setMergedIntoId(primary.getId());

        contactRepository.save(primary);
        contactRepository.save(secondary);

        // 6. Record immutable audit ledger
        try {
            String snapshotJson = objectMapper.writeValueAsString(preMergeSnapshot);
            String fieldsResolvedJson = objectMapper.writeValueAsString(fieldResolutions);
            String transferredJson = objectMapper.writeValueAsString(transferredCounts);

            ContactMergeHistory history = ContactMergeHistory.builder()
                    .tenant(actor.getTenant())
                    .primaryContact(primary)
                    .secondaryContact(secondary)
                    .mergedByUserId(actor.getId())
                    .mergedByUserEmail(actor.getEmail())
                    .preMergeSnapshotJson(snapshotJson)
                    .fieldsResolvedJson(fieldsResolvedJson)
                    .transferredEntitiesJson(transferredJson)
                    .auditNote(request.getAuditNote())
                    .mergedAt(Instant.now())
                    .build();

            mergeHistoryRepository.save(history);
        } catch (Exception e) {
            log.error("[ContactMerge] Failed to serialize audit snapshot for primary={}: {}", primary.getId(), e.getMessage(), e);
        }

        log.info("[ContactMerge] Successfully completed merge of secondary={} into primary={}. Transferred counts: {}",
                secondary.getId(), primary.getId(), transferredCounts);

        return MergeResultDTO.builder()
                .success(true)
                .primaryContactId(primary.getId())
                .secondaryContactId(secondary.getId())
                .transferredCounts(transferredCounts)
                .mergedAt(Instant.now())
                .message("Contact " + secondary.getName() + " successfully merged into " + primary.getName())
                .build();
    }

    /**
     * Retrieve merge history for a given contact.
     */
    @Transactional(readOnly = true)
    public List<ContactMergeHistoryDTO> getMergeHistory(UUID contactId, User actor) {
        UUID tenantId = actor.getTenant() != null ? actor.getTenant().getId() : null;
        if (tenantId == null) {
            return Collections.emptyList();
        }

        List<ContactMergeHistory> historyList = mergeHistoryRepository.findHistoryForContact(tenantId, contactId);
        return historyList.stream().map(h -> ContactMergeHistoryDTO.builder()
                .id(h.getId())
                .primaryContactId(h.getPrimaryContact().getId())
                .primaryContactName(h.getPrimaryContact().getName())
                .secondaryContactId(h.getSecondaryContact().getId())
                .secondaryContactName(h.getSecondaryContact().getName())
                .mergedByUserId(h.getMergedByUserId())
                .mergedByUserEmail(h.getMergedByUserEmail())
                .preMergeSnapshotJson(h.getPreMergeSnapshotJson())
                .fieldsResolvedJson(h.getFieldsResolvedJson())
                .transferredEntitiesJson(h.getTransferredEntitiesJson())
                .auditNote(h.getAuditNote())
                .mergedAt(h.getMergedAt())
                .build()
        ).collect(Collectors.toList());
    }

    private void applyFieldResolutions(Contact primary, Contact secondary, Map<String, String> resolutions) {
        if ("SECONDARY".equalsIgnoreCase(resolutions.get("name")) && secondary.getName() != null) {
            primary.setName(secondary.getName());
        }

        if ("SECONDARY".equalsIgnoreCase(resolutions.get("email")) && secondary.getEmail() != null) {
            primary.setEmail(secondary.getEmail());
        }

        if ("SECONDARY".equalsIgnoreCase(resolutions.get("source")) && secondary.getSource() != null) {
            primary.setSource(secondary.getSource());
        }

        if ("SECONDARY".equalsIgnoreCase(resolutions.get("marketingOptedOut"))) {
            primary.setMarketingOptedOut(secondary.isMarketingOptedOut());
        }

        if ("SECONDARY".equalsIgnoreCase(resolutions.get("botPaused"))) {
            primary.setBotPaused(secondary.isBotPaused());
        }

        // Phone / waId resolution: safeguard against unique constraint uk_contact_waid_owner
        if ("SECONDARY".equalsIgnoreCase(resolutions.get("phone")) && secondary.getWaId() != null) {
            String secondaryPhone = secondary.getWaId();
            // Displace secondary waId first to prevent unique key violation
            secondary.setWaId(secondaryPhone + "_merged_" + System.currentTimeMillis());
            primary.setWaId(secondaryPhone);
        }
    }

    private void resolveTags(Contact primary, Contact secondary, String strategy) {
        if ("SECONDARY_ONLY".equalsIgnoreCase(strategy)) {
            primary.setTags(new ArrayList<>(secondary.getTags()));
        } else if (!"PRIMARY_ONLY".equalsIgnoreCase(strategy)) {
            // Default: UNION
            Set<UUID> existingTagIds = primary.getTags().stream().map(Tag::getId).collect(Collectors.toSet());
            for (Tag tag : secondary.getTags()) {
                if (!existingTagIds.contains(tag.getId())) {
                    primary.getTags().add(tag);
                    existingTagIds.add(tag.getId());
                }
            }
        }
    }

    private void handleConversationStates(Contact primary, Contact secondary) {
        try {
            Long primaryStateCount = (Long) entityManager.createQuery(
                    "SELECT COUNT(cs) FROM ConversationState cs WHERE cs.contact = :primary")
                    .setParameter("primary", primary)
                    .getSingleResult();

            if (primaryStateCount > 0) {
                // Primary already has state; delete secondary state to prevent UNIQUE violation
                entityManager.createQuery(
                        "DELETE FROM ConversationState cs WHERE cs.contact = :secondary")
                        .setParameter("secondary", secondary)
                        .executeUpdate();
            } else {
                // Secondary has state; re-parent to primary
                entityManager.createQuery(
                        "UPDATE ConversationState cs SET cs.contact = :primary WHERE cs.contact = :secondary")
                        .setParameter("primary", primary)
                        .setParameter("secondary", secondary)
                        .executeUpdate();
            }
        } catch (Exception e) {
            log.warn("[ContactMerge] Note during ConversationState reconciliation: {}", e.getMessage());
        }
    }

    private int executeUpdate(String jpql, Contact primary, Contact secondary) {
        try {
            return entityManager.createQuery(jpql)
                    .setParameter("primary", primary)
                    .setParameter("secondary", secondary)
                    .executeUpdate();
        } catch (Exception e) {
            log.warn("[ContactMerge] Could not execute {}: {}", jpql, e.getMessage());
            return 0;
        }
    }

    private long countEntityForContact(String tableName, UUID contactId) {
        try {
            Number count = (Number) entityManager.createNativeQuery(
                    "SELECT COUNT(*) FROM " + tableName + " WHERE contact_id = :contactId")
                    .setParameter("contactId", contactId)
                    .getSingleResult();
            return count != null ? count.longValue() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> snapshotContact(Contact c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", c.getId());
        map.put("name", c.getName());
        map.put("waId", c.getWaId());
        map.put("email", c.getEmail());
        map.put("source", c.getSource());
        map.put("botPaused", c.isBotPaused());
        map.put("marketingOptedOut", c.isMarketingOptedOut());
        map.put("tags", c.getTags().stream().map(Tag::getName).collect(Collectors.toList()));
        return map;
    }

    private Contact getOwnedContactOrThrow(UUID contactId, User actor) {
        Contact contact = contactRepository.findByIdWithTags(contactId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contact not found with ID: " + contactId));

        if (actor.getTenant() != null && contact.getTenant() != null &&
                !contact.getTenant().getId().equals(actor.getTenant().getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: contact belongs to another tenant");
        }

        return contact;
    }
}
