package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.dto.payments.WhatsAppSessionInfoDto;
import com.chatcrmlite.backend.models.enums.WhatsAppSessionStatus;
import com.chatcrmlite.backend.repositories.MessageRepository;
import com.chatcrmlite.backend.services.payments.PaymentTemplateDefinitionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppSessionWindowServiceTest {

    @Mock
    private MessageRepository messageRepository;

    @Spy
    private PaymentTemplateDefinitionRegistry templateRegistry = new PaymentTemplateDefinitionRegistry();

    @InjectMocks
    private WhatsAppSessionWindowService sessionWindowService;

    private UUID tenantId;
    private String customerWaId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        customerWaId = "919876543210";
    }

    @Test
    @DisplayName("Boundary: 23 hours 59 minutes ago -> Session should be OPEN")
    void testSessionOpenWithin24Hours() {
        LocalDateTime recentInbound = LocalDateTime.now(ZoneOffset.UTC).minusHours(23).minusMinutes(59);
        when(messageRepository.findLatestInboundTimestamp(eq(tenantId), eq(customerWaId)))
            .thenReturn(Optional.of(recentInbound));

        WhatsAppSessionInfoDto info = sessionWindowService.getSessionStatus(tenantId, customerWaId);

        assertThat(info.getSessionStatus()).isEqualTo(WhatsAppSessionStatus.OPEN);
        assertThat(info.isNativePaymentAllowed()).isTrue();
        assertThat(info.isDirectGatewayAllowed()).isTrue();
        assertThat(info.isTemplateRequired()).isFalse();
        assertThat(info.getRemainingSeconds()).isGreaterThan(0);
        assertThat(info.getAvailableTemplates()).isNotEmpty();
    }

    @Test
    @DisplayName("Boundary: 24 hours 01 minutes ago -> Session should be CLOSED")
    void testSessionClosedAfter24Hours() {
        LocalDateTime expiredInbound = LocalDateTime.now(ZoneOffset.UTC).minusHours(24).minusMinutes(1);
        when(messageRepository.findLatestInboundTimestamp(eq(tenantId), eq(customerWaId)))
            .thenReturn(Optional.of(expiredInbound));

        WhatsAppSessionInfoDto info = sessionWindowService.getSessionStatus(tenantId, customerWaId);

        assertThat(info.getSessionStatus()).isEqualTo(WhatsAppSessionStatus.CLOSED);
        assertThat(info.isNativePaymentAllowed()).isFalse();
        assertThat(info.isTemplateRequired()).isTrue();
        assertThat(info.getRemainingSeconds()).isZero();
        assertThat(info.getAvailableTemplates()).hasSize(5);
    }

    @Test
    @DisplayName("No inbound message exists -> Session should be CLOSED")
    void testSessionClosedWhenNoInboundExists() {
        when(messageRepository.findLatestInboundTimestamp(eq(tenantId), eq(customerWaId)))
            .thenReturn(Optional.empty());

        WhatsAppSessionInfoDto info = sessionWindowService.getSessionStatus(tenantId, customerWaId);

        assertThat(info.getSessionStatus()).isEqualTo(WhatsAppSessionStatus.CLOSED);
        assertThat(info.isNativePaymentAllowed()).isFalse();
        assertThat(info.isTemplateRequired()).isTrue();
    }

    @Test
    @DisplayName("Internal error in repository -> Session should be UNKNOWN (fail-safe: template required)")
    void testSessionUnknownOnDatabaseError() {
        when(messageRepository.findLatestInboundTimestamp(any(), any()))
            .thenThrow(new RuntimeException("Database timeout"));

        WhatsAppSessionInfoDto info = sessionWindowService.getSessionStatus(tenantId, customerWaId);

        assertThat(info.getSessionStatus()).isEqualTo(WhatsAppSessionStatus.UNKNOWN);
        assertThat(info.isNativePaymentAllowed()).isFalse();
        assertThat(info.isTemplateRequired()).isTrue();
    }
}
