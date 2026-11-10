package com.pethospital.identity;

import com.pethospital.shared.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class PhoneCipher {
    private static final byte FORMAT_VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public PhoneCipher(@Value("${identity.profile.phone-key-base64:}") String encodedKey) {
        if (encodedKey == null || encodedKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("identity.profile.phone-key-base64 must be valid Base64", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("identity.profile.phone-key-base64 must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public byte[] encrypt(String plaintext) {
        requireKey();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length)
                .put(FORMAT_VERSION)
                .put(iv)
                .put(encrypted)
                .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM encryption failed", exception);
        }
    }

    public String decrypt(byte[] encoded) {
        requireKey();
        if (encoded == null || encoded.length <= 1 + IV_BYTES || encoded[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported phone ciphertext format");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[encoded.length - 1 - IV_BYTES];
        System.arraycopy(encoded, 1, iv, 0, IV_BYTES);
        System.arraycopy(encoded, 1 + IV_BYTES, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Phone ciphertext authentication failed", exception);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PROFILE_ENCRYPTION_UNAVAILABLE",
                "手机号加密服务未配置"
            );
        }
    }
}
