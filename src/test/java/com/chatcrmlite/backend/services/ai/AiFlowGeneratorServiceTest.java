package com.chatcrmlite.backend.services.ai;

import com.chatcrmlite.backend.models.flows.dto.AiFlowDraftDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiFlowGeneratorServiceTest {

    @Mock
    private AiOrchestrator aiOrchestrator;

    @Mock
    private AiGenerationAuditService auditService;

    private ObjectMapper objectMapper;

    @InjectMocks
    private AiFlowGeneratorService aiFlowGeneratorService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        aiFlowGeneratorService = new AiFlowGeneratorService(aiOrchestrator, auditService, objectMapper);
    }

    @Test
    void testValidFlowGeneration() throws Exception {
        String validJson = """
            {
              "name": "Test Flow",
              "description": "Test Desc",
              "fields": [
                {
                  "id": "name_field",
                  "name": "name_field",
                  "label": "Name",
                  "type": "TEXT",
                  "required": true
                }
              ]
            }
            """;

        AiResponse mockResponse = AiResponse.builder()
                .content(validJson)
                .provider("test")
                .tokensUsed(100)
                .latencyMs(200L)
                .build();

        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(mockResponse);

        AiFlowDraftDto draft = aiFlowGeneratorService.generateFlow("Create a test flow");

        assertNotNull(draft);
        assertEquals("Test Flow", draft.getName());
        assertEquals(1, draft.getFields().size());
        assertEquals("name_field", draft.getFields().get(0).getId());

        verify(auditService, times(1)).logGeneration(eq("test"), anyString(), eq("SUCCESS"), eq(100), eq(200L), isNull());
    }

    @Test
    void testSemanticValidation_SelectWithoutOptions() throws Exception {
        String invalidJson = """
            {
              "name": "Test Flow",
              "description": "Test Desc",
              "fields": [
                {
                  "id": "select_field",
                  "name": "select_field",
                  "label": "Select",
                  "type": "SELECT",
                  "required": true
                }
              ]
            }
            """;

        AiResponse mockResponse = AiResponse.builder()
                .content(invalidJson)
                .provider("test")
                .tokensUsed(100)
                .latencyMs(200L)
                .build();

        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(mockResponse);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            aiFlowGeneratorService.generateFlow("Create an invalid flow");
        });

        assertTrue(exception.getMessage().contains("AI generated an invalid flow: Field 'select_field' of type SELECT must have options"));
        
        // Retries once (2 attempts total)
        verify(aiOrchestrator, times(2)).execute(any(AiRequest.class));
        verify(auditService, times(1)).logGeneration(anyString(), anyString(), eq("FAILED_VALIDATION"), anyInt(), anyLong(), anyString());
    }

    @Test
    void testMalformedJson() throws Exception {
        String malformedJson = "{ invalid json";

        AiResponse mockResponse = AiResponse.builder()
                .content(malformedJson)
                .provider("test")
                .tokensUsed(50)
                .latencyMs(100L)
                .build();

        when(aiOrchestrator.execute(any(AiRequest.class))).thenReturn(mockResponse);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            aiFlowGeneratorService.generateFlow("Create a malformed flow");
        });

        assertTrue(exception.getMessage().contains("malformed JSON"));
        
        // Retries once (2 attempts total)
        verify(aiOrchestrator, times(2)).execute(any(AiRequest.class));
        verify(auditService, times(1)).logGeneration(anyString(), anyString(), eq("FAILED_JSON_PARSE"), anyInt(), anyLong(), anyString());
    }
}
