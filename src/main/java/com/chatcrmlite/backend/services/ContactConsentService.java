package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.dto.*;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.ContactConsentAuditLog;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.journey.ContactChannelPreference;
import com.chatcrmlite.backend.repositories.ContactConsentAuditLogRepository;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.TenantRepository;
import com.chatcrmlite.backend.repositories.journey.ContactChannelPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactConsentService {

    private final ContactRepository contactRepository;
    private final ContactChannelPreferenceRepository preferenceRepository;
    private final ContactConsentAuditLogRepository auditLogRepository;
    private final TenantRepository tenantRepository;

    @Transactional(readOnly = true)
    public ContactConsentDTO getConsentDTO(UUID tenantId, UUID contactId) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found with ID: " + contactId));

        if (contact.getTenant() != null && !contact.getTenant().getId().equals(tenantId)) {
            throw new RuntimeException("Access denied: Contact does not belong to tenant");
        }

        ContactChannelPreference pref = preferenceRepository.findByBusinessIdAndContactId(tenantId.toString(), contactId)
                .orElse(null);

        List<ContactConsentAuditLog> auditLogs = auditLogRepository.findByTenantIdAndContactIdOrderByTimestampDesc(tenantId, contactId);

        return buildConsentDTO(contact, pref, auditLogs);
    }

    @Transactional
    public ContactConsentDTO updateConsent(UUID tenantId, UUID contactId, UpdateConsentRequestDTO request, String performedBy, String ipAddress, String userAgent) {
        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new RuntimeException("Contact not found with ID: " + contactId));

        if (contact.getTenant() != null && !contact.getTenant().getId().equals(tenantId)) {
            throw new RuntimeException("Access denied: Contact does not belong to tenant");
        }

        ContactChannelPreference pref = preferenceRepository.findByBusinessIdAndContactId(tenantId.toString(), contactId)
                .orElseGet(() -> ContactChannelPreference.builder()
                        .businessId(tenantId.toString())
                        .contactId(contactId)
                        .emailConsentStatus("UNKNOWN")
                        .whatsappConsentStatus("UNKNOWN")
                        .smsConsentStatus("UNKNOWN")
                        .isGloballySuppressed(false)
                        .build());

        String channel = request.getChannel() != null ? request.getChannel().toUpperCase().trim() : "ALL";
        String newStatus = normalizeStatus(request.getStatus());
        String source = request.getSource() != null ? request.getSource().trim() : "ADMIN_MANUAL";
        String reason = request.getReason();

        if ("WHATSAPP".equals(channel) || "ALL".equals(channel)) {
            String prev = pref.getWhatsappConsentStatus();
            pref.setWhatsappConsentStatus(newStatus);
            recordAuditLog(tenantId, contactId, "WHATSAPP", prev, newStatus, source, reason, performedBy, ipAddress, userAgent);
        }

        if ("EMAIL".equals(channel) || "ALL".equals(channel)) {
            String prev = pref.getEmailConsentStatus();
            pref.setEmailConsentStatus(newStatus);
            recordAuditLog(tenantId, contactId, "EMAIL", prev, newStatus, source, reason, performedBy, ipAddress, userAgent);
        }

        if ("SMS".equals(channel) || "ALL".equals(channel)) {
            String prev = pref.getSmsConsentStatus();
            pref.setSmsConsentStatus(newStatus);
            recordAuditLog(tenantId, contactId, "SMS", prev, newStatus, source, reason, performedBy, ipAddress, userAgent);
        }

        if (request.getIsGloballySuppressed() != null) {
            Boolean prevSuppressed = pref.getIsGloballySuppressed();
            pref.setIsGloballySuppressed(request.getIsGloballySuppressed());
            recordAuditLog(tenantId, contactId, "GLOBAL",
                    Boolean.TRUE.equals(prevSuppressed) ? "OPTED_OUT" : "OPTED_IN",
                    Boolean.TRUE.equals(request.getIsGloballySuppressed()) ? "OPTED_OUT" : "OPTED_IN",
                    source, "Global suppression toggled", performedBy, ipAddress, userAgent);
        }

        pref.setUpdatedAt(ZonedDateTime.now());
        preferenceRepository.save(pref);

        // Synchronize with Contact marketingOptedOut field
        boolean isWaOptOut = "OPTED_OUT".equalsIgnoreCase(pref.getWhatsappConsentStatus());
        boolean isGloballyOptOut = Boolean.TRUE.equals(pref.getIsGloballySuppressed());

        if (isWaOptOut || isGloballyOptOut) {
            contact.setMarketingOptedOut(true);
            contact.setMarketingOptedOutAt(Instant.now());
            contact.setMarketingOptOutSource(source);
        } else if ("OPTED_IN".equalsIgnoreCase(pref.getWhatsappConsentStatus())) {
            contact.setMarketingOptedOut(false);
            contact.setMarketingOptedOutAt(null);
            contact.setMarketingOptOutSource(source);
        }
        contact.setMarketingPreferenceAt(Instant.now());
        contactRepository.save(contact);

        log.info("✅ [ContactConsentService] Updated consent for contactId={} channel={} newStatus={} source={}",
                contactId, channel, newStatus, source);

        List<ContactConsentAuditLog> auditLogs = auditLogRepository.findByTenantIdAndContactIdOrderByTimestampDesc(tenantId, contactId);
        return buildConsentDTO(contact, pref, auditLogs);
    }

    @Transactional
    public ContactConsentDTO recordPublicOptIn(PublicOptInRequestDTO request, String ipAddress, String userAgent) {
        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Phone number is required for public opt-in");
        }

        String rawPhone = request.getPhone().replaceAll("[^0-9]", "");
        if (rawPhone.isEmpty()) {
            throw new IllegalArgumentException("Invalid phone number format");
        }

        UUID tenantId = null;
        if (request.getBusinessId() != null && !request.getBusinessId().isBlank()) {
            try {
                tenantId = UUID.fromString(request.getBusinessId().trim());
            } catch (Exception ignored) {}
        }

        if (tenantId == null) {
            // Default to first tenant if unassigned
            tenantId = tenantRepository.findAll().stream().findFirst().map(Tenant::getId).orElse(null);
        }

        if (tenantId == null) {
            throw new IllegalStateException("No valid tenant found to process opt-in");
        }

        Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();

        // Find or create Contact
        Optional<Contact> optContact = contactRepository.findByTenantIdAndWaId(tenantId, rawPhone);
        Contact contact;
        if (optContact.isPresent()) {
            contact = optContact.get();
            if (request.getName() != null && !request.getName().isBlank()) {
                contact.setName(request.getName().trim());
            }
            if (request.getEmail() != null && !request.getEmail().isBlank()) {
                contact.setEmail(request.getEmail().trim());
            }
        } else {
            contact = Contact.builder()
                    .waId(rawPhone)
                    .name(request.getName() != null ? request.getName().trim() : "Web Lead " + rawPhone)
                    .email(request.getEmail() != null ? request.getEmail().trim() : null)
                    .source(request.getSource() != null ? request.getSource() : "WEB_WIDGET_OPTIN")
                    .build();
            contact.setTenant(tenant);
        }

        contact = contactRepository.save(contact);

        UpdateConsentRequestDTO updateReq = UpdateConsentRequestDTO.builder()
                .channel("ALL")
                .status("OPTED_IN")
                .source(request.getSource() != null ? request.getSource() : "WEB_WIDGET_OPTIN")
                .reason("Public form/widget explicit opt-in submission. URL: " + (request.getLandingPageUrl() != null ? request.getLandingPageUrl() : "N/A"))
                .isGloballySuppressed(false)
                .build();

        return updateConsent(tenantId, contact.getId(), updateReq, "PUBLIC_GUEST", ipAddress, userAgent);
    }

    @Transactional
    public void recordInboundWhatsAppOptIn(UUID tenantId, UUID contactId, String senderInfo) {
        if (tenantId == null || contactId == null) return;
        try {
            UpdateConsentRequestDTO updateReq = UpdateConsentRequestDTO.builder()
                    .channel("WHATSAPP")
                    .status("OPTED_IN")
                    .source("WHATSAPP_INBOUND_CHAT")
                    .reason("Customer initiated inbound WhatsApp conversation (" + (senderInfo != null ? senderInfo : "Direct Chat") + ")")
                    .build();
            updateConsent(tenantId, contactId, updateReq, "WHATSAPP_INBOUND", "SYSTEM_INGRESS", "WHATSAPP_WEBHOOK");
        } catch (Exception e) {
            log.warn("⚠️ [Consent] Inbound WhatsApp auto opt-in warning for contactId={}: {}", contactId, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public ConsentSummaryDTO getConsentSummary(UUID tenantId) {
        long totalContacts = contactRepository.countByTenant_Id(tenantId);

        List<ContactChannelPreference> preferences = preferenceRepository.findAll().stream()
                .filter(p -> tenantId.toString().equalsIgnoreCase(p.getBusinessId()))
                .collect(Collectors.toList());

        long waIn = preferences.stream().filter(p -> "OPTED_IN".equalsIgnoreCase(p.getWhatsappConsentStatus())).count();
        long waOut = preferences.stream().filter(p -> "OPTED_OUT".equalsIgnoreCase(p.getWhatsappConsentStatus())).count();
        long waUnknown = totalContacts - (waIn + waOut);

        long emailIn = preferences.stream().filter(p -> "OPTED_IN".equalsIgnoreCase(p.getEmailConsentStatus())).count();
        long emailOut = preferences.stream().filter(p -> "OPTED_OUT".equalsIgnoreCase(p.getEmailConsentStatus())).count();
        long emailUnknown = totalContacts - (emailIn + emailOut);

        long smsIn = preferences.stream().filter(p -> "OPTED_IN".equalsIgnoreCase(p.getSmsConsentStatus())).count();
        long smsOut = preferences.stream().filter(p -> "OPTED_OUT".equalsIgnoreCase(p.getSmsConsentStatus())).count();
        long smsUnknown = totalContacts - (smsIn + smsOut);

        long globallySuppressed = preferences.stream().filter(p -> Boolean.TRUE.equals(p.getIsGloballySuppressed())).count();

        return ConsentSummaryDTO.builder()
                .totalContacts(totalContacts)
                .whatsapp(new ConsentSummaryDTO.ChannelMetrics(waIn, waOut, Math.max(0, waUnknown)))
                .email(new ConsentSummaryDTO.ChannelMetrics(emailIn, emailOut, Math.max(0, emailUnknown)))
                .sms(new ConsentSummaryDTO.ChannelMetrics(smsIn, smsOut, Math.max(0, smsUnknown)))
                .globallySuppressedCount(globallySuppressed)
                .build();
    }

    private void recordAuditLog(UUID tenantId, UUID contactId, String channel, String prevStatus, String newStatus, String source, String reason, String performedBy, String ipAddress, String userAgent) {
        ContactConsentAuditLog logEntry = ContactConsentAuditLog.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .channel(channel)
                .previousStatus(prevStatus != null ? prevStatus : "UNKNOWN")
                .newStatus(newStatus)
                .source(source != null ? source : "ADMIN_MANUAL")
                .reason(reason)
                .performedBy(performedBy != null ? performedBy : "SYSTEM")
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .timestamp(Instant.now())
                .build();
        auditLogRepository.save(logEntry);
    }

    private String normalizeStatus(String status) {
        if (status == null) return "UNKNOWN";
        String s = status.toUpperCase().trim();
        if (s.equals("TRUE") || s.equals("ALLOW") || s.equals("OPTED_IN") || s.equals("OPT_IN")) return "OPTED_IN";
        if (s.equals("FALSE") || s.equals("BLOCK") || s.equals("OPTED_OUT") || s.equals("OPT_OUT")) return "OPTED_OUT";
        return "UNKNOWN";
    }

    private ContactConsentDTO buildConsentDTO(Contact contact, ContactChannelPreference pref, List<ContactConsentAuditLog> auditLogs) {
        String waStatus = pref != null ? pref.getWhatsappConsentStatus() : "UNKNOWN";
        String emailStatus = pref != null ? pref.getEmailConsentStatus() : "UNKNOWN";
        String smsStatus = pref != null ? pref.getSmsConsentStatus() : "UNKNOWN";
        boolean globalSuppressed = pref != null && Boolean.TRUE.equals(pref.getIsGloballySuppressed());

        boolean waAllowed = !globalSuppressed && !"OPTED_OUT".equalsIgnoreCase(waStatus) && !contact.isMarketingOptedOut();
        boolean emailAllowed = !globalSuppressed && !"OPTED_OUT".equalsIgnoreCase(emailStatus);
        boolean smsAllowed = !globalSuppressed && !"OPTED_OUT".equalsIgnoreCase(smsStatus);

        Instant updatedAt = pref != null && pref.getUpdatedAt() != null ? pref.getUpdatedAt().toInstant() : Instant.now();
        String lastSource = auditLogs != null && !auditLogs.isEmpty() ? auditLogs.get(0).getSource() : "INITIAL_IMPORT";

        List<ContactConsentDTO.AuditLogEntryDTO> auditDTOs = auditLogs == null ? List.of() : auditLogs.stream()
                .map(l -> ContactConsentDTO.AuditLogEntryDTO.builder()
                        .id(l.getId())
                        .channel(l.getChannel())
                        .previousStatus(l.getPreviousStatus())
                        .newStatus(l.getNewStatus())
                        .source(l.getSource())
                        .reason(l.getReason())
                        .performedBy(l.getPerformedBy())
                        .timestamp(l.getTimestamp())
                        .build())
                .collect(Collectors.toList());

        return ContactConsentDTO.builder()
                .contactId(contact.getId())
                .contactName(contact.getName())
                .phone(contact.getWaId())
                .email(contact.getEmail())
                .whatsappConsentStatus(waStatus)
                .whatsappAllowed(waAllowed)
                .emailConsentStatus(emailStatus)
                .emailAllowed(emailAllowed)
                .smsConsentStatus(smsStatus)
                .smsAllowed(smsAllowed)
                .globallySuppressed(globalSuppressed)
                .marketingOptedOut(contact.isMarketingOptedOut())
                .updatedAt(updatedAt)
                .lastSource(lastSource)
                .recentAuditLogs(auditDTOs)
                .build();
    }
}
