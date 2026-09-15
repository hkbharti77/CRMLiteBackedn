package com.chatcrmlite.backend.services.ingestion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DocumentCharsetDecoderTest {

    private DocumentCharsetDecoder decoder;

    @BeforeEach
    void setUp() {
        decoder = new DocumentCharsetDecoder();
        ReflectionTestUtils.setField(decoder, "fallbackCharsetName", "ISO-8859-1");
        ReflectionTestUtils.setField(decoder, "maxControlCharRatio", 0.30);
    }

    @Test
    void utf8BomDecoded() throws Exception {
        byte[] withBom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'H', 'i'};
        assertEquals("Hi", decoder.decode(withBom));
    }

    @Test
    void plausibilityRejectsNulHeavy() {
        String s = "a\0b\0c\0d\0e";
        DocumentExtractionException ex = assertThrows(DocumentExtractionException.class,
                () -> decoder.assertPlausibleText(s));
        assertEquals(DocumentExtractionErrorCode.MALFORMED_FILE, ex.getCode());
    }

    @Test
    void plainUtf8Ok() throws Exception {
        assertEquals("hello", decoder.decode("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
