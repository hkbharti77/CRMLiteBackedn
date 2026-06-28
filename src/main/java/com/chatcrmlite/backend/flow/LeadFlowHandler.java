package com.chatcrmlite.backend.flow;

import com.chatcrmlite.backend.event.LeadCreatedEvent;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.services.lead.LeadService;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FlowHandler for ENQUIRY and LEAD_CAPTURE flows.
 *
 * Responsible for:
 * 1. Creating a Lead from the collected flow data
 * 2. Setting dealLabel from the primary interest field
 * 3. Updating contact name if captured during the flow
 * 4. Appending a structured enquiry summary to the Lead
 * 5. Returning a personalised user-facing confirmation message
 *
 * Handles both ENQUIRY (status=FOLLOW_UP) and LEAD_CAPTURE (status=INTERESTED) flow types.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadFlowHandler implements FlowHandler {

    private final LeadRepository leadRepository;
    private final LeadService leadService;
    private final ContactRepository contactRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ReferenceNumberService referenceNumberService;
    private final com.chatcrmlite.backend.services.tenant.QuotaEnforcerService quotaEnforcerService;

    // ── Keys that represent the primary "interest" for a lead label ─────────
    // Checked in priority order — first match wins
    private static final String[] DEAL_LABEL_KEYS = {
        "property_type",    // property brokers
        "vehicle_type",     // auto/car dealers
        "project_type",     // interior designers, web designers
        "policy_type",      // insurance
        "destination",      // travel
        "event_type",       // event planners
        "interest",         // music/art
        "subject",          // tutors
        "country",          // study abroad
        "requirement",      // generic
    };

    // ── Keys that should appear first in the enquiry summary ────────────────
    private static final String[] PRIORITY_KEYS = {
        "name", "requirement", "policy_type", "property_type", "vehicle_type",
        "project_type", "destination", "event_type", "interest", "subject",
        "country", "course_level", "consultation_type"
    };

    @Override
    public boolean supports(FlowType flowType) {
        return flowType == FlowType.ENQUIRY || flowType == FlowType.LEAD_CAPTURE;
    }

    @Override
    public FlowResponse handle(FlowContext context) {
        try {
            Map<String, String> data = context.getCollectedData();
            boolean isLeadCapture = context.getFlowType() == FlowType.LEAD_CAPTURE;

            // ── 1. Update contact name if captured in flow ──────────────────
            String capturedName = data.get("name");
            if (capturedName != null && !capturedName.isBlank()) {
                Contact contact = context.getContact();
                if (contact.getName() == null || contact.getName().startsWith("WhatsApp User")) {
                    contact.setName(capturedName.trim());
                    contactRepository.save(contact);
                    log.info("[LeadFlowHandler] Updated contact name to '{}' for waId={}",
                            capturedName, contact.getWaId());
                }
            }

            // ── 2. Determine lead status ────────────────────────────────────
            Lead.LeadStatus initialStatus = isLeadCapture
                    ? Lead.LeadStatus.INTERESTED
                    : Lead.LeadStatus.FOLLOW_UP;

            // ── 3. Extract deal label from primary interest field ───────────
            String dealLabel = extractDealLabel(data);

            // ── 4. Create the lead ──────────────────────────────────────────
            quotaEnforcerService.verifyLeadQuota(context.getOwner().getTenant().getId());
            String leadNumber = referenceNumberService.generate(context.getOwner(), ReferenceNumberService.EntityType.LEAD);
            Lead lead = Lead.builder()
                    .contact(context.getContact())
                    .owner(context.getOwner())
                    .status(initialStatus)
                    .dealLabel(dealLabel)
                    .leadNumber(leadNumber)
                    .build();
            lead = leadRepository.save(lead);

            // ── 5. Build ordered enquiry summary ───────────────────────────
            String summary = buildOrderedSummary(data);

            String source = isLeadCapture
                    ? "WhatsApp Lead Capture Flow"
                    : "WhatsApp Enquiry Flow";

            leadService.appendEnquiryToLead(lead, summary, "FLOW", source, data);

            // Publish event so EmailNotificationListener sends owner notification
            eventPublisher.publishEvent(new LeadCreatedEvent(this, lead, "FLOW"));

            log.info("[LeadFlowHandler] Lead {} ({}) created for contact {} via FLOW — label='{}'",
                    lead.getId(), initialStatus, context.getContact().getWaId(), dealLabel);

            // ── 6. Build personalised confirmation ──────────────────────────
            String confirmation = buildConfirmation(data, isLeadCapture, dealLabel, leadNumber);
            return FlowResponse.ok(confirmation);

        } catch (Exception e) {
            log.error("[LeadFlowHandler] Failed to create lead for contact {}: {}",
                    context.getContact().getWaId(), e.getMessage(), e);
            return FlowResponse.failure(e.getMessage());
        }
    }

    /**
     * Extracts the most meaningful label for the lead from collected data.
     * Checks DEAL_LABEL_KEYS in priority order — first non-blank match wins.
     */
    private String extractDealLabel(Map<String, String> data) {
        log.debug("[LeadFlowHandler] extractDealLabel called with data keys: {}", data.keySet());
        log.debug("[LeadFlowHandler] extractDealLabel full data: {}", data);
        
        for (String key : DEAL_LABEL_KEYS) {
            String val = data.get(key);
            if (val != null && !val.isBlank()) {
                log.debug("[LeadFlowHandler] Found dealLabel='{}' from key='{}'", val, key);
                return val;
            }
        }
        log.warn("[LeadFlowHandler] No dealLabel found in data, using default 'General Enquiry'");
        return "General Enquiry";
    }

    /**
     * Builds an ordered, human-readable enquiry summary.
     * Priority keys appear first, remaining keys follow alphabetically.
     * Skips internal keys like "selection".
     */
    private String buildOrderedSummary(Map<String, String> data) {
        // Use LinkedHashMap to preserve insertion order
        Map<String, String> ordered = new LinkedHashMap<>();

        // Add priority keys first (in defined order)
        for (String key : PRIORITY_KEYS) {
            if (data.containsKey(key)) {
                ordered.put(key, data.get(key));
            }
        }

        // Add remaining keys (sorted alphabetically for consistency)
        data.entrySet().stream()
                .filter(e -> !ordered.containsKey(e.getKey()))
                .filter(e -> !"selection".equals(e.getKey()))  // skip internal flow key
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));

        StringBuilder sb = new StringBuilder();
        ordered.forEach((k, v) ->
            sb.append(capitalize(k)).append(": ").append(v).append("\n")
        );
        return sb.toString().trim();
    }

    /**
     * Builds a personalised confirmation message.
     * Uses the captured name, deal label, and lead reference number to make it feel human.
     */
    private String buildConfirmation(Map<String, String> data, boolean isLeadCapture, String dealLabel, String leadNumber) {
        String name = data.get("name");
        String firstName = (name != null && !name.isBlank())
                ? name.trim().split(" ")[0]
                : null;

        StringBuilder msg = new StringBuilder();

        if (isLeadCapture) {
            msg.append("🤝 *Thank you");
            if (firstName != null) msg.append(", ").append(firstName);
            msg.append("!*\n\n");
            msg.append("We've noted your interest in *").append(dealLabel).append("*.\n");
            msg.append("Your reference number is: *").append(leadNumber).append("*\n\n");
            msg.append("Our expert will contact you within 24 hours with the best options for you.");
        } else {
            msg.append("📋 *Thanks");
            if (firstName != null) msg.append(", ").append(firstName);
            msg.append("!*\n\n");
            msg.append("We've received your enquiry about *").append(dealLabel).append("*.\n");
            msg.append("Your reference number is: *").append(leadNumber).append("*\n\n");
            msg.append("Our team will reach out to you shortly.");
        }

        return msg.toString();
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1).replace('_', ' ');
    }
}
