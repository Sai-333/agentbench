package com.agentbench.platform.crypto;

import com.agentbench.platform.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-GCM envelope encryption for org secret refs.
 * Key material comes from {@code AGENTBENCH_SECRETS_ENCRYPTION_KEY} (base64 32 bytes).
 */
@Service
public class SecretEncryptionService {

    public static final int CURRENT_KEY_VERSION = 1;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretEncryptionService(
            @Value("${agentbench.secrets.encryption-key}") String base64Key) {
        byte[] keyBytes = decodeKey(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalStateException("agentbench.secrets.encryption-key must decode to 32 bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return packed;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_ENCRYPT_FAILED", "Unable to encrypt secret");
        }
    }

    public String decrypt(byte[] packed) {
        try {
            if (packed == null || packed.length <= GCM_IV_LENGTH) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = Arrays.copyOfRange(packed, 0, GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(packed, GCM_IV_LENGTH, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SECRET_DECRYPT_FAILED", "Unable to decrypt secret");
        }
    }

    private static byte[] decodeKey(String base64Key) {
        try {
            return Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("agentbench.secrets.encryption-key must be valid base64", ex);
        }
    }
}
