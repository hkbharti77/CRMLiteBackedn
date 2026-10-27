package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.dto.EmailAbMetricsDto;
import com.chatcrmlite.backend.dto.EmailVariantInput;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignRecipient;
import com.chatcrmlite.backend.models.email.EmailCampaignVariant;
import com.chatcrmlite.backend.repositories.CustomEmailRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignRecipientRepository;
import com.chatcrmlite.backend.repositories.email.EmailCampaignVariantRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailAbTestingServiceTest {

    @Mock
    private CustomEmailRepository campaignRepository;

    @Mock
    private EmailCampaignVariantRepository variantRepository;

    @Mock
    private EmailCampaignRecipientRepository recipientRepository;

    @Mock
    private DistributedSchedulerService distributedSchedulerService;

    @Mock
    private CustomEmailService customEmailService;

    private EmailAbTestingService abTestingService;

    private Tenant tenant;
    private User owner;
    private CustomEmail campaign;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        abTestingService = new EmailAbTestingService(
                campaignRepository,
                variantRepository,
                recipientRepository,
                distributedSchedulerService,
                customEmailService
        );

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setTimezone("Asia/Kolkata");

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("growth@yourcrm.com");
        owner.setTenant(tenant);

        campaign = CustomEmail.builder()
                .id(UUID.randomUUID())
                .name("Q4 Product Launch A/B Test")
                .subject("Introducing our newest workspace feature")
                .body("<p>Default body</p>")
                .isAbTest(true)
                .abTestPercentage(20)
                .abWinnerMetric("OPEN_RATE")
                .abEvaluationHours(4)
                .abStatus("DRAFT")
                .owner(owner)
                .build();
        campaign.setTenant(tenant);
    }

    @Test
    @DisplayName("createVariants: successfully creates Variant A and B with subject and body overrides")
    void testCreateVariants_Success() {
        EmailVariantInput inputA = EmailVariantInput.builder()
                .variantCode("A")
                .name("Variant A (Curiosity Subject)")
                .subject("Quick question about your workspace?")
                .body("<h1>Take a look at this update</h1>")
                .builderJson("{\"blocks\":[]}")
                .build();

        EmailVariantInput inputB = EmailVariantInput.builder()
                .variantCode("B")
                .name("Variant B (Direct Value Subject)")
                .subject("Save 10 hours a week with automation")
                .body("<h1>Direct value proposal inside</h1>")
                .builderJson("{\"blocks\":[]}")
                .build();

        when(variantRepository.save(any(EmailCampaignVariant.class))).thenAnswer(inv -> inv.getArgument(0));

        List<EmailCampaignVariant> created = abTestingService.createVariantsForCampaign(
                campaign, List.of(inputA, inputB), owner
        );

        assertNotNull(created);
        assertEquals(2, created.size());
        assertEquals("A", created.get(0).getVariantCode());
        assertEquals("Quick question about your workspace?", created.get(0).getSubject());
        assertEquals("B", created.get(1).getVariantCode());
        assertEquals("Save 10 hours a week with automation", created.get(1).getSubject());

        verify(variantRepository, times(2)).save(any(EmailCampaignVariant.class));
    }

    @Test
    @DisplayName("createVariants: throws exception when fewer than 2 variants provided")
    void testCreateVariants_ThrowsWhenLessThanTwo() {
        EmailVariantInput inputA = EmailVariantInput.builder().variantCode("A").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                abTestingService.createVariantsForCampaign(campaign, List.of(inputA), owner)
        );
        assertTrue(ex.getMessage().contains("requires at least 2 variants"));
    }

    @Test
    @DisplayName("partitionAudience: correctly partitions 100 recipients into 20% test group and 80% holdout group")
    void testPartitionAudience_PartitioningDistribution() {
        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .variantCode("A")
                .sampleCount(0)
                .build();

        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .variantCode("B")
                .sampleCount(0)
                .build();

        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));

        // Create 100 valid recipients
        List<EmailCampaignRecipient> recipients = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            recipients.add(EmailCampaignRecipient.builder()
                    .id(UUID.randomUUID())
                    .campaignId(campaign.getId())
                    .email("user" + i + "@example.com")
                    .build());
        }

        abTestingService.partitionAudience(campaign, recipients);

        // Verify counts
        long testCount = recipients.stream().filter(r -> Boolean.FALSE.equals(r.getIsHoldout())).count();
        long holdoutCount = recipients.stream().filter(r -> Boolean.TRUE.equals(r.getIsHoldout())).count();

        assertEquals(20, testCount, "Test sample should be exactly 20% of 100");
        assertEquals(80, holdoutCount, "Holdout group should be exactly 80% of 100");

        // Verify variant distribution within test group
        long countA = recipients.stream().filter(r -> varA.getId().equals(r.getVariantId())).count();
        long countB = recipients.stream().filter(r -> varB.getId().equals(r.getVariantId())).count();

        assertEquals(10, countA, "Variant A should receive 10 test recipients");
        assertEquals(10, countB, "Variant B should receive 10 test recipients");

        assertEquals(10, varA.getSampleCount());
        assertEquals(10, varB.getSampleCount());

        verify(variantRepository).saveAll(anyList());
        verify(recipientRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("scheduleEvaluationJob: schedules Quartz job with duration and campaignId in JobDataMap")
    void testScheduleEvaluationJob() {
        LocalDateTime startTime = LocalDateTime.now();
        abTestingService.scheduleEvaluationJob(campaign, startTime);

        assertEquals("TESTING", campaign.getAbStatus());
        assertNotNull(campaign.getAbEvaluationEndsAt());

        verify(campaignRepository).save(campaign);
        verify(distributedSchedulerService).scheduleOneTimeJob(
                eq("email-ab-winner-" + campaign.getId()),
                eq(EmailAbWinnerJob.class),
                any(Date.class),
                argThat(map -> campaign.getId().toString().equals(map.get("campaignId")))
        );
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: auto-evaluation with OPEN_RATE promotes variant with higher open rate")
    void testEvaluateAndPromoteWinner_OpenRateWinner() {
        campaign.setAbWinnerMetric("OPEN_RATE");
        campaign.setAbStatus("TESTING");

        // Variant A: 10 sent, 3 opened = 30.0% open rate
        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("A")
                .sentCount(10)
                .openedCount(3)
                .clickedCount(1)
                .build();

        // Variant B: 10 sent, 7 opened = 70.0% open rate
        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("B")
                .sentCount(10)
                .openedCount(7)
                .clickedCount(2)
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));
        when(recipientRepository.releaseHoldoutToWinner(eq(campaign.getId()), eq(varB.getId()), any()))
                .thenReturn(80);

        EmailCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), null, owner);

        assertNotNull(winner);
        assertEquals(varB.getId(), winner.getId());
        assertEquals("B", winner.getVariantCode());
        assertTrue(varB.isWinner());
        assertFalse(varA.isWinner());
        assertEquals("WINNER_PROMOTED", campaign.getAbStatus());
        assertEquals(varB.getId(), campaign.getWinningVariantId());

        // Verifies holdout release was called with winner variant ID
        verify(recipientRepository).releaseHoldoutToWinner(
                eq(campaign.getId()), eq(varB.getId()), eq(EmailCampaignRecipient.DeliveryStatus.PENDING)
        );
        // Verifies holdout dispatch was triggered
        verify(customEmailService).startCampaignExecution(campaign.getId());
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: auto-evaluation with CLICK_RATE promotes variant with higher CTR")
    void testEvaluateAndPromoteWinner_ClickRateWinner() {
        campaign.setAbWinnerMetric("CLICK_RATE");
        campaign.setAbStatus("TESTING");

        // Variant A: 20 sent, 8 opened, 4 clicked = 20.0% CTR
        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("A")
                .sentCount(20)
                .openedCount(8)
                .clickedCount(4)
                .build();

        // Variant B: 20 sent, 10 opened, 2 clicked = 10.0% CTR
        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("B")
                .sentCount(20)
                .openedCount(10)
                .clickedCount(2)
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));
        when(recipientRepository.releaseHoldoutToWinner(eq(campaign.getId()), eq(varA.getId()), any()))
                .thenReturn(50);

        EmailCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), null, owner);

        assertNotNull(winner);
        assertEquals(varA.getId(), winner.getId());
        assertEquals("A", winner.getVariantCode());
        assertTrue(varA.isWinner());
        assertFalse(varB.isWinner());
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: tie-breaker defaults deterministically to Variant A")
    void testEvaluateAndPromoteWinner_TieBreakerDefaultsToA() {
        campaign.setAbWinnerMetric("OPEN_RATE");
        campaign.setAbStatus("TESTING");

        // Both variants identical open rate (50%) and identical CTR (20%)
        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("A")
                .sentCount(10)
                .openedCount(5)
                .clickedCount(2)
                .build();

        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("B")
                .sentCount(10)
                .openedCount(5)
                .clickedCount(2)
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));

        EmailCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), null, owner);

        assertEquals("A", winner.getVariantCode(), "Tie-breaker should pick Variant A");
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: manual winner promotion overrides auto evaluation")
    void testEvaluateAndPromoteWinner_ManualOverride() {
        campaign.setAbWinnerMetric("OPEN_RATE");
        campaign.setAbStatus("TESTING");

        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("A")
                .sentCount(10)
                .openedCount(8) // Higher open rate
                .build();

        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("B")
                .sentCount(10)
                .openedCount(2) // Lower open rate
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));

        // User manually promotes Variant B
        EmailCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), varB.getId(), owner);

        assertEquals(varB.getId(), winner.getId());
        assertEquals("B", winner.getVariantCode());
        assertTrue(varB.isWinner());
    }

    @Test
    @DisplayName("getAbMetrics: returns live comparative metrics with correct leader flag")
    void testGetAbMetrics_ComparativeTelemetry() {
        campaign.setAbWinnerMetric("OPEN_RATE");
        campaign.setAbStatus("TESTING");
        campaign.setAbEvaluationEndsAt(LocalDateTime.now().plusHours(2));

        EmailCampaignVariant varA = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("A")
                .name("Control")
                .sampleCount(50)
                .sentCount(50)
                .deliveredCount(48)
                .openedCount(24) // 50%
                .clickedCount(6)  // 12.5%
                .isWinner(false)
                .build();

        EmailCampaignVariant varB = EmailCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantCode("B")
                .name("Challenger")
                .sampleCount(50)
                .sentCount(50)
                .deliveredCount(48)
                .openedCount(36) // 75% -> LEADER
                .clickedCount(12) // 25%
                .isWinner(false)
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignIdOrderByVariantCodeAsc(campaign.getId()))
                .thenReturn(List.of(varA, varB));
        when(recipientRepository.countByCampaignIdAndIsHoldout(campaign.getId(), true)).thenReturn(400L);
        when(recipientRepository.countByCampaignId(campaign.getId())).thenReturn(500L);

        EmailAbMetricsDto metrics = abTestingService.getAbMetrics(campaign.getId(), owner);

        assertNotNull(metrics);
        assertEquals(campaign.getId(), metrics.getCampaignId());
        assertEquals("TESTING", metrics.getAbStatus());
        assertEquals(500, metrics.getTotalAudience());
        assertEquals(100, metrics.getTestSampleCount());
        assertEquals(400, metrics.getHoldoutCount());
        assertEquals(2, metrics.getVariants().size());

        // Variant B is leader
        assertTrue(metrics.getVariants().get(1).isLeader());
        assertFalse(metrics.getVariants().get(0).isLeader());
        assertEquals(75.0, metrics.getVariants().get(1).getOpenRate());
        assertEquals(50.0, metrics.getVariants().get(0).getOpenRate());
    }
}
