package com.pethospital.identity;

import java.util.List;
import java.util.Set;

public record CurrentIdentity(
    long userId,
    long sessionId,
    String clientType,
    List<String> roles,
    Set<String> authorities
) {
    public CurrentIdentity {
        roles = List.copyOf(roles);
        authorities = Set.copyOf(authorities);
    }
}
