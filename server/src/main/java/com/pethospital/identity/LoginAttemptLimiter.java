package com.pethospital.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class LoginAttemptLimiter {
    private final Object lock = new Object();
    private final LinkedHashMap<String, AttemptState> attempts = new LinkedHashMap<>(64, 0.75f, true);
    private final Clock clock;
    private final int failureLimit;
    private final long windowSeconds;
    private final long lockSeconds;
    private final int maxTrackedKeys;

    public LoginAttemptLimiter(
        Clock clock,
        @Value("${identity.auth.login-failure-limit:5}") int failureLimit,
        @Value("${identity.auth.login-failure-window-seconds:300}") long windowSeconds,
        @Value("${identity.auth.login-lock-seconds:900}") long lockSeconds,
        @Value("${identity.auth.login-max-tracked-keys:10000}") int maxTrackedKeys
    ) {
        this.clock = clock;
        this.failureLimit = requirePositive(failureLimit, "login-failure-limit");
        this.windowSeconds = requirePositive(windowSeconds, "login-failure-window-seconds");
        this.lockSeconds = requirePositive(lockSeconds, "login-lock-seconds");
        this.maxTrackedKeys = requirePositive(maxTrackedKeys, "login-max-tracked-keys");
    }

    public boolean isBlocked(String normalizedUsername, PasswordAuthService.ClientType clientType) {
        String key = key(normalizedUsername, clientType);
        Instant now = clock.instant();
        synchronized (lock) {
            AttemptState state = attempts.get(key);
            if (state == null) {
                return false;
            }
            state.prune(now, windowSeconds);
            if (state.lockedUntil != null && state.lockedUntil.isAfter(now)) {
                return true;
            }
            if (state.failures.isEmpty()) {
                attempts.remove(key);
            }
            return false;
        }
    }

    public void recordFailure(String normalizedUsername, PasswordAuthService.ClientType clientType) {
        String key = key(normalizedUsername, clientType);
        Instant now = clock.instant();
        synchronized (lock) {
            AttemptState state = attempts.computeIfAbsent(key, ignored -> new AttemptState());
            state.prune(now, windowSeconds);
            state.failures.addLast(now);
            if (state.failures.size() >= failureLimit) {
                state.lockedUntil = now.plusSeconds(lockSeconds);
            }
            evictOldestEntries();
        }
    }

    public void recordSuccess(String normalizedUsername, PasswordAuthService.ClientType clientType) {
        synchronized (lock) {
            attempts.remove(key(normalizedUsername, clientType));
        }
    }

    private void evictOldestEntries() {
        Iterator<Map.Entry<String, AttemptState>> iterator = attempts.entrySet().iterator();
        while (attempts.size() > maxTrackedKeys && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private static String key(String normalizedUsername, PasswordAuthService.ClientType clientType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(clientType.name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(normalizedUsername.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    private static long requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive");
        }
        return value;
    }

    private static final class AttemptState {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil;

        private void prune(Instant now, long windowSeconds) {
            Instant cutoff = now.minusSeconds(windowSeconds);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) {
                failures.removeFirst();
            }
            if (lockedUntil != null && !lockedUntil.isAfter(now)) {
                lockedUntil = null;
            }
        }
    }
}
