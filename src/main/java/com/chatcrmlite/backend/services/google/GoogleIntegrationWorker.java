package com.chatcrmlite.backend.services.google;

import com.chatcrmlite.backend.event.AppointmentScheduledEvent;
import com.chatcrmlite.backend.event.ReminderCreatedEvent;
import com.chatcrmlite.backend.models.Appointment;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.*;
import com.chatcrmlite.backend.repositories.AppointmentRepository;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleDeadLetterRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.chatcrmlite.backend.services.GoogleCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Async background worker for Google integrations.
 * Listens for CRM domain events (AppointmentScheduledEvent, ReminderCreatedEvent),
 * coordinates sync with Google APIs, applies exponential backoff for rate limits/server errors,
 * triggers token refresh on 401, logs audit metrics, and writes unrecoverable failures
 * to the Google Dead Letter queue for manual inspection or retry.
 */
@Service
public class GoogleIntegrationWorker {

    private static final Logger log = LoggerFactory.getLogger(GoogleIntegrationWorker.class);

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = { 1000L, 2000L, 4000L };

    private final GoogleCalendarService calendarService;
    private final GoogleTasksService tasksService;
    private final GoogleTokenService tokenService;
    private final GoogleAuditService auditService;
    private final GoogleConnectionRepository connectionRepository;
    private final GoogleSyncRepository syncRepository;
    private final GoogleDeadLetterRepository deadLetterRepository;
    private final AppointmentRepository appointmentRepository;

