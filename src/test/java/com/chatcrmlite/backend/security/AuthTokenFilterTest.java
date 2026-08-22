package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.models.UserSession;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.repositories.UserSessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AuthTokenFilterTest {

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserSessionRepository sessionRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private AuthTokenFilter filter;

    private final String rawToken = "sample.jwt.token";
    private final String email = "admin@tenant.com";
    private final String sessionId = "session_uuid_123";
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("Successful authentication clears TenantContext and MDC after request completes")
    void testSuccessfulAuth_ClearsContextInFinally() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(jwtUtils.validateJwtToken(rawToken)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(rawToken)).thenReturn(email);
        when(jwtUtils.getSessionIdFromJwtToken(rawToken)).thenReturn(sessionId);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        User user = new User();
        user.setEmail(email);
        user.setTenant(tenant);
        user.setRole(User.Role.ADMIN);
        user.setAccountStatus(User.AccountStatus.ACTIVE);

        UserSession session = new UserSession();
        session.setTokenId(sessionId);
        session.setStatus("ACTIVE");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(sessionRepository.findByTokenId(sessionId)).thenReturn(Optional.of(session));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(TenantContext.getTenantId(), "TenantContext must be cleared after request");
        assertNull(MDC.get("tenantId"), "MDC tenantId must be cleared");
        assertNull(MDC.get("userEmail"), "MDC userEmail must be cleared");
    }

    @Test
    @DisplayName("Locked account sends 403 and clears TenantContext/MDC despite early return")
    void testLockedAccount_EarlyReturnClearsContext() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(jwtUtils.validateJwtToken(rawToken)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(rawToken)).thenReturn(email);
        when(jwtUtils.getSessionIdFromJwtToken(rawToken)).thenReturn(sessionId);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        User user = new User();
        user.setEmail(email);
        user.setTenant(tenant);
        user.setRole(User.Role.ADMIN);
        user.setAccountStatus(User.AccountStatus.LOCKED);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "Account is locked");
        verify(filterChain, never()).doFilter(request, response);
        assertNull(TenantContext.getTenantId(), "TenantContext must be cleared on early return");
        assertNull(MDC.get("tenantId"), "MDC must be cleared on early return");
    }

    @Test
    @DisplayName("Expired or missing session sends 401 and clears TenantContext/MDC")
    void testExpiredSession_ClearsContext() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(jwtUtils.validateJwtToken(rawToken)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(rawToken)).thenReturn(email);
        when(jwtUtils.getSessionIdFromJwtToken(rawToken)).thenReturn(sessionId);

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        User user = new User();
        user.setEmail(email);
        user.setTenant(tenant);
        user.setRole(User.Role.ADMIN);
        user.setAccountStatus(User.AccountStatus.ACTIVE);

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(sessionRepository.findByTokenId(sessionId)).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session expired or revoked");
        assertNull(TenantContext.getTenantId());
        assertNull(MDC.get("tenantId"));
    }

    @Test
    @DisplayName("IP whitelist rejection sends 403 and clears TenantContext/MDC")
    void testIpWhitelistRejection_ClearsContext() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(jwtUtils.validateJwtToken(rawToken)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(rawToken)).thenReturn(email);
        when(jwtUtils.getSessionIdFromJwtToken(rawToken)).thenReturn(sessionId);
        when(request.getRemoteAddr()).thenReturn("192.168.1.100");

        Tenant tenant = new Tenant();
        tenant.setId(tenantId);

        User user = new User();
        user.setEmail(email);
        user.setTenant(tenant);
        user.setRole(User.Role.ADMIN);
        user.setAccountStatus(User.AccountStatus.ACTIVE);
        user.setIpWhitelist(Set.of("10.0.0.1"));

        UserSession session = new UserSession();
        session.setTokenId(sessionId);
        session.setStatus("ACTIVE");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(sessionRepository.findByTokenId(sessionId)).thenReturn(Optional.of(session));

        filter.doFilterInternal(request, response, filterChain);

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN, "IP not whitelisted");
        assertNull(TenantContext.getTenantId());
        assertNull(MDC.get("tenantId"));
    }

    @Test
    @DisplayName("User with null tenant does not throw NPE and executes safely")
    void testUserWithNullTenant_NoNpe() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer " + rawToken);
        when(jwtUtils.validateJwtToken(rawToken)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(rawToken)).thenReturn(email);
        when(jwtUtils.getSessionIdFromJwtToken(rawToken)).thenReturn(sessionId);

        User user = new User();
        user.setEmail(email);
        user.setTenant(null); // System / Platform user without tenant
        user.setRole(User.Role.ADMIN);
        user.setAccountStatus(User.AccountStatus.ACTIVE);

        UserSession session = new UserSession();
        session.setTokenId(sessionId);
        session.setStatus("ACTIVE");

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        when(sessionRepository.findByTokenId(sessionId)).thenReturn(Optional.of(session));

        assertDoesNotThrow(() -> filter.doFilterInternal(request, response, filterChain));
        verify(filterChain).doFilter(request, response);
        assertNull(TenantContext.getTenantId());
    }
}
