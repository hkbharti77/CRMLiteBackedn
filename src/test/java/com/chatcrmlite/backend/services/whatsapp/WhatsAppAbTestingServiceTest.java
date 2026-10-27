package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dtos.CampaignAbMetricsDto;
import com.chatcrmlite.backend.dtos.CampaignVariantDto;
import com.chatcrmlite.backend.models.*;
import com.chatcrmlite.backend.repositories.*;
import com.chatcrmlite.backend.services.DistributedSchedulerService;
import com.chatcrmlite.backend.services.whatsapp.campaign.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WhatsAppAbTestingServiceTest {

    @Mock
    private WhatsAppCampaignRepository campaignRepository;

    @Mock
    private WhatsAppCampaignVariantRepository variantRepository;

    @Mock
    private WhatsAppCampaignRecipientRepository recipientRepository;

    @Mock
    private WhatsAppTemplateRepository templateRepository;

    @Mock
    private WhatsAppTemplateSnapshotRepository templateSnapshotRepository;

    @Mock
    private PersonalizationEngine personalizationEngine;

    @Mock
    private LeadRepository leadRepository;

    @Mock
    private CampaignQueueProducer queueProducer;

    @Mock
    private DistributedSchedulerService distributedSchedulerService;

    @Mock
    private CampaignAuditService auditService;

    private ObjectMapper objectMapper;
    private WhatsAppAbTestingService abTestingService;

    private Tenant tenant;
    private User owner;
    private WhatsAppCampaign campaign;
    private WhatsAppTemplate templateA;
    private WhatsAppTemplate templateB;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();

        abTestingService = new WhatsAppAbTestingService(
                campaignRepository,
                variantRepository,
                recipientRepository,
                templateRepository,
                templateSnapshotRepository,
                personalizationEngine,
                leadRepository,
                queueProducer,
                distributedSchedulerService,
                auditService,
                objectMapper
        );

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());

        owner = new User();
        owner.setId(UUID.randomUUID());
        owner.setEmail("marketer@example.com");
        owner.setTenant(tenant);

        campaign = WhatsAppCampaign.builder()
                .id(UUID.randomUUID())
                .name("Diwali Offer A/B Experiment")
                .isAbTest(true)
                .abTestPercentage(20)
                .abWinnerMetric("CLICK_RATE")
                .abEvaluationHours(4)
                .abStatus("TESTING")
                .owner(owner)
                .build();
        campaign.setTenant(tenant);

        templateA = WhatsAppTemplate.builder()
                .id(UUID.randomUUID())
                .name("diwali_promo_formal")
                .language("en_US")
                .category("MARKETING")
                .bodyText("Dear {{1}}, get 20% off today!")
                .owner(owner)
                .build();
        templateA.setTenant(tenant);

        templateB = WhatsAppTemplate.builder()
                .id(UUID.randomUUID())
                .name("diwali_promo_casual")
                .language("en_US")
                .category("MARKETING")
                .bodyText("Hey {{1}}! 🎉 Flash sale inside!")
                .owner(owner)
                .build();
        templateB.setTenant(tenant);
    }

    @Test
    @DisplayName("createVariants: successfully creates Variant A and B with snapshots")
    void testCreateVariants_Success() {
        CampaignVariantDto dtoA = CampaignVariantDto.builder()
                .variantLabel("A")
                .templateId(templateA.getId().toString())
                .variableMappingJson("{\"1\": \"contact.name\"}")
                .samplePercentage(50)
                .build();

        CampaignVariantDto dtoB = CampaignVariantDto.builder()
                .variantLabel("B")
                .templateId(templateB.getId().toString())
                .variableMappingJson("{\"1\": \"contact.name\"}")
                .samplePercentage(50)
                .build();

        when(templateRepository.findById(templateA.getId())).thenReturn(Optional.of(templateA));
        when(templateRepository.findById(templateB.getId())).thenReturn(Optional.of(templateB));
        when(templateSnapshotRepository.findFirstByOriginalTemplateIdOrderByVersionDesc(any())).thenReturn(Optional.empty());
        when(templateSnapshotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(variantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<WhatsAppCampaignVariant> created = abTestingService.createVariantsForCampaign(
                campaign,
                List.of(dtoA, dtoB),
                owner
        );

        assertEquals(2, created.size());
        assertEquals("A", created.get(0).getVariantLabel());
        assertEquals("B", created.get(1).getVariantLabel());
        assertEquals("diwali_promo_formal", created.get(0).getTemplateName());
        assertEquals("diwali_promo_casual", created.get(1).getTemplateName());
        verify(variantRepository, times(2)).save(any(WhatsAppCampaignVariant.class));
    }

    @Test
    @DisplayName("createVariants: throws exception if fewer than 2 variants are provided")
    void testCreateVariants_LessThanTwoVariants_ThrowsException() {
        CampaignVariantDto singleDto = CampaignVariantDto.builder()
                .variantLabel("A")
                .templateId(templateA.getId().toString())
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                abTestingService.createVariantsForCampaign(campaign, List.of(singleDto), owner)
        );
    }

    @Test
    @DisplayName("partitionAudience: correctly partitions test group and holdout group without overlap")
    void testPartitionAudience_CorrectDistribution() {
        WhatsAppCampaignVariant varA = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("A")
                .templateName("template_a")
                .variableMappingJson("{}")
                .sampleCount(0)
                .build();
        varA.setTenant(tenant);

        WhatsAppCampaignVariant varB = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("B")
                .templateName("template_b")
                .variableMappingJson("{}")
                .sampleCount(0)
                .build();
        varB.setTenant(tenant);

        when(variantRepository.findByCampaignOrderByVariantLabelAsc(campaign))
                .thenReturn(List.of(varA, varB));

        // Create 100 sample recipients
        List<WhatsAppCampaignRecipient> recipients = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            WhatsAppCampaignRecipient r = WhatsAppCampaignRecipient.builder()
                    .id(UUID.randomUUID())
                    .campaign(campaign)
                    .phoneNumber("+9198765432" + String.format("%02d", i))
                    .status(WhatsAppCampaignRecipient.RecipientStatus.PENDING)
                    .build();
            r.setTenant(tenant);
            recipients.add(r);
        }

        abTestingService.partitionAudience(campaign, recipients);

        // 20% of 100 = 20 test recipients, 80 holdout recipients
        long holdoutCount = recipients.stream().filter(WhatsAppCampaignRecipient::getIsHoldout).count();
        long testCount = recipients.stream().filter(r -> !r.getIsHoldout()).count();

        assertEquals(80, holdoutCount);
        assertEquals(20, testCount);

        // Variant distribution in test group
        long countA = recipients.stream().filter(r -> !r.getIsHoldout() && r.getVariant() == varA).count();
        long countB = recipients.stream().filter(r -> !r.getIsHoldout() && r.getVariant() == varB).count();

        assertEquals(10, countA);
        assertEquals(10, countB);
        assertEquals(10, varA.getSampleCount());
        assertEquals(10, varB.getSampleCount());
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: promotes variant with highest CTR under CLICK_RATE metric")
    void testEvaluateAndPromoteWinner_HighestCtr() {
        WhatsAppCampaignVariant varA = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("A")
                .templateName("template_a")
                .variableMappingJson("{}")
                .sampleCount(100)
                .sentCount(100)
                .deliveredCount(90)
                .readCount(50)
                .clickCount(5) // CTR = 5 / 90 = 5.55%
                .isWinner(false)
                .build();
        varA.setTenant(tenant);

        WhatsAppCampaignVariant varB = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("B")
                .templateName("template_b")
                .variableMappingJson("{}")
                .sampleCount(100)
                .sentCount(100)
                .deliveredCount(90)
                .readCount(40)
                .clickCount(18) // CTR = 18 / 90 = 20.0%
                .isWinner(false)
                .build();
        varB.setTenant(tenant);

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignOrderByVariantLabelAsc(campaign)).thenReturn(List.of(varA, varB));

        // Create 2 holdout recipients
        WhatsAppCampaignRecipient h1 = WhatsAppCampaignRecipient.builder()
                .id(UUID.randomUUID()).campaign(campaign).isHoldout(true).build();
        WhatsAppCampaignRecipient h2 = WhatsAppCampaignRecipient.builder()
                .id(UUID.randomUUID()).campaign(campaign).isHoldout(true).build();
        when(recipientRepository.findByCampaignAndIsHoldout(campaign, true)).thenReturn(List.of(h1, h2));

        WhatsAppCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), null, owner);

        assertNotNull(winner);
        assertEquals("B", winner.getVariantLabel());
        assertTrue(winner.getIsWinner());
        assertEquals(winner.getId(), campaign.getWinningVariantId());
        assertEquals("WINNER_PROMOTED", campaign.getAbStatus());

        // Verify holdout recipients were assigned winning variant and enqueued
        assertFalse(h1.getIsHoldout());
        assertEquals(winner, h1.getVariant());
        assertFalse(h2.getIsHoldout());
        assertEquals(winner, h2.getVariant());
        verify(queueProducer, times(1)).queueHoldoutRecipients(eq(campaign), anyList());
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: promotes variant with highest Read Rate under READ_RATE metric")
    void testEvaluateAndPromoteWinner_HighestReadRate() {
        campaign.setAbWinnerMetric("READ_RATE");

        WhatsAppCampaignVariant varA = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("A")
                .templateName("template_a")
                .variableMappingJson("{}")
                .deliveredCount(100)
                .readCount(75) // Read Rate = 75%
                .clickCount(2)
                .isWinner(false)
                .build();
        varA.setTenant(tenant);

        WhatsAppCampaignVariant varB = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("B")
                .templateName("template_b")
                .variableMappingJson("{}")
                .deliveredCount(100)
                .readCount(40) // Read Rate = 40%
                .clickCount(10)
                .isWinner(false)
                .build();
        varB.setTenant(tenant);

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignOrderByVariantLabelAsc(campaign)).thenReturn(List.of(varA, varB));
        when(recipientRepository.findByCampaignAndIsHoldout(campaign, true)).thenReturn(Collections.emptyList());

        WhatsAppCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), null, owner);

        assertEquals("A", winner.getVariantLabel());
        assertTrue(varA.getIsWinner());
        assertFalse(varB.getIsWinner());
    }

    @Test
    @DisplayName("evaluateAndPromoteWinner: supports manual chosen variant override")
    void testEvaluateAndPromoteWinner_ManualOverride() {
        WhatsAppCampaignVariant varA = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("A")
                .templateName("template_a")
                .variableMappingJson("{}")
                .deliveredCount(100)
                .clickCount(20)
                .isWinner(false)
                .build();
        varA.setTenant(tenant);

        WhatsAppCampaignVariant varB = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("B")
                .templateName("template_b")
                .variableMappingJson("{}")
                .deliveredCount(100)
                .clickCount(2)
                .isWinner(false)
                .build();
        varB.setTenant(tenant);

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignOrderByVariantLabelAsc(campaign)).thenReturn(List.of(varA, varB));
        when(recipientRepository.findByCampaignAndIsHoldout(campaign, true)).thenReturn(Collections.emptyList());

        // Manually select B even though A has higher CTR
        WhatsAppCampaignVariant winner = abTestingService.evaluateAndPromoteWinner(campaign.getId(), varB.getId(), owner);

        assertEquals("B", winner.getVariantLabel());
        assertTrue(varB.getIsWinner());
        assertFalse(varA.getIsWinner());
    }

    @Test
    @DisplayName("getAbMetrics: computes rates, leader, and remaining evaluation time")
    void testGetAbMetrics_ComputesTelemetry() {
        campaign.setAbEvaluationEndsAt(LocalDateTime.now().plusHours(3));

        WhatsAppCampaignVariant varA = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("A")
                .templateName("template_a")
                .sampleCount(50)
                .sentCount(50)
                .deliveredCount(50)
                .readCount(25)
                .clickCount(10)
                .isWinner(false)
                .build();

        WhatsAppCampaignVariant varB = WhatsAppCampaignVariant.builder()
                .id(UUID.randomUUID())
                .campaign(campaign)
                .variantLabel("B")
                .templateName("template_b")
                .sampleCount(50)
                .sentCount(50)
                .deliveredCount(50)
                .readCount(30)
                .clickCount(2)
                .isWinner(false)
                .build();

        when(campaignRepository.findById(campaign.getId())).thenReturn(Optional.of(campaign));
        when(variantRepository.findByCampaignOrderByVariantLabelAsc(campaign)).thenReturn(List.of(varA, varB));
        when(recipientRepository.countByCampaignAndIsHoldout(campaign, true)).thenReturn(400L);
        when(recipientRepository.countByCampaignId(campaign.getId())).thenReturn(500L);

        CampaignAbMetricsDto metrics = abTestingService.getAbMetrics(campaign.getId(), owner);

        assertNotNull(metrics);
        assertEquals(500, metrics.getTotalAudience());
        assertEquals(100, metrics.getTestSampleCount());
        assertEquals(400, metrics.getHoldoutCount());
        assertEquals(2, metrics.getVariants().size());

        // Variant A: CTR = 10 / 50 = 20% -> Leader under CLICK_RATE metric
        CampaignAbMetricsDto.VariantMetricsDto dtoA = metrics.getVariants().get(0);
        assertTrue(dtoA.isLeader());
        assertEquals(20.0, dtoA.getCtr());
        assertEquals(50.0, dtoA.getReadRate());

        CampaignAbMetricsDto.VariantMetricsDto dtoB = metrics.getVariants().get(1);
        assertFalse(dtoB.isLeader());
        assertEquals(4.0, dtoB.getCtr());
        assertEquals(60.0, dtoB.getReadRate());
    }
}
