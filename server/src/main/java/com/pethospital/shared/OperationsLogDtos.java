package com.pethospital.shared;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public final class OperationsLogDtos {
    private OperationsLogDtos() {
    }

    public record AuditLogView(
        String id,
        String actorUserId,
        String actorType,
        String action,
        String resourceType,
        String resourceId,
        String result,
        String traceId,
        String ipMasked,
        String createdAt
    ) {
    }

    public record AuditLogPage(List<AuditLogView> items, int page, int pageSize, long total) {
        public AuditLogPage {
            items = List.copyOf(items);
        }
    }

    public record AiCallLogView(
        String id,
        String sessionId,
        String userMessageId,
        String callKind,
        String businessRef,
        int generationNo,
        int attemptNo,
        String provider,
        String model,
        String status,
        String promptVersion,
        String safetyVersion,
        Integer promptTokens,
        Integer completionTokens,
        Integer cachedTokens,
        Long latencyMs,
        String startedAt,
        String finishedAt,
        String errorCode,
        JsonNode inputSnapshot,
        String createdAt
    ) {
    }

    public record AiCallLogPage(List<AiCallLogView> items, int page, int pageSize, long total) {
        public AiCallLogPage {
            items = List.copyOf(items);
        }
    }
}
