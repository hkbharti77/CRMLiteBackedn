package com.chatcrmlite.backend.controllers;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.livechat.LiveSupportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LiveChatTransferSecurityTest {

    @Mock
    private LiveSupportService liveSupportService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private LiveChatController liveChatController;

    private User requester;
    private User targetUserSameTenant;
    private User targetUserDifferentTenant;
    private UUID contactId;
    private UUID targetUserSameTenantId;
    private UUID targetUserDifferentTenantId;

    @BeforeEach
    void setUp() {
        Tenant tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());

        Tenant tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setEmail("requester@tenantA.com");
        requester.setTenant(tenantA);

        targetUserSameTenantId = UUID.randomUUID();
        targetUserSameTenant = new User();
        targetUserSameTenant.setId(targetUserSameTenantId);
        targetUserSameTenant.setTenant(tenantA);

        targetUserDifferentTenantId = UUID.randomUUID();
        targetUserDifferentTenant = new User();
        targetUserDifferentTenant.setId(targetUserDifferentTenantId);
        targetUserDifferentTenant.setTenant(tenantB);

        contactId = UUID.randomUUID();

        when(authentication.getName()).thenReturn("requester@tenantA.com");
        when(userRepository.findByEmailWithTenant("requester@tenantA.com")).thenReturn(Optional.of(requester));
    }

    @Test
    void testTransferChat_SameTenant_Succeeds() {
        when(userRepository.findById(targetUserSameTenantId)).thenReturn(Optional.of(targetUserSameTenant));
        
        Map<String, String> payload = Map.of(
            "targetUserId", targetUserSameTenantId.toString(),
            "reason", "Escalation"
        );

        ResponseEntity<Map<String, Object>> response = liveChatController.transferChat(authentication, contactId, payload);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(liveSupportService).transferChat(eq(contactId), eq(targetUserSameTenant), eq(requester), eq("Escalation"), any());
    }

    @Test
    void testTransferChat_CrossTenant_Returns403() {
        when(userRepository.findById(targetUserDifferentTenantId)).thenReturn(Optional.of(targetUserDifferentTenant));
        
        Map<String, String> payload = Map.of(
            "targetUserId", targetUserDifferentTenantId.toString(),
            "reason", "Cross tenant exploit"
        );

        ResponseEntity<Map<String, Object>> response = liveChatController.transferChat(authentication, contactId, payload);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(liveSupportService, never()).transferChat(any(), any(), any(), any(), any());
    }

    @Test
    void testTransferChat_TargetUserNoTenant_Returns403() {
        User targetUserNoTenant = new User();
        targetUserNoTenant.setId(UUID.randomUUID());
        when(userRepository.findById(targetUserNoTenant.getId())).thenReturn(Optional.of(targetUserNoTenant));
        
        Map<String, String> payload = Map.of(
            "targetUserId", targetUserNoTenant.getId().toString(),
            "reason", "No tenant target"
        );

        ResponseEntity<Map<String, Object>> response = liveChatController.transferChat(authentication, contactId, payload);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verify(liveSupportService, never()).transferChat(any(), any(), any(), any(), any());
    }
}
