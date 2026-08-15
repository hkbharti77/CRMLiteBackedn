package com.chatcrmlite.backend.services.whatsapp.flows;

import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppConfig;
import com.chatcrmlite.backend.models.flows.*;
import com.chatcrmlite.backend.repositories.WhatsAppConfigRepository;
import com.chatcrmlite.backend.repositories.flows.FlowPublishJobRepository;
import com.chatcrmlite.backend.repositories.flows.FlowRevisionRepository;
import com.chatcrmlite.backend.repositories.flows.WhatsAppFlowRepository;
import com.chatcrmlite.backend.clients.MetaFlowClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import com.chatcrmlite.backend.services.FlowConfigService;

@Slf4j
@Service
public class WhatsAppFlowService {

    private final WhatsAppFlowRepository flowRepository;
    private final FlowRevisionRepository revisionRepository;
    private final FlowPublishJobRepository jobRepository;
    private final WhatsAppConfigRepository whatsappConfigRepository;
    private final FlowSchemaBuilder schemaBuilder;
    private final FlowValidator flowValidator;
    private final WhatsAppFlowAuditService auditService;
    private final MetaFlowClient metaFlowClient;
    private final FlowConfigService flowConfigService;

    public WhatsAppFlowService(WhatsAppFlowRepository flowRepository,
                               FlowRevisionRepository revisionRepository,
                               FlowPublishJobRepository jobRepository,
                               WhatsAppConfigRepository whatsappConfigRepository,
                               FlowSchemaBuilder schemaBuilder,
                               FlowValidator flowValidator,
                               WhatsAppFlowAuditService auditService,
                               MetaFlowClient metaFlowClient,
                               FlowConfigService flowConfigService) {
        this.flowRepository = flowRepository;
        this.revisionRepository = revisionRepository;
        this.jobRepository = jobRepository;
        this.whatsappConfigRepository = whatsappConfigRepository;
        this.schemaBuilder = schemaBuilder;
        this.flowValidator = flowValidator;
        this.auditService = auditService;
        this.metaFlowClient = metaFlowClient;
        this.flowConfigService = flowConfigService;
    }

    private UUID getTenantId(User user) {
        if (user == null || user.getTenant() == null) {
            throw new IllegalStateException("Authenticated user is not linked to a valid tenant");
        }
        return user.getTenant().getId();
    }

