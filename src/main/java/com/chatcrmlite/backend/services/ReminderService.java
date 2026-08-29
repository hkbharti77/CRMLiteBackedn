package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.ReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ReminderService {

    @Autowired
    private ReminderRepository reminderRepository;

    @Autowired
    private LeadRepository leadRepository;

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
        return reminderRepository.save(reminder);
    }

    public Reminder completeReminder(UUID reminderId, User caller) {
        Reminder reminder = reminderRepository.findByIdAndTenantId(reminderId, caller.getTenant().getId())
                .orElseThrow(() -> new RuntimeException("Reminder not found"));
        reminder.setCompleted(true);
        return reminderRepository.save(reminder);
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
