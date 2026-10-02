package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleIntegrationRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.google.GoogleTokenService;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for Google Calendar integration & Google Meet link generation.
 *
 * Integrated with the centralized GoogleTokenService and GoogleSync repository:
 * - Checks active GoogleConnection + CALENDAR feature status
 * - Uses GoogleTokenService for token retrieval & auto-refresh
 * - Creates GoogleSync mapping records for idempotency and auditability
 * - Maintains backward compatibility with legacy User google tokens
 */
@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    @Autowired private GoogleConfig googleConfig;
    @Autowired private UserRepository userRepository;
    @Autowired private GoogleTokenService googleTokenService;
    @Autowired private GoogleConnectionRepository connectionRepository;
    @Autowired private GoogleIntegrationRepository integrationRepository;
    @Autowired private GoogleSyncRepository syncRepository;
    @Autowired(required = false) private com.chatcrmlite.backend.services.google.GoogleAuditService auditService;

    /**
     * Returns true if the given user has an active Google Calendar connection.
     * Checks GoogleConnection + CALENDAR status first, then falls back to legacy User tokens.
     */
    public boolean isConnected(User user) {
        if (user == null || user.getId() == null) {
            return false;
        }

        // 1. Check GoogleConnection layer
        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(user.getId());
        if (connOpt.isPresent()) {
            GoogleConnection conn = connOpt.get();
            Optional<GoogleIntegration> integrationOpt =
                    integrationRepository.findByConnectionIdAndFeature(conn.getId(), GoogleIntegrationType.CALENDAR);
            if (integrationOpt.isPresent() && integrationOpt.get().getStatus() == GoogleIntegrationStatus.CONNECTED) {
                return true;
            }
            if (conn.hasGrantedScope("calendar")) {
                return true;
            }
        }

        // 2. Legacy fallback
        return user.getGoogleRefreshToken() != null && !user.getGoogleRefreshToken().isBlank();
    }

    /**
     * Backward-compatible overload without appointmentId.
     */
    public String[] createMeetLink(User owner, String appointmentTitle, LocalDateTime startTime, String clientEmail, int durationMinutes)
            throws IOException, GeneralSecurityException {
        return createMeetLink(owner, appointmentTitle, startTime, clientEmail, durationMinutes, null);
    }

    /**
     * Creates a Google Calendar event with a Google Meet conference link.
     * The event is added to the user's primary calendar.
     *
     * @return an array where [0] is the Meet link, and [1] is the Google Event ID
     */
    @Transactional
    public String[] createMeetLink(User owner, String appointmentTitle, LocalDateTime startTime, String clientEmail, int durationMinutes, UUID appointmentId)
            throws IOException, GeneralSecurityException {

        if (!isConnected(owner)) {
            throw new IllegalStateException("Google Calendar not connected. Please connect your Google account in Settings.");
        }

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(owner.getId());
        Calendar calendarService;

        if (connOpt.isPresent()) {
            // New architecture: retrieve valid token from GoogleTokenService (auto-refreshes if needed)
            String accessToken = googleTokenService.getValidAccessToken(connOpt.get().getId());
            calendarService = buildCalendarServiceWithToken(accessToken);
        } else {
            // Legacy token fallback
            calendarService = buildLegacyCalendarService(owner);
        }

        String tenantTz = (owner.getTenant() != null && owner.getTenant().getTimezone() != null)
                ? owner.getTenant().getTimezone()
                : "Asia/Kolkata";
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(tenantTz);
        } catch (Exception e) {
            zoneId = ZoneId.of("Asia/Kolkata");
        }

        Date startDate = Date.from(startTime.atZone(zoneId).toInstant());
        Date endDate = Date.from(startTime.plusMinutes(durationMinutes).atZone(zoneId).toInstant());

        Event event = new Event()
                .setSummary(appointmentTitle)
                .setDescription("Meeting scheduled via GyanVaniAi Connect CRM");

        event.setStart(new EventDateTime().setDateTime(new DateTime(startDate)).setTimeZone(tenantTz));
        event.setEnd(new EventDateTime().setDateTime(new DateTime(endDate)).setTimeZone(tenantTz));

        // Add client as attendee so they get the Meet link via Google calendar invite
        if (clientEmail != null && !clientEmail.isBlank()) {
            List<EventAttendee> attendees = Arrays.stream(clientEmail.split(","))
                    .map(String::trim)
                    .filter(e -> !e.isEmpty())
                    .map(e -> new EventAttendee().setEmail(e))
                    .collect(Collectors.toList());
            event.setAttendees(attendees);
        }

        // Enable Google Meet conference
        ConferenceData conferenceData = new ConferenceData();
        CreateConferenceRequest createRequest = new CreateConferenceRequest();
        createRequest.setRequestId(UUID.randomUUID().toString());
        ConferenceSolutionKey solutionKey = new ConferenceSolutionKey().setType("hangoutsMeet");
        createRequest.setConferenceSolutionKey(solutionKey);
        conferenceData.setCreateRequest(createRequest);
        event.setConferenceData(conferenceData);

        long startTimeMs = System.currentTimeMillis();
        Event createdEvent;
        try {
            createdEvent = calendarService.events()
                    .insert("primary", event)
                    .setConferenceDataVersion(1)
                    .setSendUpdates("all") // sends Google Calendar invite to attendees
                    .execute();
            if (auditService != null) {
                auditService.logSuccess(owner.getId(), "CALENDAR", "CREATE_EVENT", "calendar.googleapis.com", System.currentTimeMillis() - startTimeMs);
            }
        } catch (Exception ex) {
            if (auditService != null) {
                auditService.logFailure(owner.getId(), "CALENDAR", "CREATE_EVENT", "calendar.googleapis.com", System.currentTimeMillis() - startTimeMs, ex, 0);
            }
            throw ex;
        }

        // Extract Meet link
        String meetLink = createdEvent.getHtmlLink();
        if (createdEvent.getConferenceData() != null &&
                createdEvent.getConferenceData().getEntryPoints() != null) {
            for (EntryPoint ep : createdEvent.getConferenceData().getEntryPoints()) {
                if ("video".equals(ep.getEntryPointType())) {
                    log.info("[GoogleCalendarService] Meet link created: {}", ep.getUri());
                    meetLink = ep.getUri();
                    break;
                }
            }
        }

        // Save GoogleSync mapping if appointmentId is provided and connection exists
        if (connOpt.isPresent() && appointmentId != null) {
            try {
                UUID connId = connOpt.get().getId();
                GoogleSync sync = syncRepository
                        .findByConnectionIdAndResourceTypeAndCrmResourceId(connId, "CALENDAR_EVENT", appointmentId)
                        .orElse(new GoogleSync());

                sync.setConnectionId(connId);
                sync.setResourceType("CALENDAR_EVENT");
                sync.setCrmResourceId(appointmentId);
                sync.setGoogleResourceId(createdEvent.getId());
                sync.setSyncDirection("CRM_TO_GOOGLE");
                sync.setSyncStatus("OK");
                sync.setLastSyncedAt(LocalDateTime.now());
                syncRepository.save(sync);
            } catch (Exception e) {
                log.warn("[GoogleCalendarService] Failed to record GoogleSync mapping: {}", e.getMessage());
            }
        }

        return new String[] { meetLink, createdEvent.getId() };
    }

    /**
     * Deletes a calendar event. Used when appointments are canceled or rescheduled.
     */
    @Transactional
    public void deleteEvent(User owner, String eventId) {
        if (!isConnected(owner) || eventId == null || eventId.isBlank()) {
            return;
        }

        try {
            Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(owner.getId());
            Calendar calendarService;

            if (connOpt.isPresent()) {
                String accessToken = googleTokenService.getValidAccessToken(connOpt.get().getId());
                calendarService = buildCalendarServiceWithToken(accessToken);
            } else {
                calendarService = buildLegacyCalendarService(owner);
            }

            calendarService.events().delete("primary", eventId).execute();
            log.info("[GoogleCalendarService] Deleted eventId={} for userId={}", eventId, owner.getId());

            // Update GoogleSync status if mapped
            if (connOpt.isPresent()) {
                syncRepository.findByConnectionIdAndResourceTypeAndGoogleResourceId(
                        connOpt.get().getId(), "CALENDAR_EVENT", eventId
                ).ifPresent(sync -> {
                    sync.setSyncStatus("UNLINKED");
                    syncRepository.save(sync);
                });
            }

        } catch (Exception e) {
            log.warn("[GoogleCalendarService] Failed to delete eventId={}: {}", eventId, e.getMessage());
        }
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private Calendar buildCalendarServiceWithToken(String accessToken) throws GeneralSecurityException, IOException {
        HttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
        HttpRequestInitializer initializer = request ->
                request.getHeaders().setAuthorization("Bearer " + accessToken);

        return new Calendar.Builder(transport, JSON_FACTORY, initializer)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    private Calendar buildLegacyCalendarService(User owner) throws IOException, GeneralSecurityException {
        HttpTransport httpTransport = GoogleNetHttpTransport.newTrustedTransport();

        ClientParametersAuthentication clientAuth =
                new ClientParametersAuthentication(googleConfig.getClientId(), googleConfig.getClientSecret());

        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .setTransport(httpTransport)
                .setJsonFactory(JSON_FACTORY)
                .setTokenServerUrl(new GenericUrl("https://oauth2.googleapis.com/token"))
                .setClientAuthentication(clientAuth)
                .build()
                .setAccessToken(owner.getGoogleAccessToken())
                .setRefreshToken(owner.getGoogleRefreshToken());

        // Refresh if expired
        if (owner.getGoogleTokenExpiry() == null ||
                LocalDateTime.now().isAfter(owner.getGoogleTokenExpiry().minusMinutes(5))) {
            log.info("[GoogleCalendarService] Legacy access token near-expiry, refreshing for userId={}", owner.getId());
            credential.refreshToken();

            if (credential.getAccessToken() != null) {
                owner.setGoogleAccessToken(credential.getAccessToken());
                if (credential.getExpiresInSeconds() != null) {
                    owner.setGoogleTokenExpiry(LocalDateTime.now().plusSeconds(credential.getExpiresInSeconds()));
                }
                userRepository.save(owner);
            }
        }

        return new Calendar.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }
}
