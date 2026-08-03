package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.services.CustomEmailService;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailCampaignJobTest {

    @Mock
    private CustomEmailService customEmailService;

    @Mock
    private CustomEmailRepository customEmailRepository;

    @Mock
    private EmailCampaignStateService stateService;

    @InjectMocks
    private EmailCampaignJob emailCampaignJob;

    @Test
    void testExecuteInternal_CallsStartCampaignExecution() throws JobExecutionException {
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap jobDataMap = new JobDataMap();
        UUID campaignId = UUID.randomUUID();
        jobDataMap.put("campaignId", campaignId.toString());

        when(context.getMergedJobDataMap()).thenReturn(jobDataMap);

        com.chatcrmlite.backend.models.CustomEmail campaign = new com.chatcrmlite.backend.models.CustomEmail();
        campaign.setId(campaignId);
        campaign.setStatus(com.chatcrmlite.backend.models.CustomEmail.EmailStatus.SCHEDULED);
        when(customEmailRepository.findById(campaignId)).thenReturn(java.util.Optional.of(campaign));

        emailCampaignJob.executeInternal(context);

        verify(stateService).transitionState(campaign, com.chatcrmlite.backend.models.CustomEmail.EmailStatus.SENDING, null);
        verify(customEmailService).startCampaignExecution(campaignId);
    }
}
