package com.chatcrmlite.backend.services;

import com.chatcrmlite.backend.models.Reminder;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ReminderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ReminderService {

    @Autowired
    private ReminderRepository reminderRepository;

    public List<Reminder> getPendingReminders(User user) {
        return reminderRepository.findAllByOwnerAndIsCompletedFalse(user);
    }

    public Reminder createReminder(Reminder reminder) {
        return reminderRepository.save(reminder);
    }

    public Reminder completeReminder(UUID reminderId, User owner) {
        Reminder reminder = reminderRepository.findById(reminderId)
                .filter(r -> r.getOwner().getId().equals(owner.getId()))
                .orElseThrow(() -> new RuntimeException("Reminder not found"));
        reminder.setCompleted(true);
        return reminderRepository.save(reminder);
    }

    // Phase 1 Scheduled Task: Check for due reminders every minute
    @Scheduled(fixedRate = 60000)
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
