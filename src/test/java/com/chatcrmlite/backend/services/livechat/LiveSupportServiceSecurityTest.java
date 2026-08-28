package com.chatcrmlite.backend.services.livechat;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Tenant;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class LiveSupportServiceSecurityTest {

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private LiveChatAuthorizationService authorizationService;

    @InjectMocks
    private LiveSupportService liveSupportService;

    private User requester;
    private User targetUserSameTenant;
    private User targetUserDifferentTenant;
    private Contact contact;
    private UUID contactId;

    @BeforeEach
    void setUp() {
        Tenant tenantA = new Tenant();
        tenantA.setId(UUID.randomUUID());

        Tenant tenantB = new Tenant();
        tenantB.setId(UUID.randomUUID());

        requester = new User();
        requester.setId(UUID.randomUUID());
        requester.setTenant(tenantA);

        targetUserSameTenant = new User();
        targetUserSameTenant.setId(UUID.randomUUID());
        targetUserSameTenant.setTenant(tenantA);

        targetUserDifferentTenant = new User();
        targetUserDifferentTenant.setId(UUID.randomUUID());
        targetUserDifferentTenant.setTenant(tenantB);

        contactId = UUID.randomUUID();
        contact = new Contact();
        contact.setId(contactId);
        contact.setTenant(tenantA);
    }

    @Test
    void testTransferChat_CrossTenantTarget_ThrowsSecurityException() {
        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));

        SecurityException exception = assertThrows(SecurityException.class, () -> 
            liveSupportService.transferChat(contactId, targetUserDifferentTenant, requester, "Reason", "reqId")
        );

        assertTrue(exception.getMessage().contains("Target user does not belong to the same tenant"));
    }
}
