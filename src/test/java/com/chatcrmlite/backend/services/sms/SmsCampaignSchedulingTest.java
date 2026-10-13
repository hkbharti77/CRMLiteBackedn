package com.chatcrmlite.backend.services.sms;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Message;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.sms.SmsCampaign;
import com.chatcrmlite.backend.models.sms.SmsMessage;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.chatcrmlite.backend.services.websocket.DistributedWebSocketPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsCampaignSchedulingTest {

    @Mock private SmsCampaignRepository campaignRepository;
    @Mock private SmsCampaignRecipientRepository recipientRepository;
    @Mock private SmsTemplateRepository templateRepository;
    @Mock private SmsService smsService;
    @Mock private DistributedSchedulerService distributedSchedulerService;

    // Inbound Service mocks
    @Mock private MessageRepository messageRepository;
    @Mock private ContactRepository contactRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private UserRepository userRepository;
    @Mock private SmsMessageRepository smsMessageRepository;
    @Mock private DistributedWebSocketPublisher webSocketPublisher;

    @InjectMocks private SmsCampaignService campaignService;
    private SmsInboundMessageService inboundMessageService;
    private SmsCampaignJob campaignJob;

    private UUID tenantId;
    private String businessId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        businessId = tenantId.toString();
        inboundMessageService = new SmsInboundMessageService(
                messageRepository, contactRepository, tenantRepository, userRepository, smsMessageRepository, webSocketPublisher
        );
        campaignJob = new SmsCampaignJob(campaignRepository, campaignService);
    }

    @Test
    @DisplayName("scheduleCampaign should update status to SCHEDULED and register Quartz trigger")
    void testScheduleCampaignSuccess() {
        UUID campaignId = UUID.randomUUID();
        SmsCampaign campaign = new SmsCampaign();
        campaign.setId(campaignId);
        campaign.setName("Summer Promo");
        campaign.setStatus("DRAFT");
        campaign.setTenantId(tenantId);
        campaign.setBusinessId(businessId);

        when(campaignRepository.findByIdAndTenant_Id(campaignId, tenantId)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(SmsCampaign.class))).thenAnswer(i -> i.getArgument(0));

        LocalDateTime futureTime = LocalDateTime.now().plusHours(2);
        SmsCampaign scheduled = campaignService.scheduleCampaign(campaignId, futureTime, businessId);

        assertNotNull(scheduled);
        assertEquals("SCHEDULED", scheduled.getStatus());
        assertEquals(futureTime, scheduled.getScheduledAt());

        verify(distributedSchedulerService).cancelJob("sms-campaign-job-" + campaignId);
        verify(distributedSchedulerService).scheduleOneTimeJob(
                eq("sms-campaign-job-" + campaignId),
                eq(SmsCampaignJob.class),
                any(Date.class),
                argThat(map -> campaignId.toString().equals(map.get("campaignId")))
        );
    }

    @Test
    @DisplayName("scheduleCampaign rejects scheduling in past or under 60 seconds")
    void testScheduleCampaignRejectsPast() {
        UUID campaignId = UUID.randomUUID();
        SmsCampaign campaign = new SmsCampaign();
        campaign.setId(campaignId);
        campaign.setStatus("DRAFT");

        when(campaignRepository.findByIdAndTenant_Id(campaignId, tenantId)).thenReturn(Optional.of(campaign));

        LocalDateTime invalidTime = LocalDateTime.now().plusSeconds(30);
        assertThrows(ResponseStatusException.class, () ->
                campaignService.scheduleCampaign(campaignId, invalidTime, businessId)
        );
    }

    @Test
    @DisplayName("cancelCampaign deschedules Quartz trigger and sets status to CANCELLED")
    void testCancelCampaignSuccess() {
        UUID campaignId = UUID.randomUUID();
        SmsCampaign campaign = new SmsCampaign();
        campaign.setId(campaignId);
        campaign.setStatus("SCHEDULED");
        campaign.setScheduledAt(LocalDateTime.now().plusHours(5));

        when(campaignRepository.findByIdAndTenant_Id(campaignId, tenantId)).thenReturn(Optional.of(campaign));
        when(campaignRepository.save(any(SmsCampaign.class))).thenAnswer(i -> i.getArgument(0));

        SmsCampaign cancelled = campaignService.cancelCampaign(campaignId, businessId);

        assertNotNull(cancelled);
        assertEquals("CANCELLED", cancelled.getStatus());
        assertNotNull(cancelled.getCancelledAt());
        verify(distributedSchedulerService).cancelJob("sms-campaign-job-" + campaignId);
    }

    @Test
    @DisplayName("SmsCampaignJob executes scheduled campaign via Quartz callback")
    void testSmsCampaignJobExecution() throws Exception {
        UUID campaignId = UUID.randomUUID();
        SmsCampaign campaign = new SmsCampaign();
        campaign.setId(campaignId);
        campaign.setStatus("SCHEDULED");

        when(campaignRepository.findById(campaignId)).thenReturn(Optional.of(campaign));
        when(campaignRepository.findByIdAndTenant_Id(campaignId, tenantId)).thenReturn(Optional.of(campaign));
        when(recipientRepository.findByCampaignId(campaignId)).thenReturn(Collections.emptyList());

        JobExecutionContext context = mock(JobExecutionContext.class);
        JobDataMap dataMap = new JobDataMap(Map.of("campaignId", campaignId.toString(), "businessId", businessId));
        when(context.getMergedJobDataMap()).thenReturn(dataMap);

        campaignJob.execute(context);

        // Verify status transitioned
        verify(campaignRepository, atLeastOnce()).save(argThat(c -> "PROCESSING".equals(c.getStatus()) || "COMPLETED".equals(c.getStatus())));
    }

    @Test
    @DisplayName("Inbound SMS is persisted as Message and published to WebSocket")
    void testInboundSmsProcessing() {
        String fromPhone = "+919876543210";
        String toPhone = "+919000000000";
        String messageId = "msg-twilio-abc123";
        String body = "Yes, please send me details about the new product!";

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);
        User owner = new User();
        owner.setId(UUID.randomUUID());

        when(userRepository.findFirstByTenantIdAndRole(eq(tenantId), any())).thenReturn(Optional.of(owner));
        when(messageRepository.findByWaMessageId("sms_" + messageId)).thenReturn(Optional.empty());
        when(smsMessageRepository.findFirstByPhoneNumberOrderByCreatedAtDesc(fromPhone)).thenReturn(Optional.empty());
        when(contactRepository.findByWaId(fromPhone)).thenReturn(Optional.empty());
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));

        when(contactRepository.findByTenantIdAndWaId(tenantId, fromPhone)).thenReturn(Optional.empty());
        Contact savedContact = new Contact();
        savedContact.setId(UUID.randomUUID());
        savedContact.setWaId(fromPhone);
        savedContact.setTenant(tenant);
        savedContact.setOwner(owner);
        when(contactRepository.save(any(Contact.class))).thenReturn(savedContact);

        when(messageRepository.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId(UUID.randomUUID());
            return m;
        });

        Message processed = inboundMessageService.processInboundSms("TWILIO", fromPhone, toPhone, messageId, body);

        assertNotNull(processed);
        assertEquals("sms_" + messageId, processed.getWaMessageId());
        assertEquals(body, processed.getContent());
        assertEquals(Message.Direction.INCOMING, processed.getDirection());
        assertEquals(Message.DeliveryStatus.DELIVERED, processed.getDeliveryStatus());

        verify(webSocketPublisher).publish(eq(tenantId), eq("/topic/chat/" + savedContact.getId()), any());
        verify(webSocketPublisher).publish(eq(tenantId), eq("/topic/inbox/" + tenantId), any());
    }
}
