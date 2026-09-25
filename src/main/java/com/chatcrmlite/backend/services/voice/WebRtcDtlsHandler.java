package com.chatcrmlite.backend.services.voice;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;

/**
 * Generates X.509 certificates and SHA-256 fingerprints for WebRTC DTLS 1.2 negotiation.
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

    public WebRtcDtlsHandler() {
        try {
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048, new SecureRandom());
            this.keyPair = keyGen.generateKeyPair();

            long now = System.currentTimeMillis();
            Date startDate = new Date(now - 24 * 60 * 60 * 1000L);
            Date endDate = new Date(now + 365 * 24 * 60 * 60 * 1000L);
            BigInteger serial = BigInteger.valueOf(now);
            X500Name dn = new X500Name("CN=CRMLite-WebRTC");

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                    dn, serial, startDate, endDate, dn, keyPair.getPublic()
            );

            ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSAEncryption").build(keyPair.getPrivate());
            X509CertificateHolder certHolder = certBuilder.build(signer);
            this.certificate = new JcaX509CertificateConverter().getCertificate(certHolder);

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
}
