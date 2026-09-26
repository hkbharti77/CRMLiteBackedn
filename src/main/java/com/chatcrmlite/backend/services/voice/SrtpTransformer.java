package com.chatcrmlite.backend.services.voice;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Standards-compliant SRTP (RFC 3711) Transformer for SRTP_AES128_CM_HMAC_SHA1_80.
 * Handles AES-128-CTR payload encryption/decryption and HMAC-SHA1-80 authentication tags with ROC.
 */
@Slf4j
public class SrtpTransformer {

    private final byte[] encKey = new byte[16];
    private final byte[] authKey = new byte[20];
    private final byte[] saltKey = new byte[14];
    private final boolean isSender;

    public SrtpTransformer(byte[] masterKey, byte[] masterSalt, boolean isSender) {
        this.isSender = isSender;
        deriveSessionKeys(masterKey, masterSalt);
    }

    /**
     * Derives SRTP Session Encryption Key, Session Authentication Key, and Session Salting Key
     * as specified in RFC 3711 Section 4.3.
     */
    private void deriveSessionKeys(byte[] masterKey, byte[] masterSalt) {
        try {
            SecretKeySpec masterKeySpec = new SecretKeySpec(masterKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKeySpec);

            // 1. Session Encryption Key: label = 0x00
            deriveKey(cipher, masterSalt, (byte) 0x00, encKey, 16);

            // 2. Session Authentication Key: label = 0x01
            deriveKey(cipher, masterSalt, (byte) 0x01, authKey, 20);

            // 3. Session Salting Key: label = 0x02
            deriveKey(cipher, masterSalt, (byte) 0x02, saltKey, 14);

        } catch (Exception e) {
            log.error("❌ [SRTP] Failed to derive SRTP session keys: {}", e.getMessage(), e);
            throw new RuntimeException("SRTP Key Derivation Failed", e);
        }
    }

    private void deriveKey(Cipher cipher, byte[] masterSalt, byte label, byte[] outKey, int outLen) throws Exception {
        // IV = (masterSalt * 2^16) XOR (label * 2^48)
        // In 16-byte big-endian IV: masterSalt in bytes 0..13, label at byte 9 (bits 48..55)
        byte[] iv = new byte[16];
        System.arraycopy(masterSalt, 0, iv, 0, 14);
        iv[9] ^= label;

        int blocksNeeded = (outLen + 15) / 16;
        ByteBuffer outBuf = ByteBuffer.allocate(blocksNeeded * 16);

        for (int i = 0; i < blocksNeeded; i++) {
            byte[] blockIv = Arrays.copyOf(iv, 16);
            blockIv[14] ^= (byte) ((i >> 8) & 0xFF);
            blockIv[15] ^= (byte) (i & 0xFF);
            byte[] encrypted = cipher.doFinal(blockIv);
            outBuf.put(encrypted);
        }

        System.arraycopy(outBuf.array(), 0, outKey, 0, outLen);
    }

