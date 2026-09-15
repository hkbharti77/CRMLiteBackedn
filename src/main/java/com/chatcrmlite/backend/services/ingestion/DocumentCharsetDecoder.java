package com.chatcrmlite.backend.services.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Shared BOM-aware charset decoding for CSV/TXT/MD/HTML ingestion.
 * Uses strict UTF-8 (REPORT), optional UTF-16, then configurable fallback.
 */
@Slf4j
@Component
public class DocumentCharsetDecoder {

    @Value("${rag.ingestion.text.fallback-charset:ISO-8859-1}")
    private String fallbackCharsetName = "ISO-8859-1";

    @Value("${rag.ingestion.text.max-control-char-ratio:0.30}")
    private double maxControlCharRatio = 0.30;

    public String decode(byte[] bytes) throws DocumentExtractionException {
        if (bytes == null || bytes.length == 0) {
            throw new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "Empty file content");
        }

        BomResult bom = detectBom(bytes);
        byte[] payload = bom.payload();

        if (bom.charset() != null) {
            try {
                String text = decodeStrict(payload, bom.charset());
                assertPlausibleText(text);
                return text;
            } catch (CharacterCodingException e) {
                throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.ENCODING_FAILURE,
                        "Failed to decode with BOM charset " + bom.charset().name(), e);
            }
        }

        // Strict UTF-8
        try {
            String text = decodeStrict(payload, StandardCharsets.UTF_8);
            assertPlausibleText(text);
            return text;
        } catch (CharacterCodingException utf8Fail) {
            // UTF-16 heuristic: many NULs in even/odd positions
            if (looksLikeUtf16(payload)) {
                try {
                    Charset utf16 = (payload.length >= 2 && (payload[0] & 0xFF) == 0)
                            ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
                    String text = decodeStrict(payload, utf16);
                    log.info("[DocumentExtraction] UTF-8 decode failed; using {} heuristic", utf16.name());
                    assertPlausibleText(text);
                    return text;
                } catch (CharacterCodingException ignored) {
                    // fall through
                }
            }

            Charset fallback;
            try {
                fallback = Charset.forName(fallbackCharsetName);
            } catch (Exception e) {
                throw new DocumentExtractionException(
                        DocumentExtractionErrorCode.ENCODING_FAILURE,
                        "Invalid fallback charset: " + fallbackCharsetName, e);
            }

            log.info("[DocumentExtraction] UTF-8 decode failed; using {} fallback", fallback.name());
            try {
                String text = decodeStrict(payload, fallback);
                assertPlausibleText(text);
                return text;
            } catch (CharacterCodingException e) {
                // ISO-8859-1 never fails with REPORT on single bytes, but keep path
                String text = new String(payload, fallback);
                assertPlausibleText(text);
                return text;
            }
        }
    }

    /**
     * Reject binary-like decoded text (renamed malware.bin → file.csv).
     */
    public void assertPlausibleText(String text) throws DocumentExtractionException {
        if (text == null || text.isBlank()) {
            throw new DocumentExtractionException(DocumentExtractionErrorCode.EMPTY_DOCUMENT, "Decoded text is empty");
        }

        int sampleLen = Math.min(text.length(), 50_000);
        int controlOrNonPrintable = 0;
        int nulCount = 0;
        for (int i = 0; i < sampleLen; i++) {
            char c = text.charAt(i);
            if (c == '\0') {
                nulCount++;
                controlOrNonPrintable++;
                continue;
            }
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c) || (c < 0x20) || (c == 0x7F)) {
                controlOrNonPrintable++;
            }
        }

        if (nulCount > 2) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE,
                    "Text content contains excessive NUL bytes (likely binary renamed as text)");
        }

        double ratio = sampleLen == 0 ? 0 : (double) controlOrNonPrintable / sampleLen;
        if (ratio > maxControlCharRatio) {
            throw new DocumentExtractionException(
                    DocumentExtractionErrorCode.MALFORMED_FILE,
                    String.format(
                            "Text content fails plausibility check (control/non-printable ratio %.2f > %.2f)",
                            ratio, maxControlCharRatio));
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static BomResult detectBom(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            byte[] rest = new byte[bytes.length - 3];
            System.arraycopy(bytes, 3, rest, 0, rest.length);
            return new BomResult(StandardCharsets.UTF_8, rest);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xFE) {
            byte[] rest = new byte[bytes.length - 2];
            System.arraycopy(bytes, 2, rest, 0, rest.length);
            return new BomResult(StandardCharsets.UTF_16LE, rest);
        }
        if (bytes.length >= 2
                && (bytes[0] & 0xFF) == 0xFE
                && (bytes[1] & 0xFF) == 0xFF) {
            byte[] rest = new byte[bytes.length - 2];
            System.arraycopy(bytes, 2, rest, 0, rest.length);
            return new BomResult(StandardCharsets.UTF_16BE, rest);
        }
        return new BomResult(null, bytes);
    }

    private static boolean looksLikeUtf16(byte[] bytes) {
        if (bytes.length < 4) return false;
        int nulEven = 0;
        int nulOdd = 0;
        int limit = Math.min(bytes.length, 200);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                if ((i % 2) == 0) nulEven++;
                else nulOdd++;
            }
        }
        return nulEven > limit / 8 || nulOdd > limit / 8;
    }

    private record BomResult(Charset charset, byte[] payload) {}
}
