package com.pethospital.identity;

import com.pethospital.shared.BusinessException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public final class WechatOpenIdProtector {
    private static final byte FORMAT_VERSION = 1;
    private static final byte[] AAD = "pet-hospital:wechat-openid:v1".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec hmacKey;
    private final SecretKeySpec encryptionKey;

    public WechatOpenIdProtector(
        @Value("${identity.wechat.openid-hmac-key-base64:}") String hmacKey,
        @Value("${identity.wechat.openid-encryption-key-base64:}") String encryptionKey
    ) {
        this.hmacKey = decodeKey(hmacKey, "identity.wechat.openid-hmac-key-base64", "HmacSHA256", 32, false);
        this.encryptionKey = decodeKey(
            encryptionKey,
            "identity.wechat.openid-encryption-key-base64",
            "AES",
            32,
            true
        );
    }

    public String hash(String openId) {
        requireKeys();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            return HexFormat.of().formatHex(mac.doFinal(openId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("微信OpenID HMAC失败", exception);
        }
    }

    public byte[] encrypt(String openId) {
        requireKeys();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(openId.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + encrypted.length)
                .put(FORMAT_VERSION)
                .put(iv)
                .put(encrypted)
                .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("微信OpenID加密失败", exception);
        }
    }

    public String decrypt(byte[] encoded) {
        requireKeys();
        if (encoded == null || encoded.length <= 1 + IV_BYTES || encoded[0] != FORMAT_VERSION) {
            throw new IllegalArgumentException("不支持的微信OpenID密文格式");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[encoded.length - 1 - IV_BYTES];
        System.arraycopy(encoded, 1, iv, 0, IV_BYTES);
        System.arraycopy(encoded, 1 + IV_BYTES, ciphertext, 0, ciphertext.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("微信OpenID密文认证失败", exception);
        }
    }

    private void requireKeys() {
        if (hmacKey == null || encryptionKey == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WECHAT_ENCRYPTION_UNAVAILABLE",
                "微信身份加密服务未配置"
            );
        }
    }

    private static SecretKeySpec decodeKey(
        String encoded,
        String property,
        String algorithm,
        int minimumBytes,
        boolean exact
    ) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(property + " must be valid Base64", exception);
        }
        if ((exact && decoded.length != minimumBytes) || (!exact && decoded.length < minimumBytes)) {
            throw new IllegalStateException(property + (exact ? " must decode to " : " must decode to at least ")
                + minimumBytes + " bytes");
        }
        return new SecretKeySpec(decoded, algorithm);
    }
}
