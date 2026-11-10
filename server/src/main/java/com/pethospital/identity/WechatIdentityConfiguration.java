package com.pethospital.identity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.pethospital.shared.BusinessException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Configuration
public class WechatIdentityConfiguration {
    @Bean
    @ConditionalOnProperty(name = "identity.wechat.mode", havingValue = "disabled", matchIfMissing = true)
    WechatIdentityAdapter disabledWechatIdentityAdapter() {
        return code -> {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "WECHAT_LOGIN_UNAVAILABLE",
                "微信登录尚未配置"
            );
        };
    }

    @Bean
    @ConditionalOnProperty(name = "identity.wechat.mode", havingValue = "stub")
    WechatIdentityAdapter stubWechatIdentityAdapter(
        @Value("${identity.wechat.stub.code:}") String acceptedCode,
        @Value("${identity.wechat.stub.openid:}") String openId
    ) {
        String configuredCode = requireConfigured(acceptedCode, "identity.wechat.stub.code");
        String configuredOpenId = requireConfigured(openId, "identity.wechat.stub.openid");
        return code -> {
            if (!configuredCode.equals(code == null ? null : code.trim())) {
                throw invalidCode();
            }
            return new WechatIdentityAdapter.WechatIdentity(configuredOpenId);
        };
    }

    @Bean
    @ConditionalOnProperty(name = "identity.wechat.mode", havingValue = "real")
    WechatIdentityAdapter realWechatIdentityAdapter(
        @Value("${identity.wechat.app-id:}") String appId,
        @Value("${identity.wechat.app-secret:}") String appSecret,
        @Value("${identity.wechat.endpoint:https://api.weixin.qq.com/sns/jscode2session}") String endpoint
    ) {
        return new RealWechatIdentityAdapter(
            requireConfigured(appId, "identity.wechat.app-id"),
            requireConfigured(appSecret, "identity.wechat.app-secret"),
            requireConfigured(endpoint, "identity.wechat.endpoint")
        );
    }

    static final class RealWechatIdentityAdapter implements WechatIdentityAdapter {
        private final String appId;
        private final String appSecret;
        private final String endpoint;
        private final RestClient client = RestClient.create();

        RealWechatIdentityAdapter(String appId, String appSecret, String endpoint) {
            this.appId = appId;
            this.appSecret = appSecret;
            this.endpoint = endpoint;
        }

        @Override
        public WechatIdentity exchange(String code) {
            String normalizedCode = code == null ? "" : code.trim();
            if (normalizedCode.isEmpty()) {
                throw invalidCode();
            }
            URI uri = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("appid", appId)
                .queryParam("secret", appSecret)
                .queryParam("js_code", normalizedCode)
                .queryParam("grant_type", "authorization_code")
                .build()
                .encode()
                .toUri();
            try {
                WechatSessionResponse response = client.get().uri(uri).retrieve().body(WechatSessionResponse.class);
                if (response == null || response.errCode() != null || response.openId() == null) {
                    throw invalidCode();
                }
                return new WechatIdentity(response.openId());
            } catch (BusinessException exception) {
                throw exception;
            } catch (RestClientException | IllegalArgumentException exception) {
                throw new BusinessException(
                    HttpStatus.BAD_GATEWAY,
                    "WECHAT_SERVICE_UNAVAILABLE",
                    "微信身份服务暂时不可用"
                );
            }
        }
    }

    private record WechatSessionResponse(
        @JsonProperty("openid") String openId,
        @JsonProperty("errcode") Integer errCode
    ) {
    }

    private static BusinessException invalidCode() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "WECHAT_CODE_INVALID", "微信登录凭证无效");
    }

    private static String requireConfigured(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be configured");
        }
        return value.trim();
    }
}
