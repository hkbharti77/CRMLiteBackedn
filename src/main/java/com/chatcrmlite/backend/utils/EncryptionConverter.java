package com.chatcrmlite.backend.utils;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

import lombok.extern.slf4j.Slf4j;

@Component
@Converter
@Slf4j
public class EncryptionConverter implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES";
    private static final String DEFAULT_SECRET = "ChatCrmLiteSecretAESKey12345678";

    // KEY is initialized statically with the application standard secret, and optionally
    // updated by Spring @Value if custom encryption.secret-key is supplied.
    private static byte[] KEY = initKey(DEFAULT_SECRET);

    private static byte[] initKey(String secret) {
        byte[] bytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] key16 = new byte[16];
        for (int i = 0; i < 16; i++) {
            key16[i] = (i < bytes.length) ? bytes[i] : (byte) '0';
        }
        return key16;
    }

    @Value("${encryption.secret-key:${ENCRYPTION_SECRET_KEY:ChatCrmLiteSecretAESKey12345678}}")
    public void setKey(String key) {
        if (key != null && !key.isBlank() && !key.contains("${")) {
            KEY = initKey(key);
            log.info("🔐 EncryptionConverter AES key updated successfully");
        }
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            SecretKeySpec secretKey = new SecretKeySpec(KEY, ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            byte[] encryptedBytes = cipher.doFinal(attribute.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "ENC:" + Base64.getEncoder().encodeToString(encryptedBytes);
        } catch (Exception e) {
            // Fallback to storing raw attribute if encryption fails
            return attribute;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }

        // Check if data was encrypted with ENC: prefix
        if (dbData.startsWith("ENC:")) {
            try {
                String payload = dbData.substring(4);
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                SecretKeySpec secretKey = new SecretKeySpec(KEY, ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, secretKey);
                byte[] decryptedBytes = cipher.doFinal(Base64.getDecoder().decode(payload));
                return new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.error("❌ EncryptionConverter failed to decrypt attribute with prefix ENC: - returning raw data. Reason: {}", e.getMessage());
                return dbData.substring(4);
            }
        }

        // Try standard AES decryption for existing encrypted records without prefix
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData);
            if (decoded.length > 0 && decoded.length % 16 == 0) {
                Cipher cipher = Cipher.getInstance(ALGORITHM);
                SecretKeySpec secretKey = new SecretKeySpec(KEY, ALGORITHM);
                cipher.init(Cipher.DECRYPT_MODE, secretKey);
                byte[] decryptedBytes = cipher.doFinal(decoded);
                return new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // Not base64/encrypted or padding issue, treat as legacy plaintext
        }

        // Return legacy plaintext directly
        return dbData;
    }
}
