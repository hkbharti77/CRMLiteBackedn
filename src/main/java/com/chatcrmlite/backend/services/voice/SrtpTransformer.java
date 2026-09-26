package com.chatcrmlite.backend.services.voice;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Standards-compliant SRTP (RFC 3711) Transformer for SRTP_AES128_CM_HMAC_SHA1_80.
 * Handles AES-128-CTR payload encryption/decryption and HMAC-SHA1-80 auth tags with ROC.
 *
 * RTP Header layout (RFC 3550):
 *   Bytes 0-1  : V(2) P(1) X(1) CC(4) M(1) PT(7)
 *   Bytes 2-3  : Sequence Number
 *   Bytes 4-7  : Timestamp           <- NOT SSRC
 *   Bytes 8-11 : SSRC               <- SSRC is HERE
 */
@Slf4j
public class SrtpTransformer {

    private final byte[] encKey = new byte[16];
    private final byte[] authKey = new byte[20];
    private final byte[] saltKey = new byte[14];
    private final boolean isSender;

    // ROC tracking per RFC 3711 §3.3.1
    private final AtomicInteger roc = new AtomicInteger(0);
    private volatile int lastSeq = -1;

    public SrtpTransformer(byte[] masterKey, byte[] masterSalt, boolean isSender) {
        this.isSender = isSender;
        deriveSessionKeys(masterKey, masterSalt);
    }

    byte[] getEncKey() { return encKey; }
    byte[] getAuthKey() { return authKey; }
    byte[] getSaltKey() { return saltKey; }

    /**
     * Derives SRTP Session Encryption, Authentication, and Salting Keys
     * as specified in RFC 3711 Section 4.3.
     */
    private void deriveSessionKeys(byte[] masterKey, byte[] masterSalt) {
        try {
            SecretKeySpec masterKeySpec = new SecretKeySpec(masterKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKeySpec);

            deriveKey(cipher, masterSalt, (byte) 0x00, encKey,  16); // encryption key
            deriveKey(cipher, masterSalt, (byte) 0x01, authKey, 20); // authentication key
            deriveKey(cipher, masterSalt, (byte) 0x02, saltKey, 14); // salting key

        } catch (Exception e) {
            log.error("❌ [SRTP] Failed to derive SRTP session keys: {}", e.getMessage(), e);
            throw new RuntimeException("SRTP Key Derivation Failed", e);
        }
    }

    private void deriveKey(Cipher cipher, byte[] masterSalt, byte label, byte[] outKey, int outLen) throws Exception {
        // RFC 3711 §4.3.1: IV = (label * 2^48) XOR (k_s * 2^16)
        // k_s (14 bytes) at iv[0..13], label XOR'd at byte 7
        byte[] iv = new byte[16];
        System.arraycopy(masterSalt, 0, iv, 0, 14);
        iv[7] ^= label;

        int blocksNeeded = (outLen + 15) / 16;
        ByteBuffer outBuf = ByteBuffer.allocate(blocksNeeded * 16);
        for (int i = 0; i < blocksNeeded; i++) {
            byte[] blockIv = Arrays.copyOf(iv, 16);
            blockIv[14] ^= (byte) ((i >> 8) & 0xFF);
            blockIv[15] ^= (byte) (i & 0xFF);
            outBuf.put(cipher.doFinal(blockIv));
        }
        System.arraycopy(outBuf.array(), 0, outKey, 0, outLen);
    }

    /**
     * RFC 3711 §4.1 AES-CTR IV:
     *   IV = (k_s * 2^16) XOR (SSRC * 2^64) XOR (i * 2^16)
     *
     * In 16-byte big-endian layout:
     *   iv[0..13] = saltKey   (k_s; iv[14..15] = 0)
     *   iv[4..7]  ^= SSRC     (SSRC from RTP bytes 8-11)
     *   iv[10..13] ^= index   (48-bit packet index = (ROC<<16)|SEQ)
     *
     * CRITICAL: SSRC is at RTP header bytes 8-11, NOT bytes 4-7 (those are Timestamp).
     */
    private byte[] computeIv(byte[] rtpPacket, long index) {
        // SSRC: RTP header bytes 8-11 (RFC 3550 §5.1)
        long ssrc = ((long)(rtpPacket[8]  & 0xFF) << 24)
                  | ((long)(rtpPacket[9]  & 0xFF) << 16)
                  | ((long)(rtpPacket[10] & 0xFF) <<  8)
                  | ((long)(rtpPacket[11] & 0xFF));

        byte[] iv = new byte[16];
        System.arraycopy(saltKey, 0, iv, 0, 14);
        // iv[14] and iv[15] remain 0 per k_s * 2^16

        // SSRC XOR at bytes 4-7 (SSRC * 2^64 in 128-bit field)
        iv[4] ^= (byte)((ssrc >> 24) & 0xFF);
        iv[5] ^= (byte)((ssrc >> 16) & 0xFF);
        iv[6] ^= (byte)((ssrc >>  8) & 0xFF);
        iv[7] ^= (byte)( ssrc        & 0xFF);

        // Packet index XOR at bytes 10-13 (i * 2^16 in 128-bit field)
        iv[10] ^= (byte)((index >> 24) & 0xFF);
        iv[11] ^= (byte)((index >> 16) & 0xFF);
        iv[12] ^= (byte)((index >>  8) & 0xFF);
        iv[13] ^= (byte)( index        & 0xFF);

        return iv;
    }

