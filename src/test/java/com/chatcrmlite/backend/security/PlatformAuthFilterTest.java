package com.chatcrmlite.backend.security;

import com.chatcrmlite.backend.models.PlatformAdmin;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.PlatformAdminRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatformAuthFilterTest {

    @Mock
    private PlatformJwtUtils platformJwtUtils;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private PlatformAdminRepository platformAdminRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private PlatformAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("TEST 1: User with superadmin@attacker.com and AGENT role receives NO platform privileges")
    void testSuperadminAttackerEmail_NoPlatformPrivileges() throws Exception {
        String token = "jwt.token.attacker";
        String email = "superadmin@attacker.com";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(platformJwtUtils.validatePlatformToken(token)).thenReturn(false);
        when(jwtUtils.validateJwtToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(token)).thenReturn(email);

        User normalUser = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .role(User.Role.AGENT)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(normalUser));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth, "Attacker email starting with 'superadmin' must not receive platform authentication");
    }

    @Test
    @DisplayName("TEST 2: User with superadmin123@example.com and ADMIN role receives NO platform privileges")
    void testSuperadminPrefixEmail_NoPlatformPrivileges() throws Exception {
        String token = "jwt.token.prefix";
        String email = "superadmin123@example.com";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(platformJwtUtils.validatePlatformToken(token)).thenReturn(false);
        when(jwtUtils.validateJwtToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(token)).thenReturn(email);

        User tenantAdmin = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .role(User.Role.ADMIN)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(tenantAdmin));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth, "Tenant admin with 'superadmin' prefix must not receive platform privileges");
    }

    @Test
    @DisplayName("TEST 3: Legitimate server-side SUPER_ADMIN user receives platform privileges")
    void testLegitimateSuperAdminUser_ReceivesPlatformPrivileges() throws Exception {
        String token = "jwt.token.superadmin";
        String email = "legit.superadmin@crm.internal";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(platformJwtUtils.validatePlatformToken(token)).thenReturn(false);
        when(jwtUtils.validateJwtToken(token)).thenReturn(true);
        when(jwtUtils.getEmailFromJwtToken(token)).thenReturn(email);

        User superUser = User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .role(User.Role.SUPER_ADMIN)
                .build();
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(superUser));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Legitimate SUPER_ADMIN user must receive platform authentication");
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN")));
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN")));
    }

    @Test
    @DisplayName("TEST 4: Legitimate platform admin JWT via platformAdminRepository receives platform privileges")
    void testLegitimatePlatformAdmin_ReceivesPlatformPrivileges() throws Exception {
        String platformToken = "platform.jwt.token";
        String adminEmail = "platformadmin@platform.com";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + platformToken);
        when(platformJwtUtils.validatePlatformToken(platformToken)).thenReturn(true);
        when(platformJwtUtils.getEmailFromToken(platformToken)).thenReturn(adminEmail);

        PlatformAdmin platformAdmin = new PlatformAdmin();
        platformAdmin.setEmail(adminEmail);
        when(platformAdminRepository.findByEmailIgnoreCase(adminEmail)).thenReturn(Optional.of(platformAdmin));

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth, "Legitimate PlatformAdmin record must receive platform authentication");
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PLATFORM_ADMIN")));
    }
}