    @Transactional(readOnly = true)
    public List<WhatsAppFlow> listTenantFlows(User user) {
        UUID tenantId = getTenantId(user);
        return flowRepository.findAllActiveByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public WhatsAppFlow getFlow(UUID flowId, User user) {
        UUID tenantId = getTenantId(user);
        return flowRepository.findByIdAndTenantId(flowId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Flow not found or access denied"));
    }

    @Transactional
    public WhatsAppFlow saveDraft(String name, FlowCategory category, String fieldsConfigJson, String confirmationMessage, User user) {
        UUID tenantId = getTenantId(user);
        flowValidator.validateFieldsConfig(name, fieldsConfigJson);

        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("Tenant WhatsApp account is not configured"));

        String wabaId = config.getWabaId();
        String phoneNumberId = config.getPhoneNumberId();
        if (wabaId == null || wabaId.isBlank()) {
            throw new IllegalStateException("WABA ID is not configured on your WhatsApp account");
        }

        String compiledFlowJson = schemaBuilder.buildMetaFlowJson(name, "Please complete the form below:", fieldsConfigJson);
        flowValidator.validateCompiledMetaJson(compiledFlowJson);

        WhatsAppFlow flow = WhatsAppFlow.builder()
                .name(name.trim())
                .category(category != null ? category : FlowCategory.LEAD_GENERATION)
                .status(FlowLifecycleStatus.DRAFT)
                .wabaId(wabaId)
                .phoneNumberId(phoneNumberId != null ? phoneNumberId : "")
                .build();
        flow.setTenant(user.getTenant());
        flow = flowRepository.save(flow);

        FlowRevision revision = FlowRevision.builder()
                .flow(flow)
                .versionNumber(1)
                .fieldsConfigJson(fieldsConfigJson)
                .flowJson(compiledFlowJson)
                .confirmationMessage(confirmationMessage != null ? confirmationMessage.trim() : "Thank you! We have received your submission.")
                .status(RevisionStatus.DRAFT)
                .createdBy(user)
                .build();
        revision.setTenant(user.getTenant());
        revisionRepository.save(revision);

        flow.getRevisions().add(revision);
        auditService.logAction(flow.getId(), revision.getId(), user, FlowAuditAction.FLOW_CREATED, null, "DRAFT", "{\"name\":\"" + flow.getName() + "\"}");
        log.info("✅ [WhatsAppFlowService] Created new Flow Draft '{}' (v1) for tenant {}", flow.getName(), tenantId);
        return flow;
    }

    @Transactional
    public WhatsAppFlow updateDraftRevision(UUID flowId, String name, FlowCategory category, String fieldsConfigJson, String confirmationMessage, User user) {
        UUID tenantId = getTenantId(user);
        WhatsAppFlow flow = flowRepository.findByIdAndTenantId(flowId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Flow not found or access denied"));

        flowValidator.validateFieldsConfig(name, fieldsConfigJson);
        String compiledFlowJson = schemaBuilder.buildMetaFlowJson(name, "Please complete the form below:", fieldsConfigJson);
        flowValidator.validateCompiledMetaJson(compiledFlowJson);

        flow.setName(name.trim());
        if (category != null) flow.setCategory(category);

        int maxVersion = revisionRepository.findMaxVersionByFlowId(flow.getId());
        Optional<FlowRevision> latestRevisionOpt = revisionRepository.findByFlowIdAndVersionNumber(flow.getId(), maxVersion);

        FlowRevision revision;
        if (latestRevisionOpt.isPresent() && latestRevisionOpt.get().getStatus() == RevisionStatus.DRAFT) {
            // Update existing draft revision
            revision = latestRevisionOpt.get();
            revision.setFieldsConfigJson(fieldsConfigJson);
            revision.setFlowJson(compiledFlowJson);
            if (confirmationMessage != null) revision.setConfirmationMessage(confirmationMessage.trim());
        } else {
            // Create a new draft revision incrementing version
            revision = FlowRevision.builder()
                    .flow(flow)
                    .versionNumber(maxVersion + 1)
                    .fieldsConfigJson(fieldsConfigJson)
                    .flowJson(compiledFlowJson)
                    .confirmationMessage(confirmationMessage != null ? confirmationMessage.trim() : "Thank you! We have received your submission.")
                    .status(RevisionStatus.DRAFT)
                    .createdBy(user)
                    .build();
            revision.setTenant(user.getTenant());
        }
        revisionRepository.save(revision);
        flowRepository.save(flow);

        auditService.logAction(flow.getId(), revision.getId(), user, FlowAuditAction.REVISION_CREATED, null, "DRAFT", "{\"version\":" + revision.getVersionNumber() + "}");
        return flow;
    }

    @Transactional
    public FlowPublishJob queuePublishFlow(UUID flowId, User user) {
        UUID tenantId = getTenantId(user);
        WhatsAppFlow flow = flowRepository.findByIdAndTenantId(flowId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Flow not found or access denied"));

        int maxVersion = revisionRepository.findMaxVersionByFlowId(flow.getId());
        FlowRevision revisionToPublish = revisionRepository.findByFlowIdAndVersionNumber(flow.getId(), maxVersion)
                .orElseThrow(() -> new IllegalStateException("No revision available to publish"));

        flow.setStatus(FlowLifecycleStatus.PUBLISHING);
        flowRepository.save(flow);

        revisionToPublish.setStatus(RevisionStatus.PUBLISHING);
        revisionRepository.save(revisionToPublish);

        FlowPublishJob job = FlowPublishJob.builder()
                .flow(flow)
                .revision(revisionToPublish)
                .status(PublishJobStatus.PENDING)
                .attempts(0)
                .maxAttempts(3)
                .build();
        job.setTenant(user.getTenant());
        job = jobRepository.save(job);

        auditService.logAction(flow.getId(), revisionToPublish.getId(), user, FlowAuditAction.FLOW_PUBLISH_QUEUED, "DRAFT", "PUBLISHING", "{\"jobId\":\"" + job.getId() + "\"}");
        log.info("🚀 [WhatsAppFlowService] Queued FlowPublishJob {} for Flow '{}' (v{})", job.getId(), flow.getName(), revisionToPublish.getVersionNumber());
        return job;
    }

    @Transactional
    public WhatsAppFlow duplicateFlow(UUID flowId, User user) {
        UUID tenantId = getTenantId(user);
        WhatsAppFlow sourceFlow = flowRepository.findByIdAndTenantId(flowId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Flow not found or access denied"));

        int maxVersion = revisionRepository.findMaxVersionByFlowId(sourceFlow.getId());
        FlowRevision sourceRevision = revisionRepository.findByFlowIdAndVersionNumber(sourceFlow.getId(), maxVersion)
                .orElseThrow(() -> new IllegalStateException("Source flow has no revisions to duplicate"));

        String copyName = sourceFlow.getName() + " (Copy)";
        if (copyName.length() > 60) copyName = copyName.substring(0, 60);

        WhatsAppFlow clonedFlow = WhatsAppFlow.builder()
                .name(copyName)
                .category(sourceFlow.getCategory())
                .status(FlowLifecycleStatus.DRAFT)
                .wabaId(sourceFlow.getWabaId())
                .phoneNumberId(sourceFlow.getPhoneNumberId())
                .build();
        clonedFlow.setTenant(user.getTenant());
        clonedFlow = flowRepository.save(clonedFlow);

        FlowRevision clonedRevision = FlowRevision.builder()
                .flow(clonedFlow)
                .versionNumber(1)
                .fieldsConfigJson(sourceRevision.getFieldsConfigJson())
                .flowJson(sourceRevision.getFlowJson())
                .confirmationMessage(sourceRevision.getConfirmationMessage())
                .status(RevisionStatus.DRAFT)
                .createdBy(user)
                .build();
        clonedRevision.setTenant(user.getTenant());
        revisionRepository.save(clonedRevision);

        auditService.logAction(clonedFlow.getId(), clonedRevision.getId(), user, FlowAuditAction.FLOW_DUPLICATED, null, "DRAFT", "{\"sourceFlowId\":\"" + sourceFlow.getId() + "\"}");
        log.info("📋 [WhatsAppFlowService] Duplicated Flow '{}' -> '{}' for tenant {}", sourceFlow.getName(), clonedFlow.getName(), tenantId);
        return clonedFlow;
    }

    @Transactional
    public void archiveFlow(UUID flowId, User user) {
        UUID tenantId = getTenantId(user);
        WhatsAppFlow flow = flowRepository.findByIdAndTenantId(flowId, tenantId)
                .orElseThrow(() -> new NoSuchElementException("Flow not found or access denied"));

        flow.setStatus(FlowLifecycleStatus.ARCHIVED);
        flowRepository.save(flow);

        // Also delete / deprecate on Meta Cloud API if metaFlowId exists
        if (flow.getMetaFlowId() != null && !flow.getMetaFlowId().isBlank()) {
            try {
                WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId).orElse(null);
                if (config != null && config.getAccessToken() != null && !config.getAccessToken().isBlank()) {
                    metaFlowClient.deleteFlow(flow.getMetaFlowId(), config.getAccessToken());
                    log.info("🗑️ [WhatsAppFlowService] Deleted/Deprecated flow '{}' ({}) on Meta Cloud for tenant {}", flow.getName(), flow.getMetaFlowId(), tenantId);
                }
            } catch (Exception e) {
                log.warn("⚠️ [WhatsAppFlowService] Could not delete flow {} from Meta: {}", flow.getMetaFlowId(), e.getMessage());
            }
        }

        auditService.logAction(flow.getId(), null, user, FlowAuditAction.FLOW_ARCHIVED, "PUBLISHED", "ARCHIVED", null);
        log.info("📦 [WhatsAppFlowService] Archived Flow '{}' for tenant {}", flow.getName(), tenantId);
    }

    /**
     * Synchronizes and imports all published/draft flows from Meta Graph API for this tenant.
     */
    @Transactional
    public Map<String, Object> syncFlowsFromMeta(User user) {
        UUID tenantId = getTenantId(user);
        WhatsAppConfig config = whatsappConfigRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException("WhatsApp credentials not configured for tenant"));

        if (config.getWabaId() == null || config.getWabaId().isBlank() ||
            config.getAccessToken() == null || config.getAccessToken().isBlank()) {
            throw new IllegalStateException("WABA ID or Access Token is missing in WhatsApp settings.");
        }

        JsonNode metaResponse = metaFlowClient.fetchWabaFlows(config.getWabaId(), config.getAccessToken());
        JsonNode dataNode = metaResponse != null ? metaResponse.path("data") : null;

        int importedCount = 0;
        int updatedCount = 0;

        if (dataNode != null && dataNode.isArray()) {
            for (JsonNode item : dataNode) {
                String metaFlowId = item.path("id").asText();
                if (metaFlowId == null || metaFlowId.isBlank()) continue;

                String name = item.path("name").asText("Unnamed Meta Flow");
                String metaStatus = item.path("status").asText("PUBLISHED").toUpperCase();

                FlowLifecycleStatus status = "PUBLISHED".equals(metaStatus) ? FlowLifecycleStatus.PUBLISHED
                        : "DRAFT".equals(metaStatus) ? FlowLifecycleStatus.DRAFT
                        : "DEPRECATED".equals(metaStatus) ? FlowLifecycleStatus.ARCHIVED
                        : FlowLifecycleStatus.PUBLISHED;

                FlowCategory category = FlowCategory.OTHER;
                JsonNode categoriesNode = item.path("categories");
                if (categoriesNode.isArray() && categoriesNode.size() > 0) {
                    try {
                        category = FlowCategory.valueOf(categoriesNode.get(0).asText());
                    } catch (Exception ignored) {}
                }

                WhatsAppFlow existing = flowRepository.findByMetaFlowIdAndTenantId(metaFlowId, tenantId).orElse(null);
                if (existing != null) {
                    existing.setStatus(status);
                    existing.setName(name);
                    existing.setLastSyncError(null);
                    flowRepository.save(existing);
                    updatedCount++;
                } else {
                    WhatsAppFlow newFlow = WhatsAppFlow.builder()
                            .name(name)
                            .category(category)
                            .metaFlowId(metaFlowId)
                            .status(status)
                            .build();
                    newFlow.setTenant(user.getTenant());
                    newFlow = flowRepository.save(newFlow);

                    FlowRevision revision = FlowRevision.builder()
                            .flow(newFlow)
                            .versionNumber(1)
                            .fieldsConfigJson("[]")
                            .flowJson("{}")
                            .confirmationMessage("Thank you! Your submission has been received.")
                            .status(RevisionStatus.PUBLISHED)
                            .createdBy(user)
                            .build();
                    revision.setTenant(user.getTenant());
                    revision = revisionRepository.save(revision);

                    newFlow.setPublishedRevision(revision);
                    flowRepository.save(newFlow);
                    importedCount++;
                }
            }
        }

        log.info("🔄 [WhatsAppFlowService] Synced flows from Meta for tenant {}: imported={}, updated={}", tenantId, importedCount, updatedCount);
        return Map.of(
                "success", true,
                "imported", importedCount,
                "updated", updatedCount,
                "total", (dataNode != null ? dataNode.size() : 0),
                "message", String.format("Successfully synced with Meta: %d new imported, %d updated.", importedCount, updatedCount)
        );
    }

    public List<Map<String, Object>> getPrebuiltTemplates(User user) {
        List<Map<String, Object>> templates = new ArrayList<>();

        // 1. Dynamic CRM Appointment Booking
        List<Map<String, Object>> apptFields = buildDynamicFields(user, "appointment", List.of(
                Map.of("name", "name", "label", "Full Name", "type", "TEXT", "required", true),
                Map.of("name", "phone", "label", "Contact Number", "type", "PHONE", "required", true),
                Map.of("name", "email", "label", "Email Address", "type", "EMAIL", "required", false),
                Map.of("name", "service_category", "label", "Select Service / Doctor", "type", "SELECT", "required", true, "options", List.of("General Consultation", "Specialist Appointment", "Checkup & Followup", "Online Video Consult")),
                Map.of("name", "preferred_date", "label", "Preferred Date", "type", "DATE", "required", true),
                Map.of("name", "time_slot", "label", "Preferred Time Slot", "type", "SELECT", "required", true, "options", List.of("10:00 AM - 11:30 AM", "12:00 PM - 01:30 PM", "03:00 PM - 04:30 PM", "05:00 PM - 06:30 PM", "07:00 PM - 08:30 PM")),
                Map.of("name", "specific_requirement", "label", "Appointment Notes / Symptoms", "type", "TEXTAREA", "required", false)
        ));
        templates.add(Map.of(
                "id", "crm_appointment",
                "name", "📅 Appointment Booking",
                "category", "APPOINTMENT_BOOKING",
                "description", "Schedule appointments with date, time slot, and consultation details.",
                "fields", apptFields,
                "confirmationMessage", "Thank you! Your appointment request has been received. Our team will contact you shortly with the final slot confirmation."
        ));

        // 2. Dynamic CRM Reservation & Booking
        List<Map<String, Object>> bookingFields = buildDynamicFields(user, "booking", List.of(
                Map.of("name", "name", "label", "Full Name", "type", "TEXT", "required", true),
                Map.of("name", "phone", "label", "Contact Number", "type", "PHONE", "required", true),
                Map.of("name", "email", "label", "Email Address", "type", "EMAIL", "required", false),
                Map.of("name", "service_category", "label", "Package / Service Type", "type", "SELECT", "required", true, "options", List.of("Premium Package", "Standard Package", "VIP Booking", "Trial Session")),
                Map.of("name", "preferred_date", "label", "Booking Date", "type", "DATE", "required", true),
                Map.of("name", "time_slot", "label", "Time Slot", "type", "SELECT", "required", true, "options", List.of("Morning (10:00 AM - 01:00 PM)", "Afternoon (02:00 PM - 05:00 PM)", "Evening (06:00 PM - 09:00 PM)")),
                Map.of("name", "specific_requirement", "label", "Special Requests / Instructions", "type", "TEXTAREA", "required", false)
        ));
        templates.add(Map.of(
                "id", "crm_booking",
                "name", "🔖 Service & Slot Reservation",
                "category", "APPOINTMENT_BOOKING",
                "description", "Reserve slots, packages, test drives, and class sessions.",
                "fields", bookingFields,
                "confirmationMessage", "Thank you for booking with us! Your reservation is recorded and our staff will verify your booking."
        ));

        // 3. Dynamic CRM Lead Generation & Quote
        List<Map<String, Object>> leadFields = buildDynamicFields(user, "lead", List.of(
                Map.of("name", "name", "label", "Full Name", "type", "TEXT", "required", true),
                Map.of("name", "phone", "label", "Contact Phone Number", "type", "PHONE", "required", true),
                Map.of("name", "email", "label", "Work / Personal Email", "type", "EMAIL", "required", true),
                Map.of("name", "service_category", "label", "Product / Service Interested", "type", "SELECT", "required", true, "options", List.of("Enterprise Solution", "Professional Plan", "Starter Package", "Custom Consultation")),
                Map.of("name", "budget", "label", "Estimated Budget", "type", "SELECT", "required", false, "options", List.of("Under ₹50,000", "₹50,000 - ₹2,00,000", "₹2,00,000 - ₹10,00,000", "₹10,00,000+")),
                Map.of("name", "city", "label", "City / Location", "type", "TEXT", "required", true),
                Map.of("name", "urgency", "label", "Timeline / Urgency", "type", "RADIO", "required", false, "options", List.of("Immediate (Within 48 hours)", "Within 1-2 weeks", "This Month", "Exploring Options")),
                Map.of("name", "specific_requirement", "label", "Project / Inquiry Details", "type", "TEXTAREA", "required", false)
        ));
        templates.add(Map.of(
                "id", "crm_lead",
                "name", "🎯 Sales Inquiry & Lead Form",
                "category", "LEAD_GENERATION",
                "description", "Qualify customer needs, product interest, budget, and location.",
                "fields", leadFields,
                "confirmationMessage", "Thank you! Our sales specialist will review your details and send a personalized proposal to your email."
        ));

        // 4. Dynamic CRM Customer Support Ticket
        List<Map<String, Object>> supportFields = buildDynamicFields(user, "support", List.of(
                Map.of("name", "name", "label", "Full Name", "type", "TEXT", "required", true),
                Map.of("name", "phone", "label", "Contact Number", "type", "PHONE", "required", true),
                Map.of("name", "email", "label", "Email Address", "type", "EMAIL", "required", true),
                Map.of("name", "service_category", "label", "Issue Category", "type", "SELECT", "required", true, "options", List.of("Technical Support", "Billing & Invoices", "Account Access", "General Question", "Feedback")),
                Map.of("name", "specific_requirement", "label", "Detailed Issue Description", "type", "TEXTAREA", "required", true)
        ));
        templates.add(Map.of(
                "id", "crm_support",
                "name", "🎫 Customer Support Ticket",
                "category", "CUSTOMER_SUPPORT",
                "description", "Direct ticket submission to your CRM Helpdesk with issue details.",
                "fields", supportFields,
                "confirmationMessage", "Your support ticket has been created! Our support team will investigate and follow up with you promptly."
        ));

        // 5. Dynamic Customer Feedback & Rating
        List<Map<String, Object>> feedbackFields = buildDynamicFields(user, "feedback", List.of(
                Map.of("name", "name", "label", "Your Full Name", "type", "TEXT", "required", true),
                Map.of("name", "phone", "label", "Contact Number", "type", "PHONE", "required", false),
                Map.of("name", "service_category", "label", "How was your experience with us?", "type", "SELECT", "required", true, "options", List.of("⭐⭐⭐⭐⭐ Excellent", "⭐⭐⭐⭐ Very Good", "⭐⭐⭐ Average", "⭐⭐ Needs Improvement", "⭐ Poor")),
                Map.of("name", "specific_requirement", "label", "What can we do to improve?", "type", "TEXTAREA", "required", false)
        ));
        templates.add(Map.of(
                "id", "feedback_survey",
                "name", "⭐ Customer Feedback & Rating",
                "category", "SURVEY",
                "description", "Collect customer reviews, satisfaction rating, and comments.",
                "fields", feedbackFields,
                "confirmationMessage", "Thank you for your valuable feedback! Your response helps us serve you better every day."
        ));

        return templates;
    }

    private List<Map<String, Object>> buildDynamicFields(User user, String flowType, List<Map<String, Object>> fallback) {
        if (user == null || flowConfigService == null) return fallback;
        try {
            List<com.chatcrmlite.backend.dto.flow.FlowFieldConfig> configs = flowConfigService.getConfigurableFields(user, flowType);
            if (configs == null || configs.isEmpty()) return fallback;

            List<Map<String, Object>> dynamicFields = new ArrayList<>();
            for (com.chatcrmlite.backend.dto.flow.FlowFieldConfig fc : configs) {
                if (fc.isEnabled()) {
                    String type = "TEXT";
                    if (fc.getFieldType() != null) {
                        String t = fc.getFieldType().toUpperCase();
                        if (t.contains("DATE")) type = "DATE";
                        else if (t.contains("SELECT") || t.contains("DROPDOWN") || t.contains("RADIO") || t.contains("CHOICE")) type = "SELECT";
                        else if (t.contains("TEXTAREA") || t.contains("NOTE") || t.contains("DESC")) type = "TEXTAREA";
                        else if (t.contains("PHONE") || t.contains("TEL")) type = "PHONE";
                        else if (t.contains("EMAIL") || t.contains("MAIL")) type = "EMAIL";
                        else if (t.contains("NUMBER") || t.contains("INT") || t.contains("AMOUNT") || t.contains("PRICE") || t.contains("BUDGET")) type = "NUMBER";
                    }
                    Map<String, Object> fieldMap = new HashMap<>();
                    fieldMap.put("name", fc.getKey());
                    fieldMap.put("label", fc.getLabel() != null && !fc.getLabel().isBlank() ? fc.getLabel() : fc.getKey());
                    fieldMap.put("type", type);
                    fieldMap.put("required", fc.isRequired());
                    if (fc.getOptions() != null && !fc.getOptions().isEmpty()) {
                        fieldMap.put("options", fc.getOptions());
                    }
                    dynamicFields.add(fieldMap);
                }
            }
            return dynamicFields.isEmpty() ? fallback : dynamicFields;
        } catch (Exception e) {
            log.warn("⚠️ [WhatsAppFlowService] Failed to load dynamic fields for flowType {}: {}", flowType, e.getMessage());
            return fallback;
        }
    }
}
