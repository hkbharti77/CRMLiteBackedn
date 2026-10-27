package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dtos.CampaignAbMetricsDto;
import com.chatcrmlite.backend.dtos.CampaignVariantDto;
import com.chatcrmlite.backend.dtos.CreateCampaignRequestDto;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.WhatsAppCampaign;
import com.chatcrmlite.backend.models.WhatsAppCampaignVariant;
import com.chatcrmlite.backend.repositories.BroadcastUploadFilterConfigRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.whatsapp.campaign.BroadcastCsvParserService;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppAbTestingService;
import com.chatcrmlite.backend.services.whatsapp.campaign.WhatsAppCampaignService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WhatsAppCampaignControllerAbTest {

    @Mock
    private WhatsAppCampaignService campaignService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private BroadcastCsvParserService csvParserService;

    @Mock
    private BroadcastUploadFilterConfigRepository filterConfigRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private WhatsAppAbTestingService abTestingService;

    @InjectMocks
    private WhatsAppCampaignController controller;

    private User user;
    private WhatsAppCampaign campaign;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("test@crmlite.com");

        campaign = WhatsAppCampaign.builder()
                .id(UUID.randomUUID())
                .name("Diwali Split Test")
                .isAbTest(true)
                .build();

        when(userRepository.findByEmail("test@crmlite.com")).thenReturn(Optional.of(user));
    }

    @Test
    @DisplayName("createCampaign with A/B payload delegates to campaignService")
    void testCreateCampaign_AbTestingPayload() {
        CreateCampaignRequestDto dto = CreateCampaignRequestDto.builder()
                .name("Diwali A/B Test")
                .isAbTest(true)
                .abTestPercentage(30)
                .abWinnerMetric("CLICK_RATE")
                .abEvaluationHours(6)
                .variants(List.of(
                        CampaignVariantDto.builder().variantLabel("A").templateId("tmpl_1").build(),
                        CampaignVariantDto.builder().variantLabel("B").templateId("tmpl_2").build()
                ))
                .build();

        when(campaignService.createCampaign(eq(dto), eq(user))).thenReturn(campaign);

        ResponseEntity<WhatsAppCampaign> response = controller.createCampaign(dto, "test@crmlite.com");

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(campaign, response.getBody());
        verify(campaignService).createCampaign(eq(dto), eq(user));
    }

    @Test
    @DisplayName("getAbMetrics returns telemetry from abTestingService")
    void testGetAbMetrics_Success() {
        CampaignAbMetricsDto metricsDto = CampaignAbMetricsDto.builder()
                .campaignId(campaign.getId())
                .campaignName("Diwali Split Test")
                .isAbTest(true)
                .abStatus("TESTING")
                .totalAudience(1000)
                .testSampleCount(200)
                .holdoutCount(800)
                .variants(List.of(
                        CampaignAbMetricsDto.VariantMetricsDto.builder().variantLabel("A").ctr(12.5).isLeader(true).build(),
                        CampaignAbMetricsDto.VariantMetricsDto.builder().variantLabel("B").ctr(8.0).isLeader(false).build()
                ))
                .build();

        when(campaignService.getCampaign(campaign.getId(), user)).thenReturn(campaign);
        when(abTestingService.getAbMetrics(campaign.getId(), user)).thenReturn(metricsDto);

        ResponseEntity<CampaignAbMetricsDto> response = controller.getAbMetrics(campaign.getId(), "test@crmlite.com");

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1000, response.getBody().getTotalAudience());
        assertEquals(2, response.getBody().getVariants().size());
        assertTrue(response.getBody().getVariants().get(0).isLeader());
    }

    @Test
    @DisplayName("promoteWinner triggers promotion in abTestingService")
    void testPromoteWinner_Success() {
        UUID variantBId = UUID.randomUUID();
        WhatsAppCampaignVariant variantB = WhatsAppCampaignVariant.builder()
                .id(variantBId)
                .variantLabel("B")
                .isWinner(true)
                .build();

        when(campaignService.getCampaign(campaign.getId(), user)).thenReturn(campaign);
        when(abTestingService.evaluateAndPromoteWinner(campaign.getId(), variantBId, user)).thenReturn(variantB);

        ResponseEntity<WhatsAppCampaignVariant> response = controller.promoteWinner(campaign.getId(), variantBId, "test@crmlite.com");

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("B", response.getBody().getVariantLabel());
        assertTrue(response.getBody().getIsWinner());
        verify(abTestingService).evaluateAndPromoteWinner(campaign.getId(), variantBId, user);
    }
}
