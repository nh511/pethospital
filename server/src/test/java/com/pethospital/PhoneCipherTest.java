package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.identity.PhoneCipher;
import com.pethospital.shared.BusinessException;
import org.junit.jupiter.api.Test;

class PhoneCipherTest {
    private static final String TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test
    void aesGcmUsesRandomIvAndAuthenticatesCiphertext() {
        PhoneCipher cipher = new PhoneCipher(TEST_KEY);
        byte[] first = cipher.encrypt("13800138000");
        byte[] second = cipher.encrypt("13800138000");

        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("13800138000");
        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(first))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Phone ciphertext authentication failed");
    }

    @Test
    void missingOrMalformedKeyFailsClosed() {
        PhoneCipher missing = new PhoneCipher("");
        assertThatThrownBy(() -> missing.encrypt("13800138000"))
            .isInstanceOfSatisfying(BusinessException.class, exception -> {
                assertThat(exception.code).isEqualTo("PROFILE_ENCRYPTION_UNAVAILABLE");
                assertThat(exception.status.value()).isEqualTo(503);
            });
        assertThatThrownBy(() -> new PhoneCipher("not-base64"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("identity.profile.phone-key-base64 must be valid Base64");
    }
}
