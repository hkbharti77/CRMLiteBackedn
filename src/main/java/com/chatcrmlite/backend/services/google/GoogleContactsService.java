package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.people.v1.PeopleService;
import com.google.api.services.people.v1.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for Google Contacts import via the People API (contacts.readonly scope).
 *
 * Architecture:
 * - Pagination via nextPageToken
 * - Idempotency & deduplication via GoogleSync mapping (resourceType: CONTACT)
 * - Safe merging: if contact already exists by phone/email, updates details without duplicate creation
 * - Soft-unlink on deleted Google contacts (CRM records are never deleted)
 */
@Service
public class GoogleContactsService {

    private static final Logger log = LoggerFactory.getLogger(GoogleContactsService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired private ContactRepository contactRepository;
    @Autowired(required = false) private GoogleAuditService auditService;

    /**
     * Checks if the user has an active Google Contacts integration.
     */
    public boolean isConnected(UUID userId) {
        if (userId == null) return false;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(userId);
        if (connOpt.isEmpty()) return false;

        GoogleConnection conn = connOpt.get();
        Optional<GoogleIntegration> integrationOpt =
                integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.CONTACTS);

        if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
            return true;
        }

        return conn.hasScope("contacts.readonly") || conn.hasScope("https://www.googleapis.com/auth/contacts.readonly");
    }

    /**
     * Imports/syncs contacts from user's connected Google Contacts account into CRMLite.
     *
     * @param user The authenticated CRM user
     * @param maxResults Maximum contacts to import (default: 100, max: 1000)
     * @return Summary stats map
     */
    @Transactional
    public Map<String, Object> importContacts(User user, int maxResults) throws GeneralSecurityException, IOException {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("User must not be null");
        }

        GoogleConnection conn = connectionRepository.findActiveByUserId(user.getId())
                .orElseThrow(() -> new IllegalStateException("No active Google connection found. Please connect your Google account in Settings."));

        if (!isConnected(user.getId())) {
            throw new IllegalStateException("Google Contacts is not connected. Please authorize contacts access in Settings.");
        }

        String accessToken = googleTokenService.getValidAccessToken(conn.getId());
        PeopleService peopleService = buildPeopleClient(accessToken);

        int totalFetched = 0;
        int importedCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;

        String pageToken = null;
        int pageSize = Math.min(Math.max(maxResults, 10), 100);

        do {
            long startTime = System.currentTimeMillis();
            ListConnectionsResponse response;
            try {
                response = peopleService.people().connections()
                        .list("people/me")
                        .setPersonFields("names,emailAddresses,phoneNumbers")
                        .setPageSize(pageSize)
                        .setPageToken(pageToken)
                        .execute();
                if (auditService != null) {
                    auditService.logSuccess(user.getId(), "CONTACTS", "LIST_CONNECTIONS", "people.googleapis.com", System.currentTimeMillis() - startTime);
                }
            } catch (Exception ex) {
                if (auditService != null) {
                    auditService.logFailure(user.getId(), "CONTACTS", "LIST_CONNECTIONS", "people.googleapis.com", System.currentTimeMillis() - startTime, ex, 0);
                }
                throw ex;
            }

            List<Person> connections = response.getConnections();
            if (connections == null || connections.isEmpty()) {
                break;
            }

            for (Person person : connections) {
                totalFetched++;

                String googlePersonId = person.getResourceName(); // e.g. "people/c123456789"
                if (googlePersonId == null || googlePersonId.isBlank()) {
                    skippedCount++;
                    continue;
                }

                // Extract Name
                String displayName = null;
                if (person.getNames() != null && !person.getNames().isEmpty()) {
                    displayName = person.getNames().get(0).getDisplayName();
                }

                // Extract primary email
                String email = null;
                if (person.getEmailAddresses() != null && !person.getEmailAddresses().isEmpty()) {
                    email = person.getEmailAddresses().get(0).getValue();
                }

                // Extract primary phone number
                String phone = null;
                if (person.getPhoneNumbers() != null && !person.getPhoneNumbers().isEmpty()) {
                    phone = person.getPhoneNumbers().get(0).getValue();
                }

                // Clean phone number (extract digits)
                String cleanPhone = normalizePhone(phone);

                if ((displayName == null || displayName.isBlank()) && (email == null || email.isBlank()) && (cleanPhone == null || cleanPhone.isBlank())) {
                    skippedCount++;
                    continue;
                }

                if (displayName == null || displayName.isBlank()) {
                    displayName = email != null ? email.split("@")[0] : ("Contact " + cleanPhone);
                }

                // 1. Check if already synced by Google resource name
                Optional<GoogleSync> syncOpt = syncRepository.findByConnectionIdAndResourceTypeAndGoogleResourceId(
                        conn.getId(), "CONTACT", googlePersonId
                );

                Contact contact;
                boolean isNew = false;

                if (syncOpt.isPresent()) {
                    // Existing synced contact → update
                    UUID crmId = syncOpt.get().getCrmResourceId();
                    contact = contactRepository.findById(crmId).orElse(null);
                    if (contact == null) {
                        contact = new Contact();
                        isNew = true;
                    }
                } else {
                    // 2. Check if contact exists by phone or email for this tenant
                    contact = findExistingContact(user, cleanPhone, email).orElse(null);
                    if (contact == null) {
                        contact = new Contact();
                        isNew = true;
                    }
                }

                // Populate fields
                contact.setName(displayName);
                if (email != null && !email.isBlank()) {
                    contact.setEmail(email.trim().toLowerCase());
                }
                if (cleanPhone != null && !cleanPhone.isBlank()) {
                    contact.setWaId(cleanPhone);
                    contact.setDisplayId(cleanPhone);
                } else if (contact.getWaId() == null) {
                    contact.setWaId("g-" + UUID.randomUUID().toString().substring(0, 12));
                }

                contact.setSource("GOOGLE_CONTACTS");
                contact.setOwner(user);
                if (user.getTenant() != null) {
                    contact.setTenant(user.getTenant());
                }
                if (isNew) {
                    contact.setBotPaused(false);
                }

                Contact savedContact = contactRepository.save(contact);

                // 3. Upsert GoogleSync record (check both googleResourceId and crmResourceId to respect unique constraints)
                GoogleSync sync = syncOpt.orElse(null);
                if (sync == null && savedContact.getId() != null) {
                    sync = syncRepository.findByConnectionIdAndResourceTypeAndCrmResourceId(
                            conn.getId(), "CONTACT", savedContact.getId()
                    ).orElse(null);
                }

                if (sync == null) {
                    sync = new GoogleSync();
                    sync.setConnectionId(conn.getId());
                    sync.setResourceType("CONTACT");
                    sync.setCrmResourceId(savedContact.getId());
                }

                sync.setGoogleResourceId(googlePersonId);
                sync.setSyncDirection("GOOGLE_TO_CRM");
                sync.setSyncStatus("OK");
                sync.setLastSyncedAt(LocalDateTime.now());
                syncRepository.save(sync);

                if (isNew) {
                    importedCount++;
                } else {
                    updatedCount++;
                }
            }

            pageToken = response.getNextPageToken();
        } while (pageToken != null && totalFetched < maxResults);

        log.info("[GoogleContactsService] Finished sync for userId={}: totalFetched={} imported={} updated={} skipped={}",
                user.getId(), totalFetched, importedCount, updatedCount, skippedCount);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalFetched", totalFetched);
        stats.put("importedCount", importedCount);
        stats.put("updatedCount", updatedCount);
        stats.put("skippedCount", skippedCount);
        stats.put("syncedAt", LocalDateTime.now().toString());
        return stats;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private Optional<Contact> findExistingContact(User user, String cleanPhone, String email) {
        UUID tenantId = user.getTenant() != null ? user.getTenant().getId() : null;

        if (cleanPhone != null && !cleanPhone.isBlank()) {
            if (tenantId != null) {
                Optional<Contact> byWa = contactRepository.findByWaIdAndTenant_Id(cleanPhone, tenantId);
                if (byWa.isPresent()) return byWa;
            }
            Optional<Contact> byWaOwner = contactRepository.findByWaIdAndOwner(cleanPhone, user);
            if (byWaOwner.isPresent()) return byWaOwner;
        }

        if (email != null && !email.isBlank() && tenantId != null) {
            Optional<Contact> byEmail = contactRepository.findFirstByEmailAndTenant_Id(email.trim().toLowerCase(), tenantId);
            if (byEmail.isPresent()) return byEmail;
        }

        return Optional.empty();
    }

    private String normalizePhone(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() > 10 && digits.startsWith("91")) {
            return digits; // Already has country code
        }
        if (digits.length() == 10) {
            return "91" + digits; // Add default Indian country code
        }
        return digits.isEmpty() ? null : digits;
    }

    private PeopleService buildPeopleClient(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new PeopleService.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
