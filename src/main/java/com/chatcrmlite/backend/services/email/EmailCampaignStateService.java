package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailCampaignStateService {

    private final CustomEmailRepository customEmailRepository;
    private final EmailCampaignAuditService auditService;

    @Transactional
    public void transitionState(CustomEmail campaign, CustomEmail.EmailStatus newStatus, User actor) {
        CustomEmail.EmailStatus currentStatus = campaign.getStatus();
        
        if (currentStatus == newStatus) {
            return;
        }

        validateTransition(currentStatus, newStatus);

        campaign.setStatus(newStatus);
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        
        switch (newStatus) {
            case SCHEDULED:
                auditService.logAction(campaign, actor, com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.SCHEDULED, "{}");
                break;
            case SENDING:
                if (currentStatus != CustomEmail.EmailStatus.PAUSED) {
                    campaign.setStartedAt(now);
                }
                auditService.logAction(campaign, actor, 
                    currentStatus == CustomEmail.EmailStatus.PAUSED ? com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.RESUMED : com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.STARTED, "{}");
                break;
            case PAUSED:
                campaign.setPausedAt(now);
                auditService.logAction(campaign, actor, com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.PAUSED, "{}");
                break;
            case CANCELLED:
                campaign.setCancelledAt(now);
                auditService.logAction(campaign, actor, com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.CANCELLED, "{}");
                break;
            case COMPLETED:
                campaign.setCompletedAt(now);
                auditService.logAction(campaign, actor, com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.COMPLETED, "{}");
                break;
            case FAILED:
                auditService.logAction(campaign, actor, com.chatcrmlite.backend.models.email.EmailCampaignAuditLog.Action.FAILED, "{}");
                break;
            default:
                break;
        }
        
        customEmailRepository.save(campaign);
    }

    private void validateTransition(CustomEmail.EmailStatus current, CustomEmail.EmailStatus next) {
        boolean valid = switch (current) {
            case DRAFT -> next == CustomEmail.EmailStatus.SCHEDULED || next == CustomEmail.EmailStatus.SENDING || next == CustomEmail.EmailStatus.CANCELLED;
            case SCHEDULED -> next == CustomEmail.EmailStatus.SENDING || next == CustomEmail.EmailStatus.CANCELLED;
            case SENDING -> next == CustomEmail.EmailStatus.PAUSED || next == CustomEmail.EmailStatus.COMPLETED || next == CustomEmail.EmailStatus.CANCELLED || next == CustomEmail.EmailStatus.FAILED;
            case PAUSED -> next == CustomEmail.EmailStatus.SENDING || next == CustomEmail.EmailStatus.CANCELLED;
            case COMPLETED, CANCELLED, FAILED, SENT -> false; // Terminal states
        };

        if (!valid) {
            throw new IllegalStateException(String.format("Invalid email campaign state transition from %s to %s", current, next));
        }
    }
}
