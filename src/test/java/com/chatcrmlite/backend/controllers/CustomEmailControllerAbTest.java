package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.dto.CustomEmailDTO;
import com.chatcrmlite.backend.dto.CustomEmailRequest;
import com.chatcrmlite.backend.dto.EmailAbMetricsDto;
import com.chatcrmlite.backend.dto.EmailVariantDto;
import com.chatcrmlite.backend.dto.EmailVariantInput;
import com.chatcrmlite.backend.models.CustomEmail;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.email.EmailCampaignVariant;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.CustomEmailService;
import com.chatcrmlite.backend.services.email.EmailAbTestingService;
import com.chatcrmlite.backend.services.email.EmailInboundReplyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CustomEmailControllerAbTest {

    @Mock
    private CustomEmailService customEmailService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailInboundReplyService inboundReplyService;

    @Mock
    private EmailAbTestingService emailAbTestingService;

    @InjectMocks
    private CustomEmailController controller;

    private User user;
    private UUID campaignId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("marketer@crmlite.com");

        campaignId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("marketer@crmlite.com", "pass")
        );

        when(userRepository.findByEmail("marketer@crmlite.com")).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("send campaign with A/B testing payload delegates to customEmailService")
    void testSendCampaign_AbPayload() {
        CustomEmailRequest req = new CustomEmailRequest();
        req.setName("Product Update A/B Experiment");
        req.setSubject("Default subject");
        req.setBody("<p>Default body</p>");
        req.setIsAbTest(true);
        req.setAbTestPercentage(30);
        req.setAbWinnerMetric("OPEN_RATE");
        req.setAbEvaluationHours(4);
        req.setVariants(List.of(
                EmailVariantInput.builder().variantCode("A").subject("Variant A Subject").body("<p>A</p>").build(),
                EmailVariantInput.builder().variantCode("B").subject("Variant B Subject").body("<p>B</p>").build()
        ));

        CustomEmailDTO resultDTO = CustomEmailDTO.builder()
                .id(campaignId)
                .name("Product Update A/B Experiment")
                .isAbTest(true)
                .abStatus("TESTING")
                .build();

        when(customEmailService.scheduleOrExecuteCampaign(isNull(), eq(req), eq(user))).thenReturn(resultDTO);

        ResponseEntity<CustomEmailDTO> response = controller.send(req);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(campaignId, response.getBody().getId());
        assertTrue(response.getBody().getIsAbTest());
        verify(customEmailService).scheduleOrExecuteCampaign(isNull(), eq(req), eq(user));
    }

    @Test
    @DisplayName("getAbMetrics returns comparative telemetry for email campaign")
    void testGetAbMetrics_Success() {
        EmailAbMetricsDto metricsDto = EmailAbMetricsDto.builder()
                .campaignId(campaignId)
                .campaignName("Product Update A/B Experiment")
                .isAbTest(true)
                .abStatus("TESTING")
                .winnerMetric("OPEN_RATE")
                .totalAudience(500)
                .testSampleCount(100)
                .holdoutCount(400)
                .variants(List.of(
                        EmailVariantDto.builder().variantCode("A").openRate(35.0).isLeader(false).build(),
                        EmailVariantDto.builder().variantCode("B").openRate(62.5).isLeader(true).build()
                ))
                .build();

        when(emailAbTestingService.getAbMetrics(campaignId, user)).thenReturn(metricsDto);

        ResponseEntity<EmailAbMetricsDto> response = controller.getAbMetrics(campaignId);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(500, response.getBody().getTotalAudience());
        assertEquals(2, response.getBody().getVariants().size());
        assertTrue(response.getBody().getVariants().get(1).isLeader());
        verify(emailAbTestingService).getAbMetrics(campaignId, user);
    }

    @Test
    @DisplayName("promoteWinner triggers manual winner promotion and returns variant DTO")
    void testPromoteWinner_Success() {
        UUID variantBId = UUID.randomUUID();
        EmailCampaignVariant winningVariant = EmailCampaignVariant.builder()
                .id(variantBId)
                .variantCode("B")
                .name("Variant B")
                .isWinner(true)
                .build();

        EmailVariantDto expectedDto = EmailVariantDto.builder()
                .id(variantBId)
                .variantCode("B")
                .isWinner(true)
                .build();

        when(emailAbTestingService.evaluateAndPromoteWinner(campaignId, variantBId, user)).thenReturn(winningVariant);
        when(emailAbTestingService.toVariantDto(winningVariant)).thenReturn(expectedDto);

        ResponseEntity<EmailVariantDto> response = controller.promoteWinner(
                campaignId, Map.of("variantId", variantBId.toString())
        );

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("B", response.getBody().getVariantCode());
        assertTrue(response.getBody().isWinner());
        verify(emailAbTestingService).evaluateAndPromoteWinner(campaignId, variantBId, user);
    }
}
