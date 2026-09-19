package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.services.whatsapp.validation.WhatsAppTemplateVariableParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppTemplateVariableParserTest {

    private WhatsAppTemplateVariableParser parser;

    @BeforeEach
    void setUp() {
        parser = new WhatsAppTemplateVariableParser();
    }

    @Test
    @DisplayName("Extracts variables in sequential order from text")
    void testExtractVariables() {
        String text = "Hello {{1}}, your order {{2}} has shipped with tracking {{3}}!";
        List<Integer> vars = parser.extractVariables(text);
        assertEquals(List.of(1, 2, 3), vars);
    }

    @Test
    @DisplayName("Throws exception when variable numbering does not start at 1")
    void testNonOneStartThrows() {
        String text = "Hello {{2}}, your order {{3}} is ready.";
        List<Integer> vars = parser.extractVariables(text);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                parser.validateSequential(vars, "body")
        );
        assertTrue(ex.getMessage().contains("must start at {{1}}"));
    }

    @Test
    @DisplayName("Throws exception when sequential numbering has a gap")
    void testGapInVariablesThrows() {
        String text = "Hello {{1}}, your order {{3}} is ready.";
        List<Integer> vars = parser.extractVariables(text);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                parser.validateSequential(vars, "body")
        );
        assertTrue(ex.getMessage().contains("missing variable {{2}}") || ex.getMessage().contains("Expected {{2}}"));
    }

    @Test
    @DisplayName("Passes validation when numbering is strictly sequential")
    void testStrictlySequentialPasses() {
        String text = "Welcome {{1}}! Your code is {{2}} valid until {{3}}.";
        List<Integer> vars = parser.extractVariables(text);
        assertDoesNotThrow(() -> parser.validateSequential(vars, "body"));
    }

    @Test
    @DisplayName("Uses user-supplied samples when provided")
    void testResolveSamplesWithUserValues() {
        String text = "Hello {{1}}, order {{2}} is confirmed.";
        List<String> userSamples = List.of("Himanshu", "ORD-9999");
        List<String> resolved = parser.resolveAndValidateSamples("body", text, userSamples);

        assertEquals(2, resolved.size());
        assertEquals("Himanshu", resolved.get(0));
        assertEquals("ORD-9999", resolved.get(1));
    }

    @Test
    @DisplayName("Fills in realistic semantic fallbacks when user samples are omitted")
    void testResolveSamplesWithFallbacks() {
        String text = "Hello {{1}}, order {{2}} is confirmed on {{3}}.";
        List<String> resolved = parser.resolveAndValidateSamples("body", text, null);

        assertEquals(3, resolved.size());
        assertEquals("Customer Name", resolved.get(0));
        assertEquals("Order Number", resolved.get(1));
        assertEquals("Company Name", resolved.get(2));
    }

    @Test
    @DisplayName("Renders preview replacing variables with sample values")
    void testRenderPreview() {
        String text = "Hello {{1}}, your code is {{2}}!";
        String rendered = parser.renderTemplatePreview(text, List.of("Himanshu", "XYZ123"));
        assertEquals("Hello Himanshu, your code is XYZ123!", rendered);
    }
}