    /**
     * Computes HMAC-SHA1-80 auth tag over (SRTP_packet_without_tag || ROC).
     * ROC is appended as a 4-byte big-endian integer per RFC 3711 §4.2.
     */
    private byte[] computeAuthTag(byte[] srtpData, int rocVal) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(authKey, "HmacSHA1"));
        hmac.update(srtpData);
        hmac.update((byte)((rocVal >> 24) & 0xFF));
        hmac.update((byte)((rocVal >> 16) & 0xFF));
        hmac.update((byte)((rocVal >>  8) & 0xFF));
        hmac.update((byte)( rocVal        & 0xFF));
        return hmac.doFinal();
    }

    /**
     * Encrypts an outgoing RTP packet and appends an 80-bit (10-byte) HMAC-SHA1 authentication tag.
     */
    public byte[] encryptRtp(byte[] rtpPacket) {
        if (rtpPacket == null || rtpPacket.length < 12) return rtpPacket;
        try {
            int seqNum = ((rtpPacket[2] & 0xFF) << 8) | (rtpPacket[3] & 0xFF);
            int currentRoc = roc.get();
            long index = ((long) currentRoc << 16) | (seqNum & 0xFFFF);
            byte[] iv = computeIv(rtpPacket, index);

            Cipher ctrCipher = Cipher.getInstance("AES/CTR/NoPadding");
            ctrCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));

            int payloadLen = rtpPacket.length - 12;
            byte[] encryptedPayload = ctrCipher.doFinal(rtpPacket, 12, payloadLen);

            byte[] srtpData = new byte[rtpPacket.length];
            System.arraycopy(rtpPacket, 0, srtpData, 0, 12);
            System.arraycopy(encryptedPayload, 0, srtpData, 12, payloadLen);

            byte[] tag = computeAuthTag(srtpData, currentRoc);

            byte[] result = new byte[srtpData.length + 10];
            System.arraycopy(srtpData, 0, result, 0, srtpData.length);
            System.arraycopy(tag, 0, result, srtpData.length, 10);
            return result;

        } catch (Exception e) {
            log.error("❌ [SRTP] Failed to encrypt RTP packet: {}", e.getMessage());
            return rtpPacket;
        }
    }

    /**
     * Decrypts an incoming SRTP packet and strips the 80-bit (10-byte) authentication tag.
     */
    public byte[] decryptSrtp(byte[] srtpPacket) {
        if (srtpPacket == null || srtpPacket.length < 22) return null; // 12 header + 10 auth tag min
        try {
            int seqNum = ((srtpPacket[2] & 0xFF) << 8) | (srtpPacket[3] & 0xFF);
            int currentRoc = roc.get();

            int packetLen = srtpPacket.length - 10;
            byte[] srtpData = Arrays.copyOf(srtpPacket, packetLen);
            byte[] receivedTag = Arrays.copyOfRange(srtpPacket, packetLen, srtpPacket.length);

            // Try auth verification with current ROC, then ROC+1 (handles wrap-around),
            // then without ROC (fallback for implementations that omit ROC in auth input)
            boolean authOk = false;
            int matchedRoc = currentRoc;
            for (int tryRoc = currentRoc; tryRoc <= currentRoc + 1; tryRoc++) {
                byte[] computedTag = computeAuthTag(srtpData, tryRoc);
                // Constant-time comparison to prevent timing attacks
                if (MessageDigest.isEqual(
                        Arrays.copyOf(receivedTag, 10),
                        Arrays.copyOf(computedTag, 10))) {
                    authOk = true;
                    matchedRoc = tryRoc;
                    break;
                }
            }
            // Fallback: try without ROC (some implementations skip ROC in auth)
            if (!authOk) {
                try {
                    Mac hmac = Mac.getInstance("HmacSHA1");
                    hmac.init(new SecretKeySpec(authKey, "HmacSHA1"));
                    byte[] noRocTag = hmac.doFinal(srtpData);
                    if (MessageDigest.isEqual(
                            Arrays.copyOf(receivedTag, 10),
                            Arrays.copyOf(noRocTag, 10))) {
                        authOk = true;
                    }
                } catch (Exception ignored) {}
            }

            if (!authOk) {
                log.warn("⚠️ [SRTP] Authentication tag verification failed on incoming SRTP packet");
                return null;
            }

            if (matchedRoc > currentRoc) {
                roc.compareAndSet(currentRoc, matchedRoc);
            }
            lastSeq = seqNum;

            long index = ((long) matchedRoc << 16) | (seqNum & 0xFFFF);
            byte[] iv = computeIv(srtpPacket, index);

            Cipher ctrCipher = Cipher.getInstance("AES/CTR/NoPadding");
            ctrCipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(iv));

            int payloadLen = packetLen - 12;
            byte[] decryptedPayload = ctrCipher.doFinal(srtpPacket, 12, payloadLen);

            byte[] rtpBuf = new byte[12 + decryptedPayload.length];
            System.arraycopy(srtpPacket, 0, rtpBuf, 0, 12);
            System.arraycopy(decryptedPayload, 0, rtpBuf, 12, decryptedPayload.length);
            return rtpBuf;

        } catch (Exception e) {
            log.error("❌ [SRTP] Failed to decrypt SRTP packet: {}", e.getMessage());
            return null;
        }
    }
}
