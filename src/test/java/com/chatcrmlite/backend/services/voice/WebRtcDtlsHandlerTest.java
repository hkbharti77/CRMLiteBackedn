package com.chatcrmlite.backend.services.voice;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.TlsCertificate;
import org.bouncycastle.tls.crypto.TlsCryptoParameters;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaDefaultTlsCredentialedSigner;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCertificate;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCrypto;
import org.bouncycastle.tls.crypto.impl.jcajce.JcaTlsCryptoProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;
import java.util.Hashtable;
import java.util.Vector;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

public class WebRtcDtlsHandlerTest {

    static class PipeTransport implements DatagramTransport {
        private final BlockingQueue<byte[]> in;
        private final BlockingQueue<byte[]> out;
        private volatile boolean closed = false;

        PipeTransport(BlockingQueue<byte[]> in, BlockingQueue<byte[]> out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public int getReceiveLimit() { return 1500; }

        @Override
        public int getSendLimit() { return 1500; }

        @Override
        public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
            try {
                byte[] pkt = in.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (pkt == null) return -1;
                int n = Math.min(len, pkt.length);
                System.arraycopy(pkt, 0, buf, off, n);
                return n;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            }
        }

        @Override
        public void send(byte[] buf, int off, int len) throws IOException {
            if (closed) return;
            byte[] copy = new byte[len];
            System.arraycopy(buf, off, copy, 0, len);
            out.offer(copy);
        }

        @Override
        public void close() { closed = true; }
    }

    static class WebRtcTlsServer extends DefaultTlsServer {
        private final X509Certificate serverCert;
        private final KeyPair serverKeyPair;
        private final JcaTlsCrypto crypto;
        byte[] srtpKeyingMaterial;
        int selectedSrtpProfile = -1;

        WebRtcTlsServer(JcaTlsCrypto crypto, X509Certificate serverCert, KeyPair serverKeyPair) {
            super(crypto);
            this.crypto = crypto;
            this.serverCert = serverCert;
            this.serverKeyPair = serverKeyPair;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            return ProtocolVersion.DTLSv12.only();
        }

        @Override
        public int[] getCipherSuites() {
            return new int[]{
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            };
        }

        @Override
        public TlsCredentials getCredentials() throws IOException {
            JcaTlsCertificate jcaCert = new JcaTlsCertificate(crypto, serverCert);
            Certificate cert = new Certificate(new TlsCertificate[]{jcaCert});
            SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
            return new JcaDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto, serverKeyPair.getPrivate(), cert, sigAlg);
        }

