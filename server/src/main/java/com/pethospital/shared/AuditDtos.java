package com.pethospital.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

public final class AuditDtos {
    private AuditDtos() {
    }

    public enum ActorType {
        USER,
        DOCTOR,
        ADMIN,
        SYSTEM
    }

    public enum AuditResult {
        SUCCESS,
        FAILURE
    }

    public record ResourceReference(String type, Long id) {
        public ResourceReference {
            type = requireCode(type, "resource type", 64);
            if (id != null && id <= 0) {
                throw new IllegalArgumentException("resource id must be positive");
            }
        }
    }

    public record AuditEvent(
        Long actorUserId,
        ActorType actorType,
        String action,
        ResourceReference resource,
        JsonNode beforeSnapshot,
        JsonNode afterSnapshot,
        String reason,
        String operationKey,
        String requestHash,
        AuditResult result,
        String ipMasked
    ) {
        public AuditEvent {
            if (actorUserId != null && actorUserId <= 0) {
                throw new IllegalArgumentException("actor user id must be positive");
            }
            Objects.requireNonNull(actorType, "actorType");
            action = requireCode(action, "action", 128);
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(result, "result");
        }
    }

    public record IdempotencyCommand(
        long actorUserId,
        ActorType actorType,
        String action,
        String resourceType,
        String method,
        String path,
        String operationKey,
        Object requestBody,
        JsonNode beforeSnapshot,
        String reason,
        String ipMasked
    ) {
        public IdempotencyCommand {
            if (actorUserId <= 0) {
                throw new IllegalArgumentException("actor user id must be positive");
            }
            Objects.requireNonNull(actorType, "actorType");
            action = requireCode(action, "action", 128);
            resourceType = requireCode(resourceType, "resource type", 64);
            method = Objects.requireNonNull(method, "method").strip();
            path = Objects.requireNonNull(path, "path").strip();
            operationKey = Objects.requireNonNull(operationKey, "operationKey").strip();
        }
    }

    public record IdempotentResult<T>(T value, long resourceId, JsonNode afterSnapshot) {
        public IdempotentResult {
            Objects.requireNonNull(value, "value");
            if (resourceId <= 0) {
                throw new IllegalArgumentException("resource id must be positive");
            }
        }
    }

    private static String requireCode(String value, String name, int maxLength) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
            || !normalized.matches("[A-Z][A-Z0-9_]{0," + (maxLength - 1) + "}")) {
            throw new IllegalArgumentException(name + " has invalid format");
        }
        return normalized;
    }
}