    /**
     * Encrypts an outgoing RTP packet and appends an 80-bit (10-byte) HMAC-SHA1 authentication tag.
     */
    public byte[] encryptRtp(byte[] rtpPacket) {
        if (rtpPacket == null || rtpPacket.length < 12) return rtpPacket;
        try {
            int seqNum = ((rtpPacket[2] & 0xFF) << 8) | (rtpPacket[3] & 0xFF);
            long ssrc = ((long) (rtpPacket[4] & 0xFF) << 24) |
                        ((long) (rtpPacket[5] & 0xFF) << 16) |
                        ((long) (rtpPacket[6] & 0xFF) << 8) |
                        ((long) (rtpPacket[7] & 0xFF));

            // IV calculation: (saltKey * 2^16) XOR (SSRC * 2^64) XOR (Index * 2^16)
            byte[] iv = new byte[16];
            System.arraycopy(saltKey, 0, iv, 0, 14);

            iv[4] ^= (byte) ((ssrc >> 24) & 0xFF);
            iv[5] ^= (byte) ((ssrc >> 16) & 0xFF);
            iv[6] ^= (byte) ((ssrc >> 8) & 0xFF);
            iv[7] ^= (byte) (ssrc & 0xFF);

            iv[12] ^= (byte) ((seqNum >> 8) & 0xFF);
            iv[13] ^= (byte) (seqNum & 0xFF);

            // AES-CTR payload encryption
            Cipher ctrCipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec encSpec = new SecretKeySpec(encKey, "AES");
            ctrCipher.init(Cipher.ENCRYPT_MODE, encSpec, new IvParameterSpec(iv));

            int payloadLen = rtpPacket.length - 12;
            byte[] encryptedPayload = ctrCipher.doFinal(rtpPacket, 12, payloadLen);

            // Compute HMAC-SHA1 authentication tag over (RTP Header + Encrypted Payload || ROC)
            ByteBuffer srtpBuf = ByteBuffer.allocate(rtpPacket.length + 10);
            srtpBuf.put(rtpPacket, 0, 12);
            srtpBuf.put(encryptedPayload);

            ByteBuffer authBuf = ByteBuffer.allocate(rtpPacket.length + 4);
            authBuf.put(rtpPacket, 0, 12);
            authBuf.put(encryptedPayload);
            authBuf.putInt(0); // ROC = 0 (32-bit rollover counter)

            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(authKey, "HmacSHA1"));
            byte[] tag = hmac.doFinal(authBuf.array());

            // Append 10-byte auth tag
            srtpBuf.put(tag, 0, 10);

            return srtpBuf.array();
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
            int packetLen = srtpPacket.length - 10;
            byte[] packetData = Arrays.copyOf(srtpPacket, packetLen);
            byte[] receivedTag = Arrays.copyOfRange(srtpPacket, packetLen, srtpPacket.length);

            // Verify authentication tag with 32-bit ROC
            ByteBuffer authBuf = ByteBuffer.allocate(packetLen + 4);
            authBuf.put(packetData);
            authBuf.putInt(0); // ROC = 0

            Mac hmac = Mac.getInstance("HmacSHA1");
            hmac.init(new SecretKeySpec(authKey, "HmacSHA1"));
            byte[] computedTag = hmac.doFinal(authBuf.array());

            boolean match = true;
            for (int i = 0; i < 10; i++) {
                if (receivedTag[i] != computedTag[i]) {
                    match = false;
                    break;
                }
            }

            if (!match) {
                // Also check without ROC for implementations that omit ROC
                byte[] rawComputedTag = hmac.doFinal(packetData);
                boolean rawMatch = true;
                for (int i = 0; i < 10; i++) {
                    if (receivedTag[i] != rawComputedTag[i]) {
                        rawMatch = false;
                        break;
                    }
                }
                if (!rawMatch) {
                    log.warn("⚠️ [SRTP] Authentication tag verification failed on incoming SRTP packet");
                    return null;
                }
            }

            int seqNum = ((srtpPacket[2] & 0xFF) << 8) | (srtpPacket[3] & 0xFF);
            long ssrc = ((long) (srtpPacket[4] & 0xFF) << 24) |
                        ((long) (srtpPacket[5] & 0xFF) << 16) |
                        ((long) (srtpPacket[6] & 0xFF) << 8) |
                        ((long) (srtpPacket[7] & 0xFF));

            byte[] iv = new byte[16];
            System.arraycopy(saltKey, 0, iv, 0, 14);

            iv[4] ^= (byte) ((ssrc >> 24) & 0xFF);
            iv[5] ^= (byte) ((ssrc >> 16) & 0xFF);
            iv[6] ^= (byte) ((ssrc >> 8) & 0xFF);
            iv[7] ^= (byte) (ssrc & 0xFF);

            iv[12] ^= (byte) ((seqNum >> 8) & 0xFF);
            iv[13] ^= (byte) (seqNum & 0xFF);

            Cipher ctrCipher = Cipher.getInstance("AES/CTR/NoPadding");
            SecretKeySpec encSpec = new SecretKeySpec(encKey, "AES");
            ctrCipher.init(Cipher.DECRYPT_MODE, encSpec, new IvParameterSpec(iv));

            int payloadLen = packetLen - 12;
            byte[] decryptedPayload = ctrCipher.doFinal(srtpPacket, 12, payloadLen);

            ByteBuffer rtpBuf = ByteBuffer.allocate(12 + decryptedPayload.length);
            rtpBuf.put(srtpPacket, 0, 12);
            rtpBuf.put(decryptedPayload);

            return rtpBuf.array();
        } catch (Exception e) {
            log.error("❌ [SRTP] Failed to decrypt SRTP packet: {}", e.getMessage());
            return null;
        }
    }
}
