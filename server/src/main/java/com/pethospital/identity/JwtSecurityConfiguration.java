package com.pethospital.identity;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

@Configuration
public class JwtSecurityConfiguration {
    @Bean
    JwtKeyMaterial jwtKeyMaterial(
        ResourceLoader resourceLoader,
        @Value("${identity.jwt.private-key-location:}") String privateLocation,
        @Value("${identity.jwt.public-key-location:}") String publicLocation,
        @Value("${identity.jwt.allow-ephemeral:false}") boolean allowEphemeral
    ) {
        return JwtKeyMaterial.load(resourceLoader, privateLocation, publicLocation, allowEphemeral);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyMaterial material) {
        RSAKey rsaKey = new RSAKey.Builder(material.publicKey())
            .privateKey(material.privateKey())
            .keyID(material.keyId())
            .algorithm(JWSAlgorithm.RS256)
            .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(rsaKey)));
    }

    @Bean
    JwtDecoder jwtDecoder(
        JwtKeyMaterial material,
        SessionJwtValidator sessionValidator,
        @Value("${identity.jwt.issuer:pet-hospital}") String issuer
    ) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(material.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            sessionValidator
        ));
        return decoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            var authorities = new ArrayList<>(roles.convert(jwt));
            if (jwt.getAudience().size() == 1) {
                authorities.add(new SimpleGrantedAuthority("AUD_" + jwt.getAudience().getFirst()));
            }
            return authorities;
        });
        return converter;
    }
}
