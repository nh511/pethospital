package com.pethospital.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class WechatOpenIdProtectorTest {
    private static final String OPEN_ID = "openid-unit-test-001";

    @Test
    void hmacIsStableAndCipherIsAuthenticatedAndRandomized() {
        WechatOpenIdProtector protector = protector();

        assertThat(protector.hash(OPEN_ID)).hasSize(64).isEqualTo(protector.hash(OPEN_ID));
        byte[] first = protector.encrypt(OPEN_ID);
        byte[] second = protector.encrypt(OPEN_ID);
        assertThat(first).isNotEqualTo(second);
        assertThat(protector.decrypt(first)).isEqualTo(OPEN_ID);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain(OPEN_ID);

        byte[] tampered = Arrays.copyOf(first, first.length);
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> protector.decrypt(tampered))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("认证失败");
    }

    private static WechatOpenIdProtector protector() {
        byte[] hmac = new byte[32];
        byte[] encryption = new byte[32];
        Arrays.fill(hmac, (byte) 0x31);
        Arrays.fill(encryption, (byte) 0x42);
        return new WechatOpenIdProtector(
            Base64.getEncoder().encodeToString(hmac),
            Base64.getEncoder().encodeToString(encryption)
        );
    }
}
