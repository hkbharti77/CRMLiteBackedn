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
import java.util.Vector;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Enterprise WebRTC DTLS 1.2 / SRTP Handler for WhatsApp Calling.
 * Implements RFC 8827, RFC 5763, RFC 5764, and RFC 3711 with ECDSA (secp256r1) certificates.
 * <p>
 * Uses BouncyCastle {@link DTLSClientProtocol} (not SSLEngine). The DatagramTransport
 * send/receive path is the functional equivalent of SSLEngine wrap/unwrap; diagnostics
 * mirror NEED_WRAP / NEED_UNWRAP / FINISHED / NOT_HANDSHAKING.
 */
@Slf4j
@Component
public class WebRtcDtlsHandler {

    public static final int DEFAULT_DTLS_HANDSHAKE_TIMEOUT_MS = 15_000;

    @Getter
    private final KeyPair keyPair;
    @Getter
    private final X509Certificate certificate;
    @Getter
    private final String sha256Fingerprint;

    /**
     * Shared DTLS/ICE counters and handshake status for session diagnostics.
     */
    @Getter
    public static class DtlsDiagnostics {
        final AtomicLong inboundDtlsPackets = new AtomicLong();
        final AtomicLong outboundDtlsPackets = new AtomicLong();
        final AtomicLong inboundStunPackets = new AtomicLong();
        final AtomicLong outboundStunPackets = new AtomicLong();
        final AtomicLong dtlsWrapCalls = new AtomicLong();
        final AtomicLong dtlsUnwrapCalls = new AtomicLong();
        final AtomicLong delegatedTasksExecuted = new AtomicLong();
        final AtomicLong dtlsAlertReceived = new AtomicLong();
        final AtomicLong dtlsAlertSent = new AtomicLong();
        final AtomicBoolean dtlsHandshakeFinished = new AtomicBoolean(false);
        final AtomicReference<String> dtlsHandshakeStatus = new AtomicReference<>("NOT_HANDSHAKING");
        final AtomicReference<String> dtlsEngineStatus = new AtomicReference<>("NEW");
        final AtomicReference<String> dtlsException = new AtomicReference<>(null);
        final AtomicReference<String> lastAlertReceived = new AtomicReference<>(null);
        final AtomicReference<String> lastAlertSent = new AtomicReference<>(null);
        final AtomicReference<String> advertisedLocalFingerprint = new AtomicReference<>(null);
        final AtomicReference<String> actualCertificateFingerprint = new AtomicReference<>(null);
        final AtomicReference<String> remoteFingerprintExpected = new AtomicReference<>(null);
        final AtomicReference<String> remoteFingerprintActual = new AtomicReference<>(null);
        final AtomicReference<String> certificateKeyAlgorithm = new AtomicReference<>(null);
        final AtomicReference<String> signatureAlgorithm = new AtomicReference<>(null);
        final AtomicReference<String> certificateNotAfter = new AtomicReference<>(null);
        final AtomicReference<String> selectedSrtpProfileName = new AtomicReference<>(null);
        final AtomicReference<String> srtpKeyInitResult = new AtomicReference<>("NOT_STARTED");

        public void setHandshakeStatus(String status) {
            dtlsHandshakeStatus.set(status);
        }

        public void setEngineStatus(String status) {
            dtlsEngineStatus.set(status);
        }
    }

    @Getter
    @Builder
    public static class DtlsHandshakeResult {
        private final boolean success;
        private final DTLSTransport dtlsTransport;
        private final byte[] srtpKeyingMaterial;
        private final int selectedSrtpProfile;
        private final String cipherSuite;
        private final String protocolVersion;
        private final String remoteCertificateFingerprint;
        private final String localCertificateFingerprint;
        private final String errorMessage;
        private final DtlsDiagnostics diagnostics;
    }

