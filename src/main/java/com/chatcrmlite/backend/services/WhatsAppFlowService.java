package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.clients.WhatsAppClient;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.models.ConversationState.FlowType;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.FlowTemplateEngine.FlowBlueprint;
import com.chatcrmlite.backend.services.FlowTemplateEngine.FlowStep;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Enterprise WhatsApp Conversational Flow Service.
 *
 * Responsibilities:
 * 1. Detect when a menu-selection button was clicked to START a niche-specific flow.
 * 2. Drive the multi-step conversation one question at a time.
 * 3. On flow completion, apply the correct CRM action (Appointment / Booking / Lead).
 * 4. Auto-cleanup stale flows older than 24 hours.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppFlowService {

    private final ConversationStateRepository stateRepository;
    private final LeadRepository leadRepository;
    private final WhatsAppConfigRepository configRepository;
    private final BusinessServiceRepository businessServiceRepository;
    private final WhatsAppClient whatsappClient;
    private final ObjectMapper objectMapper;
    private final FlowTemplateEngine templateEngine;
    private final LeadService leadService;
    private final AppointmentService appointmentService;
    private final BookingService bookingService;

    // ════════════════════════════════════════════════════════════════════════
    //  Entry Point — Called by WhatsAppService for every incoming message
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Processes a single incoming message in the context of a conversation flow.
     *
     * @return true  if the message was consumed by the flow engine (no further processing needed)
     *         false if the message is NOT part of any flow (plain chat — handle normally)
     */
    @Transactional
    public boolean processFlow(Contact contact, User owner, String messageText, String selectionId, boolean isInteractiveSelection) {

        // ── Case 1: Contact is already inside an active flow ─────────────────
        Optional<ConversationState> existingState = stateRepository.findByContact(contact);
        if (existingState.isPresent()) {
            ConversationState state = existingState.get();

            // ── Case 1.1: Pagination or "Other" click within the flow ─────────
            if (isInteractiveSelection && selectionId != null) {
                if (selectionId.startsWith("flow_page_")) {
                    int nextPg = Integer.parseInt(selectionId.replace("flow_page_", ""));
                    handleDynamicServiceList(state, contact, owner, nextPg);
                    return true;
                }
                if ("flow_other".equals(selectionId)) {
                    saveAnswer(state, "is_other_pending", "true");
                    stateRepository.save(state);
                    whatsappClient.sendMessage(contact.getWaId(),
                            "\uD83D\uDCDD Got it! Please type the service or specific requirement you are looking for below:",
                            configRepository.findByUserId(owner.getId()).get().getAccessToken(),
                            configRepository.findByUserId(owner.getId()).get().getPhoneNumberId());
                    return true;
                }
            }

            advanceFlow(state, contact, owner, messageText);
            return true; // consumed
        }

        // ── Case 2: Dynamic Feature Buttons Handle (Trust, Social, Offer, SOS) ──
        if (isInteractiveSelection && selectionId != null && selectionId.startsWith("btn_")) {
            handleDynamicSelection(contact, owner, selectionId);
            return true;
        }

        // ── Case 3: New interactive menu selection → START a flow ────────────
        // ONLY start if it's the dedicated 'trigger_flow' ID
        if (isInteractiveSelection && "trigger_flow".equals(selectionId)) {
            String subCategory = owner.getBusinessSubType();
            FlowBlueprint blueprint = templateEngine.getBlueprint(subCategory);

            // Create state record
            ConversationState state = ConversationState.builder()
                    .contact(contact)
                    .flowType(blueprint.getFlowType())
                    .currentStep(0)
                    .collectedData("{}")
                    .build();
            stateRepository.save(state);

            log.info("[Flow] Started '{}' flow for contact {} (sub-category: {})",
                     blueprint.getFlowType(), contact.getWaId(), subCategory);

            // Save the first selection as answer to implicit "step 0" (the menu choice itself)
            saveAnswer(state, "selection", messageText);

            // Send the first real question (step 0 of the blueprint)
            sendStep(state, blueprint, contact, owner);
            return true; // consumed
        }

        // ── Case 3: Plain text with no active flow → not consumed ────────────
        return false;
    }

    /**
     * Resets any active conversation state for the contact.
     * Effectively ends any "stuck" flow immediately.
     */
    @Transactional
    public void resetFlow(Contact contact) {
        log.info("[Flow] Force resetting conversation state for contact={}", contact.getWaId());
        stateRepository.deleteByContact(contact);
    }

    private void handleDynamicSelection(Contact contact, User owner, String selectionId) {
        WhatsAppConfig config = configRepository.findByUserId(owner.getId()).orElse(null);
        if (config == null) return;

        String message = "";
        switch (selectionId) {
            case "btn_trust":
                message = "⭐ *Trust & Reviews*\nRead our client feedback and reviews here:\n" + 
                          (config.getReviewUrl() != null ? config.getReviewUrl() : "Coming soon!");
                break;
            case "btn_offer":
                message = "🎁 *Special Offer Inc.*\n" + 
                          (config.getOfferText() != null ? config.getOfferText() : "Stay tuned for upcoming deals!");
                break;
            case "btn_sos":
                String sos = config.getSosNote() != null ? config.getSosNote() : owner.getPhone();
                message = "🆘 *Human Support*\nNeed help? Contact us directly at:\n" + sos;
                break;
        }

        if (!message.isEmpty()) {
            whatsappClient.sendMessage(contact.getWaId(), message, config.getAccessToken(), config.getPhoneNumberId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Advance an in-progress flow one step
    // ════════════════════════════════════════════════════════════════════════

    private void advanceFlow(ConversationState state, Contact contact, User owner, String answer) {
        String subCategory = owner.getBusinessSubType();
        FlowBlueprint blueprint = templateEngine.getBlueprint(subCategory);
        List<FlowStep> steps = blueprint.getSteps();

        int stepIdx = state.getCurrentStep();
        if (stepIdx >= steps.size()) {
            stateRepository.delete(state);
            return;
        }

        FlowStep currentStep = steps.get(stepIdx);
        Map<String, String> data = parseData(state.getCollectedData());
        WhatsAppConfig config = configRepository.findByUserId(owner.getId()).get();

        // ── Case 1: "Other" fallback (Already marked/clicked previously) ──
        boolean wasOtherPending = "true".equals(data.get("is_other_pending"));
        if (wasOtherPending) {
            data.remove("is_other_pending");
            state.setCollectedData(serialize(data));
            saveAnswer(state, currentStep.getDataKey(), answer);
            state.setCurrentStep(stepIdx + 1);
            saveAndCheckCompletion(state, contact, owner, blueprint);
            return;
        }

        // ── Case 2: Email Validation ───────────────────────────────────────
        if ("email".equals(currentStep.getDataKey())) {
            String emailRegex = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$";
            if (!answer.matches(emailRegex)) {
                whatsappClient.sendMessage(contact.getWaId(),
                        "⚠️ Please enter a valid email address (example@email.com):",
                        config.getAccessToken(), config.getPhoneNumberId());
                return; // Do not advance
            }
        }

        // ── Case 3: Option/Service Validation ──────────────────────────────
        // If the step has defined buttons or pulls from the database, we MUST validate
        boolean isValid = false;
        if (currentStep.isDynamicSource()) {
            // Check against DB services
            isValid = businessServiceRepository.findByOwner(owner).stream()
                    .anyMatch(s -> s.getName().equalsIgnoreCase(answer));
            
            // FALLBACK: If not in DB, check against static options
            if (!isValid) {
                isValid = currentStep.getOptions().stream()
                        .anyMatch(opt -> opt.equalsIgnoreCase(answer));
            }
        } else if (!currentStep.getOptions().isEmpty()) {
            // Check against hardcoded button options only
            isValid = currentStep.getOptions().stream()
                    .anyMatch(opt -> opt.equalsIgnoreCase(answer));
        } else {
            // No options to validate against (free text)
            isValid = true;
        }

        if (!isValid) {
            whatsappClient.sendMessage(contact.getWaId(),
                    "❌ Invalid selection. Please choose an option from the list below:",
                    config.getAccessToken(), config.getPhoneNumberId());
            sendStep(state, blueprint, contact, owner); // Resend the options
            return; // Do not advance
        }

        // ── Case 4: General advancement ────────────────────────────────────
        saveAnswer(state, currentStep.getDataKey(), answer);
        state.setCurrentStep(stepIdx + 1);
        saveAndCheckCompletion(state, contact, owner, blueprint);
    }

    private void saveAndCheckCompletion(ConversationState state, Contact contact, User owner, FlowBlueprint blueprint) {
        if (state.getCurrentStep() >= blueprint.getSteps().size()) {
            stateRepository.delete(state);
            completeFlow(state, contact, owner, blueprint.getFlowType());
        } else {
            stateRepository.save(state);
            sendStep(state, blueprint, contact, owner);
        }
    }

    private String serialize(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Send the question for the current step
    // ════════════════════════════════════════════════════════════════════════

    private void sendStep(ConversationState state, FlowBlueprint blueprint, Contact contact, User owner) {
        List<FlowStep> steps = blueprint.getSteps();
        int idx = state.getCurrentStep();
        if (idx >= steps.size()) return;

        FlowStep step = steps.get(idx);
        WhatsAppConfig config = configRepository.findByUserId(owner.getId())
                .orElseThrow(() -> new RuntimeException("WhatsApp config not found"));

        if (step.isDynamicSource()) {
            handleDynamicServiceList(state, contact, owner, 0);
            return;
        }

        if (step.isUsesButtons() && !step.getOptions().isEmpty()) {
            // Send as interactive reply buttons (max 3)
            sendInteractiveQuestion(contact, config, step.getQuestion(), step.getOptions());
        } else {
            // Send as plain text question
            whatsappClient.sendMessage(contact.getWaId(), step.getQuestion(),
                    config.getAccessToken(), config.getPhoneNumberId());
        }
    }

    private void handleDynamicServiceList(ConversationState state, Contact contact, User owner, int pageIndex) {
        WhatsAppConfig config = configRepository.findByUserId(owner.getId()).get();
        String subCategory = owner.getBusinessSubType();
        
        // Fetch max 8 services to leave room for "Other" and "Next" (Limit 10 rows total)
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(pageIndex, 8);
        org.springframework.data.domain.Page<BusinessService> servicePage = businessServiceRepository.findByOwner(owner, pageable);

        // FALLBACK: If database is empty on the first page, use static JSON options instead
        if (pageIndex == 0 && servicePage.isEmpty()) {
            FlowBlueprint blueprint = templateEngine.getBlueprint(subCategory);
            FlowStep step = blueprint.getSteps().get(state.getCurrentStep());
            if (!step.getOptions().isEmpty()) {
                log.info("[Flow] DB Empty for {}, falling back to static options", subCategory);
                sendInteractiveQuestion(contact, config, step.getQuestion(), step.getOptions());
                return;
            }
        }

        List<com.chatcrmlite.backend.dto.MenuDto.MenuRowDto> rows = new ArrayList<>();
        
        // 1. Add Services
        for (BusinessService srv : servicePage.getContent()) {
            String title = srv.getName();
            if (title.length() > 24) title = title.substring(0, 24);
            rows.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                    .id("srv_" + srv.getId())
                    .title(title)
                    .build());
        }

        // 2. Add "Other" Option (Always present)
        rows.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                .id("flow_other")
                .title("\u270D\uFE0F Not in list / Other")
                .description("Tell us exactly what you need")
                .build());

        // 3. Add "Next" if more exist
        if (servicePage.hasNext()) {
            rows.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                    .id("flow_page_" + (pageIndex + 1))
                    .title("Next \u27A1\uFE0F")
                    .description("View more options")
                    .build());
        } else if (pageIndex > 0) {
            rows.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                    .id("flow_page_0")
                    .title("\u2B05\uFE0F Back to Start")
                    .build());
        }

        com.chatcrmlite.backend.dto.MenuDto menu = com.chatcrmlite.backend.dto.MenuDto.builder()
                .type("list")
                .title(templateEngine.getServicesLabel(subCategory))
                .bodyText("Please choose a service from the list below or click 'Other' to type your request:")
                .button("View Options")
                .sections(List.of(com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto.builder().rows(rows).build()))
                .build();

        whatsappClient.sendInteractiveMenu(contact.getWaId(), menu, config.getAccessToken(), config.getPhoneNumberId());
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Send an interactive reply-button question
    // ════════════════════════════════════════════════════════════════════════

    private void sendInteractiveQuestion(Contact contact, WhatsAppConfig config, String question, List<String> options) {
        // Build a simple button-format MenuDto on-the-fly
        com.chatcrmlite.backend.dto.MenuDto menu = com.chatcrmlite.backend.dto.MenuDto.builder()
                .type("button")
                .bodyText(question) // Use bodyText for longer questions (1024 limit) instead of title (60 limit)
                .sections(List.of(
                    com.chatcrmlite.backend.dto.MenuDto.MenuSectionDto.builder()
                        .rows(buildRows(options))
                        .build()
                ))
                .build();

        try {
            whatsappClient.sendInteractiveMenu(
                    contact.getWaId(), menu,
                    config.getAccessToken(), config.getPhoneNumberId()
            );
        } catch (Exception e) {
            // Fallback to plain text if buttons fail (e.g. > 3 options sent as list)
            StringBuilder sb = new StringBuilder(question).append("\n\n");
            for (int i = 0; i < options.size(); i++) {
                sb.append(i + 1).append(". ").append(options.get(i)).append("\n");
            }
            whatsappClient.sendMessage(contact.getWaId(), sb.toString(),
                    config.getAccessToken(), config.getPhoneNumberId());
        }
    }

    private List<com.chatcrmlite.backend.dto.MenuDto.MenuRowDto> buildRows(List<String> options) {
        List<com.chatcrmlite.backend.dto.MenuDto.MenuRowDto> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(options.size(), 3); i++) {
            rows.add(com.chatcrmlite.backend.dto.MenuDto.MenuRowDto.builder()
                    .id("opt_" + (i + 1))
                    .title(options.get(i))
                    .build());
        }
        return rows;
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Flow Completion Actions (CRM side effects)
    // ════════════════════════════════════════════════════════════════════════

    private void completeFlow(ConversationState state, Contact contact, User owner, FlowType flowType) {
        Map<String, String> data = parseData(state.getCollectedData());
        WhatsAppConfig config = configRepository.findByUserId(owner.getId()).orElse(null);

        // Resolve active lead (non-closed) or create new one
        List<Lead.LeadStatus> closedStatuses = List.of(
                Lead.LeadStatus.CLOSED_WON, Lead.LeadStatus.CLOSED_LOST);
        Lead lead = leadRepository
                .findTopByContactAndStatusNotInOrderByCreatedAtDesc(contact, closedStatuses)
                .orElseGet(() -> {
                    Lead newLead = Lead.builder()
                            .contact(contact).owner(owner).status(Lead.LeadStatus.NEW).build();
                    return leadRepository.save(newLead);
                });

        switch (flowType) {

            case APPOINTMENT -> {
                lead.setStatus(Lead.LeadStatus.BOOKED);
                lead.setLastActivity(LocalDateTime.now());
                leadRepository.save(lead);

                // Pick best title from collected data keys
                String title = data.getOrDefault("treatment",
                               data.getOrDefault("service",
                               data.getOrDefault("concern",
                               data.getOrDefault("shoot_type",
                               data.getOrDefault("goal", "Appointment")))));

                // Parse preferred date — placeholder +1 day if not parseable
                LocalDateTime apptTime = LocalDateTime.now().plusDays(1);

                // Store in appointments table — collectedData has full JSON
                appointmentService.bookFromFlow(lead, owner, title, data, apptTime);

                sendConfirmation(contact, config,
                    "✅ Your appointment has been booked! Our team will confirm the exact time shortly.");
            }

            case BOOKING -> {
                lead.setStatus(Lead.LeadStatus.BOOKED);
                lead.setLastActivity(LocalDateTime.now());
                leadRepository.save(lead);

                String service = data.getOrDefault("service",
                                 data.getOrDefault("class",
                                 data.getOrDefault("session", "Booking")));
                String slot = data.getOrDefault("date_time",
                              data.getOrDefault("slot", null));

                // Store in bookings table — collectedData has full JSON
                bookingService.bookFromFlow(lead, owner, service, slot, data);

                sendConfirmation(contact, config,
                    "🎉 Booking Confirmed! We've reserved your slot. See you soon!");
            }

            case ENQUIRY -> {
                lead.setStatus(Lead.LeadStatus.FOLLOW_UP);
                lead.setLastActivity(LocalDateTime.now());
                leadRepository.save(lead);

                // Build readable message from flow data
                StringBuilder msg = new StringBuilder();
                data.forEach((k, v) -> msg.append(capitalize(k)).append(": ").append(v).append("\n"));
                leadService.appendEnquiryToLead(lead, msg.toString().trim(), "FLOW", "WhatsApp Enquiry Flow");

                sendConfirmation(contact, config,
                    "📋 Thanks! We've received your enquiry. Our team will reach out shortly.");
            }

            case LEAD_CAPTURE -> {
                lead.setStatus(Lead.LeadStatus.INTERESTED);
                lead.setLastActivity(LocalDateTime.now());
                leadRepository.save(lead);

                StringBuilder msg = new StringBuilder();
                data.forEach((k, v) -> msg.append(capitalize(k)).append(": ").append(v).append("\n"));
                leadService.appendEnquiryToLead(lead, msg.toString().trim(), "FLOW", "WhatsApp Lead Capture Flow");

                sendConfirmation(contact, config,
                    "🤝 Great! We've noted your requirement. Our expert will contact you within 24 hours.");
            }
        }
    }

    private void sendConfirmation(Contact contact, WhatsAppConfig config, String message) {
        if (config == null) return;
        try {
            whatsappClient.sendMessage(contact.getWaId(), message,
                    config.getAccessToken(), config.getPhoneNumberId());
        } catch (Exception e) {
            log.warn("[Flow] Could not send confirmation to {}: {}", contact.getWaId(), e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Internal: Data helpers
    // ════════════════════════════════════════════════════════════════════════

    private void saveAnswer(ConversationState state, String key, String value) {
        Map<String, String> data = parseData(state.getCollectedData());
        data.put(key, value);
        try {
            state.setCollectedData(objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            log.error("[Flow] Failed to serialize data", e);
        }
    }

    private Map<String, String> parseData(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1).replace('_', ' ');
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Scheduled Cleanup: Remove stale flows older than 24 hours
    // ════════════════════════════════════════════════════════════════════════

    @Scheduled(fixedDelay = 3_600_000) // every 1 hour
    @Transactional
    public void cleanupStaleFlows() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        stateRepository.deleteStaleFlows(cutoff);
        log.info("[Flow] Stale flow cleanup completed (cutoff={})", cutoff);
    }
}
