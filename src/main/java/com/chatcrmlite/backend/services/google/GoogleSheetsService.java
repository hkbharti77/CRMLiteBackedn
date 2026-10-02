package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.sheets.v4.Sheets;
import com.google.api.services.sheets.v4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for Google Sheets integration (spreadsheets scope).
 *
 * Provides:
 * - One-click export of CRM Leads to a newly generated Google Sheet
 * - Bulk import of leads from an existing Google Sheet with deduplication
 */
@Service
public class GoogleSheetsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleSheetsService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired(required = false) private GoogleAuditService auditService;

    /**
     * Checks if the user has an active Google Sheets integration.
     */
    public boolean isConnected(UUID userId) {
        if (userId == null) return false;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(userId);
        if (connOpt.isEmpty()) return false;

        GoogleConnection conn = connOpt.get();
        Optional<GoogleIntegration> integrationOpt =
                integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.SHEETS);

        if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
            return true;
        }

        return conn.hasScope("spreadsheets") || conn.hasScope("https://www.googleapis.com/auth/spreadsheets");
    }

    /**
     * Exports all active CRM leads for the user into a newly created Google Spreadsheet.
     *
     * @param user The authenticated user
     * @param customTitle Optional custom title for the spreadsheet
     * @return Map with spreadsheetId, spreadsheetUrl, rowsExported
     */
    @Transactional
    public Map<String, Object> exportLeadsToSheet(User user, String customTitle) throws GeneralSecurityException, IOException {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found. Please connect Google Sheets in Settings."));

        if (!isConnected(user.getId())) {
            throw new IllegalStateException("Google Sheets is not connected. Please authorize Sheets in Settings.");
        }

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Sheets sheetsClient = buildSheetsClient(accessToken);

        // 1. Create a new Spreadsheet
        String title = (customTitle != null && !customTitle.isBlank())
                ? customTitle
                : "GyanVaniAi Leads Export - " + LocalDate.now();

        Spreadsheet spreadsheet = new Spreadsheet()
                .setProperties(new SpreadsheetProperties().setTitle(title));

        long startTime = System.currentTimeMillis();
        Spreadsheet createdSheet;
        try {
            createdSheet = sheetsClient.spreadsheets().create(spreadsheet)
                    .setFields("spreadsheetId,spreadsheetUrl")
                    .execute();
            if (auditService != null) {
                auditService.logSuccess(user.getId(), "SHEETS", "CREATE_SPREADSHEET", "sheets.googleapis.com", System.currentTimeMillis() - startTime);
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(user.getId(), "SHEETS", "CREATE_SPREADSHEET", "sheets.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
            }
            throw ex;
        }

        String spreadsheetId = createdSheet.getSpreadsheetId();
        String spreadsheetUrl = createdSheet.getSpreadsheetUrl();

        // 2. Fetch leads from database
        List<Lead> leads = leadRepository.findAllByOwner(user);

        // 3. Prepare rows
        List<List<Object>> values = new ArrayList<>();
        // Header
        values.add(Arrays.asList("Lead Ref", "Name", "WhatsApp / Mobile", "Email", "Status", "Source", "Created Date"));

        for (Lead lead : leads) {
            Contact c = lead.getContact();
            values.add(Arrays.asList(
                    lead.getLeadNumber() != null ? lead.getLeadNumber() : lead.getId().toString().substring(0, 8),
                    c != null && c.getName() != null ? c.getName() : "-",
                    c != null && c.getWaId() != null ? c.getWaId() : "-",
                    c != null && c.getEmail() != null ? c.getEmail() : "-",
                    lead.getStatus() != null ? lead.getStatus().name() : "NEW",
                    c != null && c.getSource() != null ? c.getSource() : "MANUAL",
                    lead.getCreatedAt() != null ? lead.getCreatedAt().toString() : "-"
            ));
        }

        // 4. Write data to Sheet
        ValueRange body = new ValueRange().setValues(values);
        sheetsClient.spreadsheets().values()
                .update(spreadsheetId, "A1", body)
                .setValueInputOption("USER_ENTERED")
                .execute();

        log.info("[GoogleSheetsService] Exported {} leads to Google Sheet {} for userId={}",
                leads.size(), spreadsheetId, user.getId());

        // 5. Record GoogleSync row
        try {
            GoogleSync sync = new GoogleSync();
            sync.setConnectionId(conn.getId());
            sync.setResourceType("SHEET");
            sync.setCrmResourceId(UUID.randomUUID());
            sync.setGoogleResourceId(spreadsheetId);
            sync.setSyncDirection("CRM_TO_GOOGLE");
            sync.setSyncStatus("OK");
            sync.setLastSyncedAt(LocalDateTime.now());
            syncRepository.save(sync);
        } catch (Exception e) {
            log.warn("[GoogleSheetsService] Failed to record GoogleSync row: {}", e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("spreadsheetId", spreadsheetId);
        result.put("spreadsheetUrl", spreadsheetUrl);
        result.put("rowsExported", leads.size());
        result.put("exportedAt", LocalDateTime.now().toString());
        return result;
    }

    /**
     * Imports leads from an existing Google Sheet.
     *
     * @param user The authenticated user
     * @param spreadsheetId Google Spreadsheet ID
     * @param range Sheet range (e.g. "Sheet1!A1:Z500" or null for default)
     * @return Summary statistics
     */
    @Transactional
    public Map<String, Object> importLeadsFromSheet(User user, String spreadsheetId, String range)
            throws GeneralSecurityException, IOException {

        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found."));

        if (!isConnected(user.getId())) {
            throw new IllegalStateException("Google Sheets is not connected. Please authorize Sheets in Settings.");
        }

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        Sheets sheetsClient = buildSheetsClient(accessToken);

        String readRange = (range != null && !range.isBlank()) ? range : "A1:Z1000";
        long readStart = System.currentTimeMillis();
        ValueRange response;
        try {
            response = sheetsClient.spreadsheets().values()
                    .get(spreadsheetId, readRange)
                    .execute();
            if (auditService != null) {
                auditService.logSuccess(user.getId(), "SHEETS", "READ_SHEET_VALUES", "sheets.googleapis.com", System.currentTimeMillis() - readStart);
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(user.getId(), "SHEETS", "READ_SHEET_VALUES", "sheets.googleapis.com", System.currentTimeMillis() - readStart, ex, 0);
            }
            throw ex;
        }

        List<List<Object>> values = response.getValues();
        if (values == null || values.size() <= 1) {
            return Map.of("totalRead", 0, "importedCount", 0, "skippedCount", 0, "message", "No rows found in sheet");
        }

        // Header mapping
        List<Object> header = values.get(0);
        int nameCol = -1, phoneCol = -1, emailCol = -1, sourceCol = -1;

        for (int i = 0; i < header.size(); i++) {
            String col = String.valueOf(header.get(i)).trim().toLowerCase();
            if (col.contains("name")) nameCol = i;
            else if (col.contains("phone") || col.contains("mobile") || col.contains("wa") || col.contains("contact")) phoneCol = i;
            else if (col.contains("email") || col.contains("mail")) emailCol = i;
            else if (col.contains("source")) sourceCol = i;
        }

        int totalRead = 0;
        int importedCount = 0;
        int skippedCount = 0;

        for (int i = 1; i < values.size(); i++) {
            List<Object> row = values.get(i);
            totalRead++;

            String name = (nameCol >= 0 && nameCol < row.size()) ? String.valueOf(row.get(nameCol)).trim() : null;
            String phone = (phoneCol >= 0 && phoneCol < row.size()) ? String.valueOf(row.get(phoneCol)).trim() : null;
            String email = (emailCol >= 0 && emailCol < row.size()) ? String.valueOf(row.get(emailCol)).trim() : null;
            String source = (sourceCol >= 0 && sourceCol < row.size()) ? String.valueOf(row.get(sourceCol)).trim() : "GOOGLE_SHEETS";

            String cleanPhone = normalizePhone(phone);

            if ((name == null || name.isBlank()) && (cleanPhone == null || cleanPhone.isBlank()) && (email == null || email.isBlank())) {
                skippedCount++;
                continue;
            }

            if (name == null || name.isBlank()) {
                name = email != null ? email.split("@")[0] : ("Lead " + cleanPhone);
            }

            // Deduplication
            UUID tenantId = user.getTenant() != null ? user.getTenant().getId() : null;
            boolean exists = false;
            if (cleanPhone != null && tenantId != null) {
                exists = contactRepository.existsByWaIdAndTenant_Id(cleanPhone, tenantId);
            }
            if (!exists && email != null && tenantId != null) {
                exists = contactRepository.existsByEmailAndTenant_Id(email.toLowerCase(), tenantId);
            }

            if (exists) {
                skippedCount++;
                continue;
            }

            // Create Contact
            Contact contact = new Contact();
            contact.setName(name);
            contact.setEmail(email != null ? email.toLowerCase() : null);
            contact.setWaId(cleanPhone != null ? cleanPhone : "s-" + UUID.randomUUID().toString().substring(0, 10));
            contact.setDisplayId(cleanPhone);
            contact.setSource(source != null ? source : "GOOGLE_SHEETS");
            contact.setOwner(user);
            if (user.getTenant() != null) {
                contact.setTenant(user.getTenant());
            }
            Contact savedContact = contactRepository.save(contact);

            // Create Lead
            Lead lead = new Lead();
            lead.setContact(savedContact);
            lead.setStatus(Lead.LeadStatus.NEW);
            lead.setOwner(user);
            if (user.getTenant() != null) {
                lead.setTenant(user.getTenant());
            }
            leadRepository.save(lead);
            importedCount++;
        }

        log.info("[GoogleSheetsService] Sheet import for userId={}: totalRead={} imported={} skipped={}",
                user.getId(), totalRead, importedCount, skippedCount);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalRead", totalRead);
        stats.put("importedCount", importedCount);
        stats.put("skippedCount", skippedCount);
        stats.put("importedAt", LocalDateTime.now().toString());
        return stats;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            return digits;
        }
        if (digits.length() == 10) {
            return "91" + digits;
        }
        return digits.isEmpty() ? null : digits;
    }

    private Sheets buildSheetsClient(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new Sheets.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
