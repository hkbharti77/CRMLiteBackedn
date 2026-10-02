package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.google.GoogleConnection;
import com.chatcrmlite.backend.models.google.GoogleSync;
import com.chatcrmlite.backend.repositories.GoogleConnectionRepository;
import com.chatcrmlite.backend.repositories.GoogleSyncRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.ReminderRepository;
import com.chatcrmlite.backend.services.google.GoogleTasksService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private LeadRepository leadRepository;

    @Autowired(required = false)
    private GoogleTasksService googleTasksService;

    @Autowired(required = false)
    private GoogleConnectionRepository connectionRepository;

    @Autowired(required = false)
    private GoogleSyncRepository syncRepository;

    @Autowired
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    public List<Reminder> getPendingReminders(User user) {
        return reminderRepository.findAllByOwnerAndIsCompletedFalse(user);
    }

    public Reminder createReminder(Reminder reminder) {
        if (reminder.getLead() == null || reminder.getLead().getId() == null) {
            throw new IllegalArgumentException("Reminder must be associated with a lead");
        }
        Lead lead = leadRepository.findByIdAndTenantId(reminder.getLead().getId(), reminder.getOwner().getTenant().getId())
                .orElseThrow(() -> new RuntimeException("Lead not found"));
        reminder.setLead(lead);
        Reminder saved = reminderRepository.save(reminder);

        // Publish event for async worker (retries, dead-letter, audit log)
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new com.chatcrmlite.backend.event.ReminderCreatedEvent(this, saved));
            } catch (Exception ex) {
                log.warn("[ReminderService] Failed to publish ReminderCreatedEvent: {}", ex.getMessage());
            }
        }

        return saved;
    }

    public Reminder completeReminder(UUID reminderId, User caller) {
        Reminder reminder = reminderRepository.findByIdAndTenantId(reminderId, caller.getTenant().getId())
                .orElseThrow(() -> new RuntimeException("Reminder not found"));
        reminder.setCompleted(true);
        Reminder saved = reminderRepository.save(reminder);

        // Complete in Google Tasks if synced
        if (googleTasksService != null && connectionRepository != null && syncRepository != null && caller != null) {
            try {
                Optional<GoogleConnection> connOpt = connectionRepository.findActiveByUserId(caller.getId());
                if (connOpt.isPresent()) {
                    Optional<GoogleSync> syncOpt = syncRepository.findByConnectionIdAndResourceTypeAndCrmResourceId(
                            connOpt.get().getId(), "TASK", reminderId
                    );
                    if (syncOpt.isPresent() && syncOpt.get().getGoogleResourceId() != null) {
                        googleTasksService.completeTask(caller, syncOpt.get().getGoogleResourceId());
                        log.info("[ReminderService] Completed Google Task taskId={} for reminderId={}",
                                syncOpt.get().getGoogleResourceId(), reminderId);
                    }
                }
            } catch (Exception ex) {
                log.warn("[ReminderService] Google Task complete sync failed: {}", ex.getMessage());
            }
        }

        return saved;
    }

    // Phase 1 Scheduled Task: Check for due reminders every minute
    @Scheduled(fixedRate = 60000)
    @SchedulerLock(name = "ReminderService_checkDueReminders", lockAtMostFor = "50s", lockAtLeastFor = "30s")
    public void checkDueReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Reminder> dueReminders = reminderRepository.findAllByDueDateBeforeAndIsCompletedFalse(now);
        
        for (Reminder reminder : dueReminders) {
            // In Phase 2, this would trigger a WhatsApp message or Push Notification
            System.out.println("REMINDER DUE: " + reminder.getMessage() + " for lead ID: " + reminder.getLead().getId());
            // Marking as completed for now to avoid multiple triggers in MVP
            reminder.setCompleted(true);
            reminderRepository.save(reminder);
        }
    }
}