    @Autowired
    public GoogleIntegrationWorker(GoogleCalendarService calendarService,
                                   GoogleTasksService tasksService,
                                   GoogleTokenService tokenService,
                                   GoogleAuditService auditService,
                                   GoogleConnectionRepository connectionRepository,
                                   GoogleSyncRepository syncRepository,
                                   GoogleDeadLetterRepository deadLetterRepository,
                                   AppointmentRepository appointmentRepository) {
        this.calendarService = calendarService;
        this.tasksService = tasksService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.connectionRepository = connectionRepository;
        this.syncRepository = syncRepository;
        this.deadLetterRepository = deadLetterRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Listens for appointments scheduled anywhere in CRM (flows, manual, API).
     * Synchronizes to Google Calendar & Meet link if the host/user has calendar connected.
     */
    @Async
    @EventListener
    public void onAppointmentScheduled(AppointmentScheduledEvent event) {
        if (event == null || event.getAppointment() == null) return;
        // Avoid looping when Google Meet link generation updates the appointment
        if ("MEET_LINK_GENERATED".equalsIgnoreCase(event.getSource())) {
            return;
        }

        Appointment appt = event.getAppointment();
        User host = appt.getOwner();
        if (host == null || host.getId() == null) {
            return;
        }

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(host.getId());
        if (connOpt.isEmpty() || !calendarService.isConnected(host)) {
            log.debug("[GoogleWorker] User id={} has no active Google Calendar integration. Skipping appointmentId={}",
                    host.getId(), appt.getId());
            return;
        }

        UUID connectionId = connOpt.get().getId();

        // Check if already synced
        Optional<GoogleSync> existingSync = syncRepository.findByConnectionIdAndResourceTypeAndCrmResourceId(
                connectionId, "CALENDAR_EVENT", appt.getId()
        );
        if (existingSync.isPresent() && "SYNCED".equalsIgnoreCase(existingSync.get().getSyncStatus())) {
            log.debug("[GoogleWorker] AppointmentId={} already synced to Google Calendar.", appt.getId());
            return;
        }

        log.info("[GoogleWorker] Starting async Google Calendar sync for appointmentId={} hostId={}",
                appt.getId(), host.getId());

        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            long startTime = System.currentTimeMillis();
            try {
                String clientEmail = (appt.getContact() != null) ? appt.getContact().getEmail() : null;
                String title = (appt.getTitle() != null && !appt.getTitle().isBlank())
                        ? appt.getTitle()
                        : ("CRM Appointment: " + (appt.getContact() != null ? appt.getContact().getName() : "Client"));

                int duration = 30;

                String[] meetResult = calendarService.createMeetLink(
                        host, title, appt.getAppointmentDateTime(), clientEmail, duration, appt.getId()
                );

                long latency = System.currentTimeMillis() - startTime;
                auditService.logApiCall(host.getId(), connectionId, "CALENDAR", "CREATE_EVENT",
                        "calendar.googleapis.com", "SUCCESS", latency, null, null, attempt);

                if (meetResult != null && meetResult.length > 0 && meetResult[0] != null && !meetResult[0].isBlank()) {
                    appt.setMeetingLink(meetResult[0]);
                    appointmentRepository.save(appt);
                    log.info("[GoogleWorker] Successfully attached Meet link {} to appointmentId={}",
                            meetResult[0], appt.getId());
                }
                return; // Success
            } catch (Exception ex) {
                lastException = ex;
                long latency = System.currentTimeMillis() - startTime;
                String errMessage = ex.getMessage();

                // 401 Unauthorized -> Refresh token and retry
                if (errMessage != null && errMessage.contains("401")) {
                    log.warn("[GoogleWorker] 401 Unauthorized on attempt {}/{} for appointmentId={}. Refreshing token...",
                            attempt + 1, MAX_RETRIES, appt.getId());
                    try {
                        tokenService.getValidAccessToken(connectionId);
                    } catch (Exception tEx) {
                        log.error("[GoogleWorker] Token refresh failed: {}", tEx.getMessage());
                    }
                }
                // 403 Forbidden -> Granular scope missing or revoked, abort without retry
                else if (errMessage != null && errMessage.contains("403")) {
                    log.error("[GoogleWorker] 403 Forbidden for appointmentId={}. Granular scope or auth missing.", appt.getId());
                    auditService.logApiCall(host.getId(), connectionId, "CALENDAR", "CREATE_EVENT",
                            "calendar.googleapis.com", "REAUTH_REQUIRED", latency, "403", errMessage, attempt);
                    recordDeadLetter(host.getId(), connectionId, "APPOINTMENT", appt.getId(), "CREATE_CALENDAR_EVENT",
                            "{\"appointmentId\":\"" + appt.getId() + "\"}", "403 Forbidden: " + errMessage, attempt + 1);
                    return;
                }

                attempt++;
                if (attempt < MAX_RETRIES) {
                    long backoff = BACKOFF_MS[attempt - 1];
                    log.warn("[GoogleWorker] Attempt {}/{} failed for appointmentId={}. Backing off for {}ms. Error: {}",
                            attempt, MAX_RETRIES, appt.getId(), backoff, ex.getMessage());
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // Exhausted retries -> Dead Letter queue
        log.error("[GoogleWorker] Max retries exceeded for appointmentId={}. Moving to dead letter queue.",
                appt.getId(), lastException);
        auditService.logFailure(host.getId(), "CALENDAR", "CREATE_EVENT", "calendar.googleapis.com",
                0L, lastException, attempt);
        recordDeadLetter(host.getId(), connectionId, "APPOINTMENT", appt.getId(), "CREATE_CALENDAR_EVENT",
                "{\"appointmentId\":\"" + appt.getId() + "\"}",
                lastException != null ? lastException.getMessage() : "Max retries exceeded", attempt);
    }

    /**
     * Listens for reminders created in CRM.
     * Synchronizes to Google Tasks asynchronously with auto-retry and audit logging.
     */
    @Async
    @EventListener
    public void onReminderCreated(ReminderCreatedEvent event) {
        if (event == null || event.getReminder() == null) return;

        Reminder reminder = event.getReminder();
        User owner = reminder.getOwner();
        if (owner == null || owner.getId() == null) return;

        Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(owner.getId());
        if (connOpt.isEmpty() || !tasksService.isConnected(owner.getId())) {
            log.debug("[GoogleWorker] User id={} has no active Google Tasks integration. Skipping reminderId={}",
                    owner.getId(), reminder.getId());
            return;
        }

        UUID connectionId = connOpt.get().getId();

        // Check if already synced
        Optional<GoogleSync> existingSync = syncRepository.findByConnectionIdAndResourceTypeAndCrmResourceId(
                connectionId, "TASK", reminder.getId()
        );
        if (existingSync.isPresent() && "SYNCED".equalsIgnoreCase(existingSync.get().getSyncStatus())) {
            log.debug("[GoogleWorker] ReminderId={} already synced to Google Tasks.", reminder.getId());
            return;
        }

        int attempt = 0;
        Exception lastException = null;

        while (attempt < MAX_RETRIES) {
            long startTime = System.currentTimeMillis();
            try {
                String leadName = (reminder.getLead() != null && reminder.getLead().getContact() != null)
                        ? reminder.getLead().getContact().getName() : "Lead Follow-up";
                String title = "CRM Follow-up: " + leadName;
                String notes = reminder.getMessage() != null ? reminder.getMessage() : "Scheduled CRM Reminder";

                tasksService.createTask(owner, title, notes, reminder.getDueDate(), reminder.getId());

                long latency = System.currentTimeMillis() - startTime;
                auditService.logApiCall(owner.getId(), connectionId, "TASKS", "CREATE_TASK",
                        "tasks.googleapis.com", "SUCCESS", latency, null, null, attempt);
                log.info("[GoogleWorker] Synced reminderId={} to Google Tasks for userId={}",
                        reminder.getId(), owner.getId());
                return;
            } catch (Exception ex) {
                lastException = ex;
                long latency = System.currentTimeMillis() - startTime;
                String errMessage = ex.getMessage();

                if (errMessage != null && errMessage.contains("401")) {
                    log.warn("[GoogleWorker] 401 Unauthorized for reminderId={}. Refreshing token...", reminder.getId());
                    try {
                        tokenService.getValidAccessToken(connectionId);
                    } catch (Exception tEx) {
                        log.error("[GoogleWorker] Token refresh failed: {}", tEx.getMessage());
                    }
                } else if (errMessage != null && errMessage.contains("403")) {
                    auditService.logApiCall(owner.getId(), connectionId, "TASKS", "CREATE_TASK",
                            "tasks.googleapis.com", "REAUTH_REQUIRED", latency, "403", errMessage, attempt);
                    recordDeadLetter(owner.getId(), connectionId, "REMINDER", reminder.getId(), "CREATE_TASK",
                            "{\"reminderId\":\"" + reminder.getId() + "\"}", "403 Forbidden: " + errMessage, attempt + 1);
                    return;
                }

                attempt++;
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(BACKOFF_MS[attempt - 1]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        auditService.logFailure(owner.getId(), "TASKS", "CREATE_TASK", "tasks.googleapis.com",
                0L, lastException, attempt);
        recordDeadLetter(owner.getId(), connectionId, "REMINDER", reminder.getId(), "CREATE_TASK",
                "{\"reminderId\":\"" + reminder.getId() + "\"}",
                lastException != null ? lastException.getMessage() : "Max retries exceeded", attempt);
    }

    private void recordDeadLetter(UUID userId, UUID connectionId, String resourceType, UUID crmResourceId,
                                  String operation, String payloadJson, String errorMessage, int retryCount) {
        try {
            UUID tenantId = null;
            if (connectionId != null) {
                tenantId = connectionRepository.findById(connectionId)
                        .map(GoogleConnection::getTenantId)
                        .orElse(null);
            }

            GoogleDeadLetter dl = new GoogleDeadLetter();
            dl.setTenantId(tenantId);
            dl.setUserId(userId);
            dl.setConnectionId(connectionId);
            dl.setResourceType(resourceType);
            dl.setCrmResourceId(crmResourceId);
            dl.setOperation(operation);
            dl.setPayloadJson(payloadJson);
            dl.setErrorMessage(errorMessage);
            dl.setRetryCount(retryCount);
            dl.setLastAttemptAt(LocalDateTime.now());
            dl.setResolved(false);

            deadLetterRepository.save(dl);
            log.warn("[GoogleWorker] Recorded dead letter for userId={} resourceType={} crmResourceId={}",
                    userId, resourceType, crmResourceId);
        } catch (Exception ex) {
            log.error("[GoogleWorker] Failed to write dead letter: {}", ex.getMessage());
        }
    }
}
