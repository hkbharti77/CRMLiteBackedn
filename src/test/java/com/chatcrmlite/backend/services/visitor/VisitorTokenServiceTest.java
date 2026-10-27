package com.chatcrmlite.backend.services.visitor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VisitorTokenServiceTest {

    private VisitorTokenService tokenService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tokenService = new VisitorTokenService();
        ReflectionTestUtils.setField(tokenService, "tokenSecret", "test-secret-entropy-key-32-chars-long");
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("1. Generates valid token with signature and prefix")
    void testGenerateToken_ValidFormat() {
        String visitorId = UUID.randomUUID().toString();
        String token = tokenService.generateToken(visitorId, tenantId);

        assertNotNull(token);
        assertTrue(token.startsWith("vid_" + visitorId));
        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
    }

    @Test
    @DisplayName("2. Extracts visitor ID successfully from genuine token")
    void testExtractVisitorId_Success() {
        String visitorId = UUID.randomUUID().toString();
        String token = tokenService.generateToken(visitorId, tenantId);

        String extracted = tokenService.extractVisitorIdIfValid(token, tenantId);
        assertEquals(visitorId, extracted);
    }

    @Test
    @DisplayName("3. Rejects token with tampered visitor ID")
    void testExtractVisitorId_TamperedVisitorId() {
        String visitorId = UUID.randomUUID().toString();
        String token = tokenService.generateToken(visitorId, tenantId);

        // Tamper the visitorId in the first segment
        String tamperedToken = "vid_" + UUID.randomUUID() + token.substring(token.indexOf('.'));
        String extracted = tokenService.extractVisitorIdIfValid(tamperedToken, tenantId);

        assertNull(extracted, "Tampered token signature must fail verification");
    }

    @Test
    @DisplayName("4. Rejects token with tampered signature")
    void testExtractVisitorId_TamperedSignature() {
        String visitorId = UUID.randomUUID().toString();
        String token = tokenService.generateToken(visitorId, tenantId);

        String tamperedToken = token + "xyz";
        String extracted = tokenService.extractVisitorIdIfValid(tamperedToken, tenantId);

        assertNull(extracted, "Tampered signature must be rejected");
    }

    @Test
    @DisplayName("5. Rejects token when presented to a different tenant")
    void testExtractVisitorId_DifferentTenant() {
        String visitorId = UUID.randomUUID().toString();
        String token = tokenService.generateToken(visitorId, tenantId);

        UUID differentTenantId = UUID.randomUUID();
        String extracted = tokenService.extractVisitorIdIfValid(token, differentTenantId);

        assertNull(extracted, "Token generated for Tenant A cannot be used for Tenant B");
    }

    @Test
    @DisplayName("6. resolveVisitorIdentity creates new token if missing or invalid")
    void testResolveVisitorIdentity_NewAndExisting() {
        // Missing token -> generates new
        var identityNew = tokenService.resolveVisitorIdentity(null, tenantId);
        assertTrue(identityNew.isNew());
        assertNotNull(identityNew.getVisitorId());
        assertNotNull(identityNew.getFullToken());

        // Valid existing token -> reuses it
        var identityReused = tokenService.resolveVisitorIdentity(identityNew.getFullToken(), tenantId);
        assertFalse(identityReused.isNew());
        assertEquals(identityNew.getVisitorId(), identityReused.getVisitorId());
        assertEquals(identityNew.getFullToken(), identityReused.getFullToken());
    }
}
