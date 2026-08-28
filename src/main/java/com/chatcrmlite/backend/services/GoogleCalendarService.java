package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.config.GoogleConfig;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.ClientParametersAuthentication;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.MemoryDataStoreFactory;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GoogleCalendarService {

    private static final Logger log = LoggerFactory.getLogger(GoogleCalendarService.class);
    private static final String APPLICATION_NAME = "CRMLite";
    private static final GsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final List<String> SCOPES = Collections.singletonList(CalendarScopes.CALENDAR);

    @Autowired private GoogleConfig googleConfig;
    @Autowired private UserRepository userRepository;

    /**
     * Builds the OAuth2 authorization URL that the user must visit to authorize access
     * to their Google Calendar.
     * 
     * @param state a cryptographically secure random string bound to the user's session
     */
    public String buildAuthorizationUrl(String state) throws IOException, GeneralSecurityException {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        return flow.newAuthorizationUrl()
                .setRedirectUri(googleConfig.getRedirectUri())
                .setState(state) // pass secure opaque state to identify user in callback
                .setAccessType("offline")
                .set("prompt", "consent") // force refresh token every time
                .build();
    }

    /**
     * Exchanges the authorization code (from the OAuth callback) for access+refresh tokens
     * and saves them to the user's record.
     */
    @Transactional
    public void handleOAuthCallback(String code, UUID userId) throws IOException, GeneralSecurityException {
        GoogleAuthorizationCodeFlow flow = buildFlow();
        TokenResponse tokenResponse = flow.newTokenRequest(code)
                .setRedirectUri(googleConfig.getRedirectUri())
                .execute();

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found: " + userId));

        user.setGoogleAccessToken(tokenResponse.getAccessToken());
        user.setGoogleRefreshToken(tokenResponse.getRefreshToken() != null
                ? tokenResponse.getRefreshToken()
                : user.getGoogleRefreshToken()); // keep old refresh token if not returned
        long expiresInSeconds = tokenResponse.getExpiresInSeconds() != null ? tokenResponse.getExpiresInSeconds() : 3600L;
        user.setGoogleTokenExpiry(LocalDateTime.now().plusSeconds(expiresInSeconds));
        userRepository.save(user);
        log.info("[GoogleCalendarService] OAuth tokens saved for user={}", userId);
    }

    /**
     * Creates a Google Calendar event with a Google Meet conference link.
     * The event is added to the user's primary calendar.
     *
     * @return an array where [0] is the Meet link, and [1] is the Google Event ID
     */
    public String[] createMeetLink(User owner, String appointmentTitle, LocalDateTime startTime, String clientEmail, int durationMinutes)
            throws IOException, GeneralSecurityException {

        if (owner.getGoogleAccessToken() == null || owner.getGoogleAccessToken().isBlank()) {
            throw new IllegalStateException("Google Calendar not connected. Please connect your Google account in Settings.");
        }

        Calendar calendarService = buildCalendarService(owner);

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
                .setDescription("Meeting scheduled via CRMLite");

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

        Event createdEvent = calendarService.events()
                .insert("primary", event)
                .setConferenceDataVersion(1)
                .setSendUpdates("all") // sends Google Calendar invite to attendees
                .execute();

        // Extract the Meet link
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
        
        return new String[] { meetLink, createdEvent.getId() };
    }

    /**
     * Deletes a calendar event. Used to forcefully expire meeting links.
     */
    public void deleteEvent(User owner, String eventId) {
        if (owner.getGoogleAccessToken() == null || owner.getGoogleAccessToken().isBlank()) {
            return;
        }
        try {
            Calendar calendarService = buildCalendarService(owner);
            calendarService.events().delete("primary", eventId).execute();
            log.info("[GoogleCalendarService] Deleted eventId={} for userId={}", eventId, owner.getId());
        } catch (Exception e) {
            log.warn("[GoogleCalendarService] Failed to delete eventId={}: {}", eventId, e.getMessage());
        }
    }

    /**
     * Returns true if the given user has connected their Google account.
     */
    public boolean isConnected(User user) {
        return user.getGoogleRefreshToken() != null && !user.getGoogleRefreshToken().isBlank();
    }

    // ── Private Helpers ────────────────────────────────────────────────────

    private GoogleAuthorizationCodeFlow buildFlow() throws IOException, GeneralSecurityException {
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
                .setClientId(googleConfig.getClientId())
                .setClientSecret(googleConfig.getClientSecret());

        GoogleClientSecrets clientSecrets = new GoogleClientSecrets().setInstalled(details);

        return new GoogleAuthorizationCodeFlow.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JSON_FACTORY,
                clientSecrets,
                SCOPES)
                .setAccessType("offline")
                .setDataStoreFactory(new MemoryDataStoreFactory())
                .build();
    }

    /**
     * Builds a Calendar client using the user's stored tokens.
     * Uses GoogleCredential directly (no data store needed) — the library
     * automatically refreshes the access token using the stored refresh token.
     * After refresh, updated tokens are saved back to the user record.
     */
    private Calendar buildCalendarService(User owner) throws IOException, GeneralSecurityException {
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

        // If token is expired or expiry unknown, refresh immediately
        if (owner.getGoogleTokenExpiry() == null ||
                LocalDateTime.now().isAfter(owner.getGoogleTokenExpiry().minusMinutes(5))) {
            log.info("[GoogleCalendarService] Access token expired/near-expiry, refreshing for userId={}", owner.getId());
            credential.refreshToken();

            // Persist refreshed tokens back to the user record
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