        @Override
        public CertificateRequest getCertificateRequest() throws IOException {
            Vector<SignatureAndHashAlgorithm> sigAlgs = new Vector<>();
            sigAlgs.add(new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa));
            sigAlgs.add(new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.rsa));
            short[] certTypes = new short[]{ClientCertificateType.ecdsa_sign, ClientCertificateType.rsa_sign};
            return new CertificateRequest(certTypes, sigAlgs, null);
        }

        @Override
        public void notifyClientCertificate(Certificate clientCertificate) throws IOException {
        }

        @Override
        public Hashtable getServerExtensions() throws IOException {
            Hashtable extensions = super.getServerExtensions();
            if (extensions == null) extensions = new Hashtable();
            int[] profiles = new int[]{SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80};
            TlsSRTPUtils.addUseSRTPExtension(extensions, new UseSRTPData(profiles, new byte[0]));
            return extensions;
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            this.srtpKeyingMaterial = context.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
        }
    }

    static class WebRtcTlsClient extends DefaultTlsClient {
        private final X509Certificate clientCert;
        private final KeyPair clientKeyPair;
        private final JcaTlsCrypto crypto;
        byte[] srtpKeyingMaterial;
        int selectedSrtpProfile = -1;

        WebRtcTlsClient(JcaTlsCrypto crypto, X509Certificate clientCert, KeyPair clientKeyPair) {
            super(crypto);
            this.crypto = crypto;
            this.clientCert = clientCert;
            this.clientKeyPair = clientKeyPair;
        }

        @Override
        public ProtocolVersion[] getProtocolVersions() {
            return ProtocolVersion.DTLSv12.only();
        }

        @Override
        public int[] getCipherSuites() {
            return new int[]{
                    CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                    CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
            };
        }

        @Override
        public TlsAuthentication getAuthentication() throws IOException {
            return new TlsAuthentication() {
                @Override
                public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                }

                @Override
                public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException {
                    JcaTlsCertificate jcaCert = new JcaTlsCertificate(crypto, clientCert);
                    Certificate cert = new Certificate(new TlsCertificate[]{jcaCert});
                    SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
                    return new JcaDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto, clientKeyPair.getPrivate(), cert, sigAlg);
                }
            };
        }

        @Override
        public Hashtable getClientExtensions() throws IOException {
            Hashtable extensions = super.getClientExtensions();
            if (extensions == null) extensions = new Hashtable();
            int[] profiles = new int[]{
                    SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
                    SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM
            };
            TlsSRTPUtils.addUseSRTPExtension(extensions, new UseSRTPData(profiles, new byte[0]));
            return extensions;
        }

        @Override
        public void processServerExtensions(Hashtable serverExtensions) throws IOException {
            super.processServerExtensions(serverExtensions);
            UseSRTPData srtpData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions);
            if (srtpData != null && srtpData.getProtectionProfiles() != null && srtpData.getProtectionProfiles().length > 0) {
                this.selectedSrtpProfile = srtpData.getProtectionProfiles()[0];
            }
        }

        @Override
        public void notifyHandshakeComplete() throws IOException {
            super.notifyHandshakeComplete();
            this.srtpKeyingMaterial = context.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
        }
    }

    @Test
    public void testDtlsHandshakeAndSrtpRoundtrip() throws Exception {
        KeyPairGenerator ecKeyGen = KeyPairGenerator.getInstance("EC");
        ecKeyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair clientKeyPair = ecKeyGen.generateKeyPair();
        KeyPair serverKeyPair = ecKeyGen.generateKeyPair();

        X509Certificate clientCert = generateCert("CN=CRMLite-Client", clientKeyPair, "SHA256withECDSA");
        X509Certificate serverCert = generateCert("CN=Meta-WebRTC", serverKeyPair, "SHA256withECDSA");

        BlockingQueue<byte[]> clientToServer = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> serverToClient = new LinkedBlockingQueue<>();

        PipeTransport clientTransport = new PipeTransport(serverToClient, clientToServer);
        PipeTransport serverTransport = new PipeTransport(clientToServer, serverToClient);

        JcaTlsCrypto clientCrypto = new JcaTlsCryptoProvider().create(new SecureRandom());
        JcaTlsCrypto serverCrypto = new JcaTlsCryptoProvider().create(new SecureRandom());

        CountDownLatch latch = new CountDownLatch(2);
        final DTLSTransport[] clientDtls = new DTLSTransport[1];
        final DTLSTransport[] serverDtls = new DTLSTransport[1];

        WebRtcTlsServer server = new WebRtcTlsServer(serverCrypto, serverCert, serverKeyPair);
        WebRtcTlsClient client = new WebRtcTlsClient(clientCrypto, clientCert, clientKeyPair);

        ExecutorService exec = Executors.newFixedThreadPool(2);

        exec.submit(() -> {
            try {
                DTLSServerProtocol protocol = new DTLSServerProtocol();
                serverDtls[0] = protocol.accept(server, serverTransport);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        exec.submit(() -> {
            try {
                DTLSClientProtocol protocol = new DTLSClientProtocol();
                clientDtls[0] = protocol.connect(client, clientTransport);
                latch.countDown();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "DTLS Handshake timed out");
        assertNotNull(clientDtls[0], "Client DTLS should not be null");
        assertNotNull(serverDtls[0], "Server DTLS should not be null");
        assertNotNull(client.srtpKeyingMaterial, "Client SRTP keying material should be exported");
        assertNotNull(server.srtpKeyingMaterial, "Server SRTP keying material should be exported");
        assertArrayEquals(server.srtpKeyingMaterial, client.srtpKeyingMaterial, "Exported SRTP keying material must match");
        assertEquals(SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80, client.selectedSrtpProfile);

        // Test SRTP encryption and decryption with derived keys
        byte[] keyingMaterial = client.srtpKeyingMaterial;
        byte[] clientMasterKey = Arrays.copyOfRange(keyingMaterial, 0, 16);
        byte[] serverMasterKey = Arrays.copyOfRange(keyingMaterial, 16, 32);
        byte[] clientMasterSalt = Arrays.copyOfRange(keyingMaterial, 32, 46);
        byte[] serverMasterSalt = Arrays.copyOfRange(keyingMaterial, 46, 60);

        SrtpTransformer senderTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, true);
        SrtpTransformer receiverTransformer = new SrtpTransformer(clientMasterKey, clientMasterSalt, false);

        // Build sample RTP packet (Opus comfort frame)
        ByteBuffer rtpBuf = ByteBuffer.allocate(15);
        rtpBuf.put((byte) 0x80); // V=2
        rtpBuf.put((byte) 111);  // PT=111
        rtpBuf.putShort((short) 1001); // SeqNum
        rtpBuf.putInt(960);      // Timestamp
        rtpBuf.putInt(850231558); // SSRC
        rtpBuf.put(new byte[]{(byte) 0xF8, (byte) 0xFF, (byte) 0xFE}); // Opus silence

        byte[] plainRtp = rtpBuf.array();
        byte[] encryptedSrtp = senderTransformer.encryptRtp(plainRtp);

        assertNotNull(encryptedSrtp);
        assertEquals(plainRtp.length + 10, encryptedSrtp.length, "SRTP packet should be RTP length + 10 bytes HMAC-SHA1 tag");
        assertFalse(Arrays.equals(plainRtp, encryptedSrtp), "Encrypted payload must differ from plain RTP");

        // Decrypt with receiver transformer
        byte[] decryptedRtp = receiverTransformer.decryptSrtp(encryptedSrtp);
        assertNotNull(decryptedRtp, "Decrypted RTP must not be null");
        assertArrayEquals(plainRtp, decryptedRtp, "Decrypted RTP must exactly match original plaintext RTP");
    }

    @Test
    void testRfc3711TestVectors() {
        byte[] masterKey = java.util.HexFormat.of().parseHex("E1F97A0D3E018BE0D64FA32C06DE4139");
        byte[] masterSalt = java.util.HexFormat.of().parseHex("0EC675AD498AFEEBB6960B3AABE6");

        SrtpTransformer transformer = new SrtpTransformer(masterKey, masterSalt, true);

        // Expected keys from RFC 3711 Appendix B.3:
        // Cipher Key:  C61E7A93744F39EE10734AFE3FF7A087
        // Auth Key:    CEBE321F6FF7716B6FD4AB49AF256A156D38BAA4 (first 20 bytes)
        // Cipher Salt: 30CBBC08863D8C85D49DB34A9AE1
        assertEquals("c61e7a93744f39ee10734afe3ff7a087", java.util.HexFormat.of().formatHex(transformer.getEncKey()).toLowerCase());
        assertEquals("cebe321f6ff7716b6fd4ab49af256a156d38baa4", java.util.HexFormat.of().formatHex(transformer.getAuthKey()).toLowerCase());
        assertEquals("30cbbc08863d8c85d49db34a9ae1", java.util.HexFormat.of().formatHex(transformer.getSaltKey()).toLowerCase());
    }

    private static X509Certificate generateCert(String dnStr, KeyPair kp, String sigAlg) throws Exception {
        long now = System.currentTimeMillis();
        Date startDate = new Date(now - 24 * 60 * 60 * 1000L);
        Date endDate = new Date(now + 365 * 24 * 60 * 60 * 1000L);
        BigInteger serial = BigInteger.valueOf(now);
        X500Name dn = new X500Name(dnStr);

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                dn, serial, startDate, endDate, dn, kp.getPublic()
        );

        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).build(kp.getPrivate());
        X509CertificateHolder certHolder = certBuilder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(certHolder);
    }
}
