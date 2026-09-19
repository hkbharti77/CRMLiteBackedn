package com.chatcrmlite.backend.services.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

class FileSecurityValidatorTest {

    private FileSecurityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new FileSecurityValidator();
    }

    @Test
    @DisplayName("Valid PDF with %PDF- magic bytes passes validation")
    void validPdfPasses() {
        byte[] pdfBytes = "%PDF-1.4 mock pdf content bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "brochure.pdf", "application/pdf", pdfBytes);

        FileSecurityValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.valid());
        assertEquals("application/pdf", result.detectedMimeType());
        assertEquals("DOCUMENT", result.mediaType());
        assertNull(result.errorMessage());
    }

    @Test
    @DisplayName("Executable disguised as .pdf extension is blocked")
    void fakePdfExecutableBlocked() {
        byte[] fakeExeBytes = "MZ\u0090\u0000mock executable content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "malware.pdf", "application/pdf", fakeExeBytes);

        FileSecurityValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.valid());
        assertNotNull(result.errorMessage());
        assertTrue(result.errorMessage().contains("Invalid file content"));
    }

    @Test
    @DisplayName("Blocked dangerous file extension like .exe or .sh")
    void dangerousExtensionBlocked() {
        byte[] bytes = "%PDF-1.4 mock".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "script.sh", "text/plain", bytes);

        FileSecurityValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("Execution of dangerous file types is blocked"));
    }

    @Test
    @DisplayName("Valid PNG image passes validation")
    void validPngPasses() {
        byte[] pngHeader = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        MockMultipartFile file = new MockMultipartFile("file", "image.png", "image/png", pngHeader);

        FileSecurityValidator.ValidationResult result = validator.validate(file);

        assertTrue(result.valid());
        assertEquals("image/png", result.detectedMimeType());
        assertEquals("IMAGE", result.mediaType());
    }

    @Test
    @DisplayName("Empty file is rejected")
    void emptyFileRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        FileSecurityValidator.ValidationResult result = validator.validate(file);

        assertFalse(result.valid());
        assertTrue(result.errorMessage().contains("File is missing or empty"));
    }
}
