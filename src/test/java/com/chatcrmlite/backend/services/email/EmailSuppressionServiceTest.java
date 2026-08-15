package com.chatcrmlite.backend.services.email;

import com.chatcrmlite.backend.models.email.EmailSuppressionList;
import com.chatcrmlite.backend.models.email.EmailSuppressionList.SuppressionReason;
import com.chatcrmlite.backend.repositories.email.EmailSuppressionListRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmailSuppressionServiceTest {

    @Mock
    private EmailSuppressionListRepository suppressionRepository;

    @InjectMocks
    private EmailSuppressionService suppressionService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testNormalizeEmail() {
        assertEquals("test@example.com", suppressionService.normalizeEmail("  Test@Example.com  "));
        assertEquals("test+1@example.com", suppressionService.normalizeEmail("test+1@example.com"));
        assertNull(suppressionService.normalizeEmail(null));
    }

    @Test
    void testIsSuppressedReturnsTrueWhenFound() {
        UUID tenantId = UUID.randomUUID();
        String email = "test@example.com";
        
        when(suppressionRepository.existsByTenantIdAndEmail(tenantId, email)).thenReturn(true);
        
        assertTrue(suppressionService.isSuppressed(tenantId, email));
        verify(suppressionRepository).existsByTenantIdAndEmail(tenantId, email);
    }

    @Test
    void testAddSuppressionCreatesNewRecord() {
        UUID tenantId = UUID.randomUUID();
        String email = "test@example.com";
        UUID campaignId = UUID.randomUUID();
        
        when(suppressionRepository.findByTenantIdAndEmail(tenantId, email)).thenReturn(Optional.empty());
        
        suppressionService.addSuppression(tenantId, email, SuppressionReason.UNSUBSCRIBED, campaignId, null);
        
        verify(suppressionRepository, times(1)).save(any(EmailSuppressionList.class));
    }
}
