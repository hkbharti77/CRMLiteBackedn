package com.chatcrmlite.backend.services.voice;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class DtlsSrtpCryptographicTest {

    private WebRtcDtlsHandler dtlsHandler;

    private static final byte[] MASTER_KEY = HexFormat.of().parseHex("E1F97A0D3E018BE0D64FA32C06DE4139");
    private static final byte[] MASTER_SALT = HexFormat.of().parseHex("0EC675AD498AFEEBB6960B3AABE6");

    @BeforeEach
    void setUp() {
        dtlsHandler = new WebRtcDtlsHandler();
    }

    @Test
    @DisplayName("DTLS certificate generation creates valid ECDSA secp256r1 certificate and SHA-256 fingerprint")
    void testDtlsCertificateGeneration() {
        assertNotNull(dtlsHandler.getKeyPair());
        assertNotNull(dtlsHandler.getCertificate());
        assertNotNull(dtlsHandler.getSha256Fingerprint());

        X509Certificate cert = dtlsHandler.getCertificate();
        assertEquals("EC", dtlsHandler.getKeyPair().getPublic().getAlgorithm());
        assertEquals("SHA256WITHECDSA", cert.getSigAlgName().toUpperCase());

        // Validate fingerprint format (hex pairs separated by colons)
        String fp = dtlsHandler.getSha256Fingerprint();
        assertTrue(fp.matches("^([0-9A-F]{2}:){31}[0-9A-F]{2}$"));
    }

    @Test
    @DisplayName("Fingerprint normalization and equality handles spaces, colons, and casing")
    void testFingerprintNormalizationAndEquality() {
        String fp1 = "aa:bb:cc:dd:11:22:33:44";
        String fp2 = "AA:BB:CC:DD:11:22:33:44";
        String fp3 = "AABBCCDD11223344";
        String fp4 = "aa bb cc dd 11 22 33 44";

        assertTrue(WebRtcDtlsHandler.fingerprintsEqual(fp1, fp2));
        assertTrue(WebRtcDtlsHandler.fingerprintsEqual(fp1, fp3));
        assertTrue(WebRtcDtlsHandler.fingerprintsEqual(fp2, fp4));
        assertFalse(WebRtcDtlsHandler.fingerprintsEqual(fp1, "FF:FF:FF:FF:11:22:33:44"));
        assertFalse(WebRtcDtlsHandler.fingerprintsEqual(null, fp1));
    }

    @Test
    @DisplayName("Local fingerprint mismatch precheck fails immediately before handshake starts")
    void testMismatchedLocalFingerprintFailsPrecheck() {
        WebRtcDtlsHandler.DtlsDiagnostics diagnostics = new WebRtcDtlsHandler.DtlsDiagnostics();
        WebRtcDtlsHandler.UdpDatagramTransport transport = new WebRtcDtlsHandler.UdpDatagramTransport(null, null, diagnostics);

        String badAdvertisedFp = "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00";

        WebRtcDtlsHandler.DtlsHandshakeResult result = dtlsHandler.startDtlsClientHandshake(
                null, null, transport, "remote_fp", badAdvertisedFp, 1000
        );

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertTrue(result.getErrorMessage().contains("fingerprint does not match"));
        assertEquals("FAILED", diagnostics.getDtlsEngineStatus().get());
    }

    @Test
    @DisplayName("SRTP key derivation conforms to RFC 3711 Section 4.3")
    void testSrtpKeyDerivationRFC3711() {
        SrtpTransformer transformer = new SrtpTransformer(MASTER_KEY, MASTER_SALT, true);

        // Expected keys from RFC 3711 Appendix B.3:
        assertEquals("c61e7a93744f39ee10734afe3ff7a087", HexFormat.of().formatHex(transformer.getEncKey()).toLowerCase());
        assertEquals("cebe321f6ff7716b6fd4ab49af256a156d38baa4", HexFormat.of().formatHex(transformer.getAuthKey()).toLowerCase());
        assertEquals("30cbbc08863d8c85d49db34a9ae1", HexFormat.of().formatHex(transformer.getSaltKey()).toLowerCase());
    }

    @Test
    @DisplayName("SRTP encrypt and decrypt round trip recovers exact plaintext RTP packet")
    void testSrtpEncryptDecryptRoundTrip() {
        SrtpTransformer sender = new SrtpTransformer(MASTER_KEY, MASTER_SALT, true);
        SrtpTransformer receiver = new SrtpTransformer(MASTER_KEY, MASTER_SALT, false);

        // Construct 12-byte RTP header + 3-byte payload
        ByteBuffer buf = ByteBuffer.allocate(15);
        buf.put((byte) 0x80); // V=2
        buf.put((byte) 111);  // PT=111
        buf.putShort((short) 1005); // Seq
        buf.putInt(1920);     // Timestamp
        buf.putInt(850231558); // SSRC
        buf.put(new byte[]{(byte) 0x01, (byte) 0x02, (byte) 0x03}); // Payload

        byte[] plainRtp = buf.array();
        byte[] encryptedSrtp = sender.encryptRtp(plainRtp);

        assertNotNull(encryptedSrtp);
        assertEquals(plainRtp.length + 10, encryptedSrtp.length, "SRTP must append 10-byte auth tag");
        assertFalse(Arrays.equals(plainRtp, encryptedSrtp), "Encrypted payload must differ from plaintext");

        byte[] decryptedRtp = receiver.decryptSrtp(encryptedSrtp);
        assertNotNull(decryptedRtp);
        assertArrayEquals(plainRtp, decryptedRtp, "Decrypted RTP must exactly match plaintext RTP");
    }

    @Test
    @DisplayName("SRTP authentication tag failure rejects tampered packet")
    void testSrtpAuthenticationTagTamperingRejection() {
        SrtpTransformer sender = new SrtpTransformer(MASTER_KEY, MASTER_SALT, true);
        SrtpTransformer receiver = new SrtpTransformer(MASTER_KEY, MASTER_SALT, false);

        ByteBuffer buf = ByteBuffer.allocate(15);
        buf.put((byte) 0x80);
        buf.put((byte) 111);
        buf.putShort((short) 1006);
        buf.putInt(2880);
        buf.putInt(850231558);
        buf.put(new byte[]{(byte) 0xAA, (byte) 0xBB, (byte) 0xCC});

        byte[] plainRtp = buf.array();
        byte[] encryptedSrtp = sender.encryptRtp(plainRtp);

        // Tamper with payload byte
        byte[] tamperedPayload = Arrays.copyOf(encryptedSrtp, encryptedSrtp.length);
        tamperedPayload[13] ^= 0x01;
        assertNull(receiver.decryptSrtp(tamperedPayload), "Tampered payload must fail auth check and return null");

        // Tamper with auth tag byte
        byte[] tamperedTag = Arrays.copyOf(encryptedSrtp, encryptedSrtp.length);
        tamperedTag[tamperedTag.length - 1] ^= 0xFF;
        assertNull(receiver.decryptSrtp(tamperedTag), "Tampered auth tag must fail auth check and return null");
    }

    @Test
    @DisplayName("SRTP decrypt rejects short or malformed packets (< 22 bytes)")
    void testSrtpShortMalformedPackets() {
        SrtpTransformer receiver = new SrtpTransformer(MASTER_KEY, MASTER_SALT, false);

        assertNull(receiver.decryptSrtp(null));
        assertNull(receiver.decryptSrtp(new byte[0]));
        assertNull(receiver.decryptSrtp(new byte[10]));
        assertNull(receiver.decryptSrtp(new byte[21])); // 21 is less than min 22
    }

    @Test
    @DisplayName("SRTP encrypt handles null or empty safely")
    void testSrtpEncryptNullOrEmpty() {
        SrtpTransformer sender = new SrtpTransformer(MASTER_KEY, MASTER_SALT, true);

        assertNull(sender.encryptRtp(null));
        assertEquals(0, sender.encryptRtp(new byte[0]).length);
        assertEquals(11, sender.encryptRtp(new byte[11]).length); // Less than 12 byte RTP header returns input
    }
}
