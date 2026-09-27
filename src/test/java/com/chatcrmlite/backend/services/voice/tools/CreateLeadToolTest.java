package com.chatcrmlite.backend.services.voice.tools;

import com.chatcrmlite.backend.models.Contact;
import com.chatcrmlite.backend.models.Lead;
import com.chatcrmlite.backend.models.User;
import com.chatcrmlite.backend.repositories.ContactRepository;
import com.chatcrmlite.backend.repositories.LeadRepository;
import com.chatcrmlite.backend.repositories.UserRepository;
import com.chatcrmlite.backend.services.ReferenceNumberService;
import com.chatcrmlite.backend.services.lead.LeadEnquiryService;
import com.chatcrmlite.backend.services.tenant.QuotaEnforcerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateLeadToolTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ContactRepository contactRepository;
    @Mock
    private LeadRepository leadRepository;
    @Mock
    private QuotaEnforcerService quotaEnforcerService;
    @Mock
    private ReferenceNumberService referenceNumberService;
    @Mock
    private LeadEnquiryService leadEnquiryService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private CreateLeadTool createLeadTool;

    @BeforeEach
    void setUp() {
        createLeadTool = new CreateLeadTool(objectMapper, userRepository, contactRepository, leadRepository, quotaEnforcerService, referenceNumberService, leadEnquiryService);
    }

    private ToolExecutionContext createMockContext() {
        return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stream-1", "conv-1", "test-turn-id", "919876543210");
    }

    @Test
    void testSuccessfulCreateLead() {
        ToolExecutionContext context = createMockContext();
        User mockUser = new User();
        when(userRepository.findById(context.userId())).thenReturn(Optional.of(mockUser));
        when(contactRepository.findByWaIdAndOwner(anyString(), any())).thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(referenceNumberService.generate(any(), any())).thenReturn("LEAD-001");
        when(leadRepository.save(any())).thenReturn(new Lead());

        String jsonArgs = "{\"customer_name\":\"John Doe\", \"enquiry_details\":\"Interested in CRM\"}";
        ToolExecutionResult result = createLeadTool.execute("call-1", jsonArgs, context);

        assertEquals(ToolExecutionStatus.SUCCESS, result.status());
        verify(leadEnquiryService).appendEnquiry(any(), eq("Interested in CRM"), eq("VOICE_BOT"), eq("voice-bot"), anyMap());
    }

    @Test
    void testMissingRequiredFieldReturnsValidationFailed() {
        ToolExecutionContext context = createMockContext();
        String jsonArgs = "{\"customer_name\":\"Unknown\", \"enquiry_details\":\"\"}";
        ToolExecutionResult result = createLeadTool.execute("call-2", jsonArgs, context);

        assertEquals(ToolExecutionStatus.VALIDATION_FAILED, result.status());
        assertTrue(result.result().contains("Missing required field"));
        verifyNoInteractions(userRepository); // execution stopped before DB calls
    }

    @Test
    void testDatabaseConstraintFailureDoesNotThrowUnexpectedRollbackException() {
        ToolExecutionContext context = createMockContext();
        User mockUser = new User();
        when(userRepository.findById(context.userId())).thenReturn(Optional.of(mockUser));
        when(contactRepository.findByWaIdAndOwner(anyString(), any())).thenReturn(Optional.empty());
        
        // Simulate phone length constraint failure on contact save
        when(contactRepository.save(any())).thenThrow(new DataIntegrityViolationException("value too long for type character varying(50)"));

        String jsonArgs = "{\"customer_name\":\"John Doe\", \"enquiry_details\":\"Interested in CRM\"}";
        ToolExecutionResult result = createLeadTool.execute("call-3", jsonArgs, context);

        // Exception is caught and returned as UNKNOWN instead of blowing up the @Transactional scope
        assertEquals(ToolExecutionStatus.UNKNOWN, result.status());
        assertTrue(result.result().contains("value too long"));
    }

    @Test
    void testTruncatePhoneLengthForWaId() {
        ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "stream", "conv", "turn-1", 
            "web_voice_wacid.1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890"); // > 250 chars

        User mockUser = new User();
        when(userRepository.findById(context.userId())).thenReturn(Optional.of(mockUser));
        when(contactRepository.findByWaIdAndOwner(anyString(), any())).thenReturn(Optional.empty());
        when(contactRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(referenceNumberService.generate(any(), any())).thenReturn("LEAD-001");
        when(leadRepository.save(any())).thenReturn(new Lead());

        String jsonArgs = "{\"customer_name\":\"Jane Doe\", \"enquiry_details\":\"Test truncate\"}";
        ToolExecutionResult result = createLeadTool.execute("call-4", jsonArgs, context);

        assertEquals(ToolExecutionStatus.SUCCESS, result.status());
        
        ArgumentCaptor<Map<String, String>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(leadEnquiryService).appendEnquiry(any(), anyString(), anyString(), anyString(), dataCaptor.capture());
        
        String savedPhone = dataCaptor.getValue().get("phone");
        assertNotNull(savedPhone);
        assertTrue(savedPhone.length() <= 250, "Phone string must be truncated safely");
    }
}
