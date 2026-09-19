package com.chatcrmlite.backend.services.whatsapp;

import com.chatcrmlite.backend.services.whatsapp.validation.DynamicUrlValidator;
import com.chatcrmlite.backend.dto.WhatsAppTemplateDto.TemplateButtonDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicUrlValidatorTest {

    private DynamicUrlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DynamicUrlValidator(DynamicUrlValidator.DynamicUrlRules.builder()
                .maxVariables(1)
                .variableMustBeAtEnd(true)
                .build());
    }

    @Test
    @DisplayName("Allows valid static URL")
    void testValidStaticUrl() {
        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("URL")
                .text("Visit Website")
                .url("https://gyanvaniai.online/promo")
                .build();

        assertDoesNotThrow(() -> validator.validateUrlButtons(List.of(btn)));
    }

    @Test
    @DisplayName("Allows valid dynamic URL with tracking {{1}} and urlSample")
    void testValidDynamicUrl() {
        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("URL")
                .text("Track Order")
                .url("https://gyanvaniai.online/track/{{1}}")
                .urlSample("https://gyanvaniai.online/track/ORD-12345")
                .build();

        assertDoesNotThrow(() -> validator.validateUrlButtons(List.of(btn)));
    }

    @Test
    @DisplayName("Rejects dynamic URL missing urlSample")
    void testDynamicUrlMissingSampleThrows() {
        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("URL")
                .text("Track Order")
                .url("https://gyanvaniai.online/track/{{1}}")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateUrlButtons(List.of(btn))
        );
        assertTrue(ex.getMessage().contains("sample destination URL") || ex.getMessage().contains("urlSample"));
    }

    @Test
    @DisplayName("Rejects dynamic URL with multiple variables")
    void testMultipleVariablesInUrlThrows() {
        TemplateButtonDto btn = TemplateButtonDto.builder()
                .type("URL")
                .text("Track")
                .url("https://gyanvaniai.online/track/{{1}}/order/{{2}}")
                .urlSample("https://gyanvaniai.online/track/1/order/2")
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateUrlButtons(List.of(btn))
        );
        assertTrue(ex.getMessage().contains("only support {{1}}") || ex.getMessage().contains("maximum allowed variables"));
    }

    @Test
    @DisplayName("Rejects more than 2 URL buttons")
    void testExceedingMaxUrlButtonsThrows() {
        TemplateButtonDto btn1 = TemplateButtonDto.builder().type("URL").text("Link 1").url("https://a.com").build();
        TemplateButtonDto btn2 = TemplateButtonDto.builder().type("URL").text("Link 2").url("https://b.com").build();
        TemplateButtonDto btn3 = TemplateButtonDto.builder().type("URL").text("Link 3").url("https://c.com").build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                validator.validateUrlButtons(List.of(btn1, btn2, btn3))
        );
        assertTrue(ex.getMessage().contains("Maximum of 2 URL buttons"));
    }
}
