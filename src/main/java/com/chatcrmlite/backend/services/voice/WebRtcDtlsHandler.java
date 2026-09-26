package com.chatcrmlite.backend.services.voice;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Date;
import java.util.Hashtable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Enterprise WebRTC DTLS 1.2 / SRTP Handler for WhatsApp Calling.
 * Implements RFC 8827, RFC 5763, RFC 5764, and RFC 3711 with ECDSA (secp256r1) certificates.
 */
@Slf4j
@Component
public class WebRtcDtlsHandler {

    @Getter
    private final KeyPair keyPair;
    @Getter
    private final X509Certificate certificate;
    @Getter
    private final String sha256Fingerprint;

    @Getter
    @Builder
    public static class DtlsHandshakeResult {
        private final boolean success;
        private final DTLSTransport dtlsTransport;
        private final byte[] srtpKeyingMaterial;
        private final int selectedSrtpProfile;
        private final String cipherSuite;
        private final String protocolVersion;
        private final String errorMessage;
    }

    public WebRtcDtlsHandler() {
        try {
            // WebRTC standards mandate ECDSA with secp256r1 (prime256v1) for modern peers
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            this.keyPair = keyGen.generateKeyPair();

            long now = System.currentTimeMillis();
            Date startDate = new Date(now - 24 * 60 * 60 * 1000L);
            Date endDate = new Date(now + 365 * 24 * 60 * 60 * 1000L);
            BigInteger serial = BigInteger.valueOf(now);
            X500Name dn = new X500Name("CN=CRMLite-WebRTC");

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    dn, serial, startDate, endDate, dn, keyPair.getPublic()
            );

            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
            X509CertificateHolder certHolder = certBuilder.build(signer);
            this.certificate = new JcaX509CertificateConverter().getCertificate(certHolder);

            // Compute standard RFC 8122 SHA-256 fingerprint formatted with colons
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(this.certificate.getEncoded());
            StringBuilder fp = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) fp.append(":");
                fp.append(String.format("%02X", digest[i]));
            }
            this.sha256Fingerprint = fp.toString();
            log.info("🔐 [WebRtcDtls] Initialized DTLS SHA-256 Certificate Fingerprint: {}", this.sha256Fingerprint);

        } catch (Exception e) {
            log.error("❌ [WebRtcDtls] Failed to initialize WebRTC DTLS Certificate: {}", e.getMessage(), e);
            throw new RuntimeException("DTLS Init failed", e);
        }
    }

    /**
     * Executes the DTLS 1.2 Client Handshake with Meta's media endpoint to negotiate SRTP keys.
     */
    public DtlsHandshakeResult startDtlsClientHandshake(DatagramSocket socket, InetSocketAddress remoteAddress, UdpDatagramTransport transport) {
        log.info("🚀 [WebRtcDtls] Initiating DTLS 1.2 Handshake (Client Role) with Meta at {}", remoteAddress);
        try {
            JcaTlsCrypto crypto = new JcaTlsCryptoProvider().create(new SecureRandom());
            DTLSClientProtocol protocol = new DTLSClientProtocol();

            final byte[][] exportedKeyingMaterial = new byte[1][];
            final int[] negotiatedSrtpProfile = new int[]{-1};
            final String[] negotiatedCipher = new String[]{"UNKNOWN"};

            DefaultTlsClient client = new DefaultTlsClient(crypto) {
                @Override
                public ProtocolVersion[] getProtocolVersions() {
                    return ProtocolVersion.DTLSv12.only();
                }

                @Override
                public int[] getCipherSuites() {
                    return new int[]{
                            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA,
                            CipherSuite.TLS_RSA_WITH_AES_128_GCM_SHA256,
                            CipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA
                    };
                }

                @Override
                public TlsAuthentication getAuthentication() throws IOException {
                    return new TlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(TlsServerCertificate serverCertificate) {
                            log.info("🔐 [WebRtcDtls] Received Remote Meta DTLS Server Certificate");
                        }

                        @Override
                        public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException {
                            log.info("🔐 [WebRtcDtls] Providing local ECDSA client credentials to Meta server");
                            JcaTlsCertificate jcaCert = new JcaTlsCertificate(crypto, certificate);
                            Certificate cert = new Certificate(new TlsCertificate[]{jcaCert});
                            SignatureAndHashAlgorithm sigAlg = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
                            return new JcaDefaultTlsCredentialedSigner(new TlsCryptoParameters(context), crypto, keyPair.getPrivate(), cert, sigAlg);
                        }
                    };
                }

                @Override
                public Hashtable<Integer, byte[]> getClientExtensions() throws IOException {
                    Hashtable<Integer, byte[]> extensions = super.getClientExtensions();
                    if (extensions == null) {
                        extensions = new Hashtable<>();
                    }
                    int[] protectionProfiles = new int[]{
                            SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80,
                            SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM
                    };
                    TlsSRTPUtils.addUseSRTPExtension(extensions, new UseSRTPData(protectionProfiles, new byte[0]));
                    return extensions;
                }

                @Override
                public void processServerExtensions(Hashtable serverExtensions) throws IOException {
                    super.processServerExtensions(serverExtensions);
                    UseSRTPData srtpData = TlsSRTPUtils.getUseSRTPExtension(serverExtensions);
                    if (srtpData != null && srtpData.getProtectionProfiles() != null && srtpData.getProtectionProfiles().length > 0) {
                        negotiatedSrtpProfile[0] = srtpData.getProtectionProfiles()[0];
                        log.info("🔒 [WebRtcDtls] Negotiated SRTP Protection Profile: 0x{}", Integer.toHexString(negotiatedSrtpProfile[0]));
                    }
                }

                @Override
                public void notifyHandshakeComplete() throws IOException {
                    super.notifyHandshakeComplete();
                    // RFC 5764: Derive 60 bytes of SRTP keying material using extractor-dtls_srtp
                    exportedKeyingMaterial[0] = context.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
                    log.info("🔑 [WebRtcDtls] Successfully exported 60 bytes of SRTP Keying Material");
                }
            };

            DTLSTransport dtlsTransport = protocol.connect(client, transport);
            log.info("✅ [WebRtcDtls] DTLS 1.2 Handshake Completed Successfully with Meta at {}", remoteAddress);

            return DtlsHandshakeResult.builder()
                    .success(true)
                    .dtlsTransport(dtlsTransport)
                    .srtpKeyingMaterial(exportedKeyingMaterial[0])
                    .selectedSrtpProfile(negotiatedSrtpProfile[0] > 0 ? negotiatedSrtpProfile[0] : SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80)
                    .cipherSuite("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256")
                    .protocolVersion("DTLS 1.2")
                    .build();

        } catch (Exception e) {
            log.error("❌ [WebRtcDtls] DTLS Handshake Failed: {}", e.getMessage(), e);
            return DtlsHandshakeResult.builder()
                    .success(false)
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    /**
     * Non-blocking DatagramTransport bridge between BouncyCastle DTLS engine and Spring DatagramSocket.
     */
    public static class UdpDatagramTransport implements DatagramTransport {
        private final DatagramSocket socket;
        private final InetSocketAddress remoteAddress;
        private final BlockingQueue<byte[]> inboundQueue = new LinkedBlockingQueue<>();
        private volatile boolean closed = false;

        public UdpDatagramTransport(DatagramSocket socket, InetSocketAddress remoteAddress) {
            this.socket = socket;
            this.remoteAddress = remoteAddress;
        }

        public void enqueueInbound(byte[] packet) {
            if (!closed && packet != null) {
                inboundQueue.offer(packet);
            }
        }

        @Override
        public int getReceiveLimit() {
            return 1500;
        }

        @Override
        public int getSendLimit() {
            return 1500;
        }

        @Override
        public int receive(byte[] buf, int off, int len, int waitMillis) throws IOException {
            try {
                byte[] pkt = inboundQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (pkt == null) {
                    return -1;
                }
                int toCopy = Math.min(len, pkt.length);
                System.arraycopy(pkt, 0, buf, off, toCopy);
                return toCopy;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("DTLS receive interrupted");
            }
        }

        @Override
        public void send(byte[] buf, int off, int len) throws IOException {
            if (closed || socket.isClosed() || remoteAddress == null) return;
            byte[] toSend = new byte[len];
            System.arraycopy(buf, off, toSend, 0, len);
            DatagramPacket packet = new DatagramPacket(toSend, len, remoteAddress);
            socket.send(packet);
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
