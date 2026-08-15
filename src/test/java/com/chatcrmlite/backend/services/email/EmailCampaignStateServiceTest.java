package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignAuditLog;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailCampaignStateServiceTest {

    @Mock
    private CustomEmailRepository customEmailRepository;

    @Mock
    private EmailCampaignAuditService auditService;

    @InjectMocks
    private EmailCampaignStateService stateService;

    private CustomEmail campaign;
    private User actor;

    @BeforeEach
    void setUp() {
        campaign = new CustomEmail();
        campaign.setId(UUID.randomUUID());
        campaign.setStatus(CustomEmail.EmailStatus.DRAFT);

        actor = new User();
        actor.setId(UUID.randomUUID());
    }

    @Test
    void testValidTransition_DraftToScheduled() {
        stateService.transitionState(campaign, CustomEmail.EmailStatus.SCHEDULED, actor);

        assertEquals(CustomEmail.EmailStatus.SCHEDULED, campaign.getStatus());
        verify(auditService).logAction(eq(campaign), eq(actor), eq(EmailCampaignAuditLog.Action.SCHEDULED), any());
        verify(customEmailRepository).save(campaign);
    }

    @Test
    void testValidTransition_ScheduledToSending() {
        campaign.setStatus(CustomEmail.EmailStatus.SCHEDULED);
        stateService.transitionState(campaign, CustomEmail.EmailStatus.SENDING, actor);

        assertEquals(CustomEmail.EmailStatus.SENDING, campaign.getStatus());
        assertNotNull(campaign.getStartedAt());
        verify(auditService).logAction(eq(campaign), eq(actor), eq(EmailCampaignAuditLog.Action.STARTED), any());
        verify(customEmailRepository).save(campaign);
    }

    @Test
    void testValidTransition_SendingToPaused() {
        campaign.setStatus(CustomEmail.EmailStatus.SENDING);
        stateService.transitionState(campaign, CustomEmail.EmailStatus.PAUSED, actor);

        assertEquals(CustomEmail.EmailStatus.PAUSED, campaign.getStatus());
        assertNotNull(campaign.getPausedAt());
        verify(auditService).logAction(eq(campaign), eq(actor), eq(EmailCampaignAuditLog.Action.PAUSED), any());
        verify(customEmailRepository).save(campaign);
    }

    @Test
    void testValidTransition_PausedToSending() {
        campaign.setStatus(CustomEmail.EmailStatus.PAUSED);
        stateService.transitionState(campaign, CustomEmail.EmailStatus.SENDING, actor);

        assertEquals(CustomEmail.EmailStatus.SENDING, campaign.getStatus());
        verify(auditService).logAction(eq(campaign), eq(actor), eq(EmailCampaignAuditLog.Action.RESUMED), any());
        verify(customEmailRepository).save(campaign);
    }

    @Test
    void testInvalidTransition_ThrowsException() {
        campaign.setStatus(CustomEmail.EmailStatus.COMPLETED);

        assertThrows(IllegalStateException.class, () -> {
            stateService.transitionState(campaign, CustomEmail.EmailStatus.SENDING, actor);
        });

        verifyNoInteractions(auditService);
        verifyNoInteractions(customEmailRepository);
    }
}
