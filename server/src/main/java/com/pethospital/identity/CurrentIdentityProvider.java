package com.pethospital.identity;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentIdentityProvider {
    public CurrentIdentity require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
            || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated JWT identity is required");
        }
        var jwt = jwtAuthentication.getToken();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            roles = List.of();
        }
        return new CurrentIdentity(
            Long.parseLong(jwt.getClaimAsString("userId")),
            Long.parseLong(jwt.getClaimAsString("sessionId")),
            jwt.getAudience().getFirst(),
            roles,
            jwtAuthentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
        );
    }
}
