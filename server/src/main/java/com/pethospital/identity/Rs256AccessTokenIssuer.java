package com.pethospital.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public final class Rs256AccessTokenIssuer {
    private final JwtEncoder jwtEncoder;
    private final String keyId;
    private final String issuer;

    public Rs256AccessTokenIssuer(
        JwtEncoder jwtEncoder,
        JwtKeyMaterial material,
        @Value("${identity.jwt.issuer:pet-hospital}") String issuer
    ) {
        this.jwtEncoder = jwtEncoder;
        this.keyId = material.keyId();
        this.issuer = issuer;
    }

    public IssuedAccessToken issue(
        long userId,
        long sessionId,
        int tokenVersion,
        String audience,
        List<String> roles,
        Instant issuedAt,
        long expiresInSeconds
    ) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
            .type("JWT")
            .keyId(keyId)
            .build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(Long.toString(userId))
            .claim("userId", Long.toString(userId))
            .claim("sessionId", Long.toString(sessionId))
            .claim("tokenVersion", tokenVersion)
            .audience(List.of(audience))
            .claim("roles", List.copyOf(roles))
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(expiresInSeconds))
            .id(UUID.randomUUID().toString())
            .build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresInSeconds);
    }

    public record IssuedAccessToken(String value, long expiresIn) {
    }
}
