package com.pethospital.identity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record RegisterRequest(
        @NotBlank
        @Size(min = 4, max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "用户名只能包含字母、数字、点、下划线和连字符")
        String username,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 64) String displayName,
        @NotBlank @Size(max = 32) String privacyPolicyVersion
    ) {
    }

    public record LoginRequest(
        @NotBlank @Size(max = 64) String username,
        @NotBlank @Size(max = 72) String password
    ) {
    }

    public record WechatLoginRequest(
        @NotBlank @Size(max = 256) String code,
        @NotBlank @Size(max = 32) String privacyPolicyVersion
    ) {
    }

    public record UserView(
        String id,
        String username,
        String displayName,
        List<String> roles
    ) {
        public UserView {
            roles = List.copyOf(roles);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenBundle(
        String accessToken,
        String refreshToken,
        long expiresIn,
        String sessionId,
        UserView user
    ) {
    }

    record LoginResult(
        String accessToken,
        long accessExpiresIn,
        String sessionId,
        UserView user,
        String refreshToken,
        long refreshExpiresIn
    ) {
        TokenBundle miniBundle() {
            return new TokenBundle(accessToken, refreshToken, accessExpiresIn, sessionId, user);
        }

        TokenBundle webBundle() {
            return new TokenBundle(accessToken, null, accessExpiresIn, sessionId, user);
        }
    }
}