    public WebRtcDtlsHandler() {
        try {
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

            this.sha256Fingerprint = computeSha256Fingerprint(this.certificate.getEncoded());
            log.info("🔐 [WebRtcDtls] Initialized DTLS SHA-256 Certificate Fingerprint: {}", this.sha256Fingerprint);
            log.info("🔐 [WebRtcDtls] certificateKeyAlgorithm={} signatureAlgorithm={} certificateNotAfter={}",
                    keyPair.getPublic().getAlgorithm(), certificate.getSigAlgName(), certificate.getNotAfter());

        } catch (Exception e) {
            log.error("❌ [WebRtcDtls] Failed to initialize WebRTC DTLS Certificate: {}", e.getMessage(), e);
            throw new RuntimeException("DTLS Init failed", e);
        }
    }

    public static String computeSha256Fingerprint(byte[] derEncoded) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(derEncoded);
            StringBuilder fp = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) fp.append(":");
                fp.append(String.format("%02X", digest[i]));
            }
            return fp.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public static String normalizeFingerprint(String fp) {
        if (fp == null) return null;
        return fp.replace(":", "").replace(" ", "").toUpperCase();
    }

    public static boolean fingerprintsEqual(String a, String b) {
        String na = normalizeFingerprint(a);
        String nb = normalizeFingerprint(b);
        return na != null && na.equals(nb);
    }

    /**
     * Executes the DTLS 1.2 Client Handshake with Meta's media endpoint to negotiate SRTP keys.
     */
    public DtlsHandshakeResult startDtlsClientHandshake(DatagramSocket socket,
                                                        InetSocketAddress remoteAddress,
                                                        UdpDatagramTransport transport,
                                                        String expectedRemoteFingerprint,
                                                        String advertisedLocalFingerprint,
                                                        int handshakeTimeoutMs) {
        DtlsDiagnostics diag = transport.getDiagnostics();
        diag.advertisedLocalFingerprint.set(advertisedLocalFingerprint);
        diag.actualCertificateFingerprint.set(sha256Fingerprint);
        diag.remoteFingerprintExpected.set(expectedRemoteFingerprint);
        diag.certificateKeyAlgorithm.set(keyPair.getPublic().getAlgorithm());
        diag.signatureAlgorithm.set(certificate.getSigAlgName());
        diag.certificateNotAfter.set(String.valueOf(certificate.getNotAfter()));
        diag.setEngineStatus("DTLS_CLIENT_STARTING");
        diag.setHandshakeStatus("NEED_WRAP");

        log.info("🚀 [WebRtcDtls] DTLS_HANDSHAKE_STARTED: role=CLIENT setup=active remote={} timeoutMs={}",
                remoteAddress, handshakeTimeoutMs);
        log.info("🔐 [WebRtcDtls] CERT_VERIFY_PRECHECK advertisedLocalFingerprint={} actualCertificateFingerprint={} match={}",
                advertisedLocalFingerprint, sha256Fingerprint,
                fingerprintsEqual(advertisedLocalFingerprint, sha256Fingerprint));
        if (!fingerprintsEqual(advertisedLocalFingerprint, sha256Fingerprint)) {
            String err = "SDP advertised fingerprint does not match SSLEngine/BC local certificate fingerprint";
            diag.dtlsException.set(err);
            diag.setEngineStatus("FAILED");
            return DtlsHandshakeResult.builder()
                    .success(false)
                    .errorMessage(err)
                    .diagnostics(diag)
                    .localCertificateFingerprint(sha256Fingerprint)
                    .build();
        }

        try {
            JcaTlsCrypto crypto = new JcaTlsCryptoProvider().create(new SecureRandom());
            DTLSClientProtocol protocol = new DTLSClientProtocol();

            final byte[][] exportedKeyingMaterial = new byte[1][];
            final int[] negotiatedSrtpProfile = new int[]{-1};
            final String[] remoteCertFingerprint = new String[]{"UNKNOWN"};
            final String[] negotiatedCipher = new String[]{"UNKNOWN"};
            final int effectiveTimeout = handshakeTimeoutMs > 0 ? handshakeTimeoutMs : DEFAULT_DTLS_HANDSHAKE_TIMEOUT_MS;

            DefaultTlsClient client = new DefaultTlsClient(crypto) {
                @Override
                public ProtocolVersion[] getProtocolVersions() {
                    return ProtocolVersion.DTLSv12.only();
                }

                @Override
                public int getHandshakeTimeoutMillis() {
                    // BC default is 0 (infinite) — that hides DTLS failure behind Meta MEDIA_RECEIVE_TIMEOUT
                    return effectiveTimeout;
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
                public void notifyHandshakeBeginning() throws IOException {
                    super.notifyHandshakeBeginning();
                    diag.setHandshakeStatus("NEED_WRAP");
                    diag.setEngineStatus("HANDSHAKE_BEGINNING");
                    log.info("🔐 [WebRtcDtls] DTLS_HANDSHAKE_BEGINNING (ClientHello outbound to {}) status=NEED_WRAP", remoteAddress);
                }

                @Override
                public void notifySelectedCipherSuite(int cipherSuite) {
                    negotiatedCipher[0] = "0x" + Integer.toHexString(cipherSuite);
                    log.info("🔐 [WebRtcDtls] Negotiated DTLS cipherSuite={}", negotiatedCipher[0]);
                }

                @Override
                public TlsAuthentication getAuthentication() throws IOException {
                    return new TlsAuthentication() {
                        @Override
                        public void notifyServerCertificate(TlsServerCertificate serverCertificate) throws IOException {
                            diag.setHandshakeStatus("NEED_UNWRAP");
                            if (serverCertificate == null || serverCertificate.getCertificate() == null) {
                                diag.dtlsException.set("Empty server certificate");
                                throw new TlsFatalAlert(AlertDescription.bad_certificate);
                            }
                            TlsCertificate[] chain = serverCertificate.getCertificate().getCertificateList();
                            if (chain == null || chain.length == 0) {
                                diag.dtlsException.set("Empty server certificate chain");
                                throw new TlsFatalAlert(AlertDescription.bad_certificate);
                            }
                            byte[] der = chain[0].getEncoded();
                            remoteCertFingerprint[0] = computeSha256Fingerprint(der);
                            diag.remoteFingerprintActual.set(remoteCertFingerprint[0]);
                            log.info("🔐 [WebRtcDtls] DTLS_SERVER_CERT_RECEIVED remoteFingerprint={} expected={}",
                                    remoteCertFingerprint[0], expectedRemoteFingerprint);

                            if (expectedRemoteFingerprint != null && !expectedRemoteFingerprint.isBlank()
                                    && !fingerprintsEqual(expectedRemoteFingerprint, remoteCertFingerprint[0])) {
                                String err = "Remote DTLS certificate fingerprint mismatch: expected="
                                        + expectedRemoteFingerprint + " actual=" + remoteCertFingerprint[0];
                                diag.dtlsException.set(err);
                                log.error("❌ [WebRtcDtls] {}", err);
                                throw new TlsFatalAlert(AlertDescription.bad_certificate);
                            }
                            log.info("🔐 [WebRtcDtls] Remote fingerprint verification PASSED");
                        }

                        @Override
                        public TlsCredentials getClientCredentials(CertificateRequest certificateRequest) throws IOException {
                            diag.setHandshakeStatus("NEED_TASK");
                            log.info("🔐 [WebRtcDtls] DTLS_CLIENT_CERT_REQUEST_RECEIVED: Providing local ECDSA client credentials (Fingerprint={})",
                                    sha256Fingerprint);

                            short[] certTypes = certificateRequest != null ? certificateRequest.getCertificateTypes() : null;
                            boolean ecdsaAllowed = false;
                            if (certTypes != null) {
                                for (short t : certTypes) {
                                    if (t == ClientCertificateType.ecdsa_sign) {
                                        ecdsaAllowed = true;
                                        break;
                                    }
                                }
                                log.info("🔐 [WebRtcDtls] CertificateRequest types={}", java.util.Arrays.toString(certTypes));
                            } else {
                                ecdsaAllowed = true;
                            }
                            if (!ecdsaAllowed) {
                                String err = "Meta CertificateRequest does not allow ecdsa_sign; cannot use local ECDSA cert";
                                diag.dtlsException.set(err);
                                log.error("❌ [WebRtcDtls] {}", err);
                                throw new TlsFatalAlert(AlertDescription.handshake_failure);
                            }

                            // Choose sigalg from Meta's offered list (do NOT hardcode blindly)
                            @SuppressWarnings("unchecked")
                            Vector<SignatureAndHashAlgorithm> offered =
                                    certificateRequest != null ? certificateRequest.getSupportedSignatureAlgorithms() : null;
                            SignatureAndHashAlgorithm sigAlg;
                            if (offered != null && !offered.isEmpty()) {
                                sigAlg = TlsUtils.chooseSignatureAndHashAlgorithm(context, offered, SignatureAlgorithm.ecdsa);
                                log.info("🔐 [WebRtcDtls] Selected SignatureAndHashAlgorithm from CertificateRequest: hash={} sig={}",
                                        HashAlgorithm.getName(sigAlg.getHash()), SignatureAlgorithm.getName(sigAlg.getSignature()));
                            } else {
                                sigAlg = new SignatureAndHashAlgorithm(HashAlgorithm.sha256, SignatureAlgorithm.ecdsa);
                                log.info("🔐 [WebRtcDtls] CertificateRequest has no sigalgs; using SHA256withECDSA");
                            }
                            diag.signatureAlgorithm.set(HashAlgorithm.getName(sigAlg.getHash()) + "with"
                                    + SignatureAlgorithm.getName(sigAlg.getSignature()).toUpperCase());

                            JcaTlsCertificate jcaCert = new JcaTlsCertificate(crypto, certificate);
                            Certificate cert = new Certificate(new TlsCertificate[]{jcaCert});
                            diag.delegatedTasksExecuted.incrementAndGet();
                            log.info("DTLS_TASK: task=BuildClientCredentials result=OK sigAlg={}", diag.signatureAlgorithm.get());
                            log.info("🔐 [WebRtcDtls] DTLS_CLIENT_CREDENTIALS_READY: will send Certificate+CertificateVerify+Finished (Fingerprint={})",
                                    sha256Fingerprint);
                            diag.setHandshakeStatus("NEED_WRAP");
                            return new JcaDefaultTlsCredentialedSigner(
                                    new TlsCryptoParameters(context), crypto, keyPair.getPrivate(), cert, sigAlg);
                        }
                    };
                }

                @Override
                public Hashtable getClientExtensions() throws IOException {
                    @SuppressWarnings("unchecked")
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
                        String profileName = srtpProfileName(negotiatedSrtpProfile[0]);
                        diag.selectedSrtpProfileName.set(profileName + " (0x" + Integer.toHexString(negotiatedSrtpProfile[0]) + ")");
                        log.info("🔒 [WebRtcDtls] Negotiated SRTP Protection Profile: {} — profile negotiated ONLY, SRTP NOT READY yet",
                                diag.selectedSrtpProfileName.get());
                    }
                }

                @Override
                public void notifyHandshakeComplete() throws IOException {
                    super.notifyHandshakeComplete();
                    exportedKeyingMaterial[0] = context.exportKeyingMaterial("EXTRACTOR-dtls_srtp", null, 60);
                    diag.dtlsHandshakeFinished.set(true);
                    diag.setHandshakeStatus("FINISHED");
                    diag.setEngineStatus("DTLS_CONNECTED");
                    diag.srtpKeyInitResult.set("KEY_MATERIAL_EXPORTED");
                    log.info("🔑 [WebRtcDtls] DTLS_HANDSHAKE_FINISHED: exported {} bytes EXTRACTOR-dtls_srtp",
                            exportedKeyingMaterial[0] != null ? exportedKeyingMaterial[0].length : 0);
                    log.info("✅ [WebRtcDtls] DTLS_CONNECTED handshakeStatus=FINISHED/NOT_HANDSHAKING");
                }

                @Override
                public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause) {
                    diag.dtlsAlertSent.incrementAndGet();
                    String alert = AlertDescription.getName(alertDescription) + "(level=" + alertLevel + ")";
                    diag.lastAlertSent.set(alert);
                    log.warn("⚠️ [WebRtcDtls] DTLS_ALERT_SENT: {} msg={} cause={}",
                            alert, message, (cause != null ? cause.getMessage() : "none"));
                }

                @Override
                public void notifyAlertReceived(short alertLevel, short alertDescription) {
                    diag.dtlsAlertReceived.incrementAndGet();
                    String alert = AlertDescription.getName(alertDescription) + "(level=" + alertLevel + ")";
                    diag.lastAlertReceived.set(alert);
                    diag.dtlsException.set("DTLS alert received: " + alert);
                    log.warn("⚠️ [WebRtcDtls] DTLS_ALERT_RECEIVED from Meta: {}", alert);
                }
            };

            diag.setEngineStatus("CONNECTING");
            DTLSTransport dtlsTransport = protocol.connect(client, transport);

            if (!diag.dtlsHandshakeFinished.get() || exportedKeyingMaterial[0] == null) {
                String err = "DTLS connect returned without FINISHED/key material";
                diag.dtlsException.set(err);
                diag.setEngineStatus("FAILED");
                return DtlsHandshakeResult.builder()
                        .success(false)
                        .errorMessage(err)
                        .diagnostics(diag)
                        .localCertificateFingerprint(sha256Fingerprint)
                        .remoteCertificateFingerprint(remoteCertFingerprint[0])
                        .build();
            }

            diag.setHandshakeStatus("NOT_HANDSHAKING");
            diag.setEngineStatus("DTLS_CONNECTED");
            log.info("✅ [WebRtcDtls] DTLS_CONNECTED: Handshake Completed with Meta at {} outboundDtls={} inboundDtls={}",
                    remoteAddress, diag.outboundDtlsPackets.get(), diag.inboundDtlsPackets.get());

            return DtlsHandshakeResult.builder()
                    .success(true)
                    .dtlsTransport(dtlsTransport)
                    .srtpKeyingMaterial(exportedKeyingMaterial[0])
                    .selectedSrtpProfile(negotiatedSrtpProfile[0] > 0 ? negotiatedSrtpProfile[0] : SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80)
                    .cipherSuite(negotiatedCipher[0])
                    .protocolVersion("DTLS 1.2")
                    .remoteCertificateFingerprint(remoteCertFingerprint[0])
                    .localCertificateFingerprint(sha256Fingerprint)
                    .diagnostics(diag)
                    .build();

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            diag.dtlsException.set(msg);
            diag.setEngineStatus("FAILED");
            diag.setHandshakeStatus("FAILED");
            log.error("❌ [WebRtcDtls] DTLS Handshake Failed: {} | status={} engine={} unwrap={} wrap={} outDtls={} inDtls={} alertRx={} alertTx={}",
                    msg, diag.dtlsHandshakeStatus.get(), diag.dtlsEngineStatus.get(),
                    diag.dtlsUnwrapCalls.get(), diag.dtlsWrapCalls.get(),
                    diag.outboundDtlsPackets.get(), diag.inboundDtlsPackets.get(),
                    diag.dtlsAlertReceived.get(), diag.dtlsAlertSent.get(), e);
            return DtlsHandshakeResult.builder()
                    .success(false)
                    .errorMessage(msg)
                    .diagnostics(diag)
                    .localCertificateFingerprint(sha256Fingerprint)
                    .build();
        }
    }

    private static String srtpProfileName(int profile) {
        if (profile == SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80) {
            return "SRTP_AES128_CM_HMAC_SHA1_80";
        }
        if (profile == SRTPProtectionProfile.SRTP_AEAD_AES_128_GCM) {
            return "SRTP_AEAD_AES_128_GCM";
        }
        return "UNKNOWN_PROFILE";
    }

    /**
     * DatagramTransport bridge: each receive() is NEED_UNWRAP, each send() is NEED_WRAP.
     * BC drives the handshake state machine inside {@link DTLSClientProtocol#connect}.
     */
    public static class UdpDatagramTransport implements DatagramTransport {
        private final DatagramSocket socket;
        private volatile InetSocketAddress remoteAddress;
        private final BlockingQueue<byte[]> inboundQueue = new LinkedBlockingQueue<>();
        private volatile boolean closed = false;
        @Getter
        private final DtlsDiagnostics diagnostics;

        public UdpDatagramTransport(DatagramSocket socket, InetSocketAddress remoteAddress) {
            this(socket, remoteAddress, new DtlsDiagnostics());
        }

        public UdpDatagramTransport(DatagramSocket socket, InetSocketAddress remoteAddress, DtlsDiagnostics diagnostics) {
            this.socket = socket;
            this.remoteAddress = remoteAddress;
            this.diagnostics = diagnostics != null ? diagnostics : new DtlsDiagnostics();
        }

        public void setRemoteAddress(InetSocketAddress remoteAddress) {
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
            diagnostics.dtlsUnwrapCalls.incrementAndGet();
            diagnostics.setHandshakeStatus("NEED_UNWRAP");
            try {
                byte[] pkt = inboundQueue.poll(waitMillis, TimeUnit.MILLISECONDS);
                if (pkt == null) {
                    // Equivalent to SSLEngine BUFFER_UNDERFLOW / wait — BC treats -1 as timeout
                    return -1;
                }
                if (pkt.length > len) {
                    // Equivalent to BUFFER_OVERFLOW — must not silently truncate DTLS records
                    log.error("❌ [WebRtcDtls] DTLS_RX BUFFER_OVERFLOW: pktLen={} bufLen={} — dropping truncated read risk",
                            pkt.length, len);
                    throw new IOException("DTLS receive BUFFER_OVERFLOW: packet " + pkt.length + " > buffer " + len);
                }
                System.arraycopy(pkt, 0, buf, off, pkt.length);
                String src = remoteAddress != null ? remoteAddress.toString() : "unknown";
                String hdr = describeDtlsRecord(pkt);
                log.info("DTLS_RX: length={} source={} {} handshakeStatus={} bytesConsumed={} bytesProduced={}",
                        pkt.length, src, hdr, "NEED_UNWRAP", pkt.length, 0);
                return pkt.length;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("DTLS receive interrupted");
            }
        }

        @Override
        public void send(byte[] buf, int off, int len) throws IOException {
            diagnostics.dtlsWrapCalls.incrementAndGet();
            diagnostics.setHandshakeStatus("NEED_WRAP");
            if (closed) {
                throw new IOException("DTLS transport closed — cannot send");
            }
            if (socket.isClosed()) {
                throw new IOException("DTLS socket closed — cannot send");
            }
            if (remoteAddress == null) {
                throw new IOException("DTLS remoteAddress is null — cannot send (silent drop disabled)");
            }
            byte[] toSend = new byte[len];
            System.arraycopy(buf, off, toSend, 0, len);
            DatagramPacket packet = new DatagramPacket(toSend, len, remoteAddress);
            socket.send(packet);
            diagnostics.outboundDtlsPackets.incrementAndGet();
            log.info("DTLS_TX: length={} destination={} {} handshakeStatus={}",
                    len, remoteAddress, describeDtlsRecord(toSend), diagnostics.dtlsHandshakeStatus.get());
        }

        private static String describeDtlsRecord(byte[] pkt) {
            if (pkt == null || pkt.length < 13) {
                return "record=too_short";
            }
            int contentType = pkt[0] & 0xFF;
            int epoch = ((pkt[3] & 0xFF) << 8) | (pkt[4] & 0xFF);
            int recordLen = ((pkt[11] & 0xFF) << 8) | (pkt[12] & 0xFF);
            String typeName = switch (contentType) {
                case 20 -> "ChangeCipherSpec";
                case 21 -> "Alert";
                case 22 -> "Handshake";
                case 23 -> "ApplicationData";
                default -> "Type" + contentType;
            };
            String hs = "";
            if (contentType == 22 && pkt.length >= 14) {
                int hsType = pkt[13] & 0xFF;
                hs = " hsType=" + switch (hsType) {
                    case 1 -> "ClientHello";
                    case 2 -> "ServerHello";
                    case 11 -> "Certificate";
                    case 12 -> "ServerKeyExchange";
                    case 13 -> "CertificateRequest";
                    case 14 -> "ServerHelloDone";
                    case 15 -> "CertificateVerify";
                    case 16 -> "ClientKeyExchange";
                    case 20 -> "Finished";
                    default -> String.valueOf(hsType);
                };
            }
            return "content=" + typeName + " epoch=" + epoch + " recordLen=" + recordLen + hs;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
