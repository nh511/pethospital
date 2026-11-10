package com.pethospital.shared;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectMapper objectMapper;
    private final AuditSanitizer sanitizer;
    private final RequestHasher requestHasher;

    public IdempotencyService(
        ObjectProvider<DataSource> dataSourceProvider,
        ObjectMapper objectMapper,
        AuditSanitizer sanitizer,
        RequestHasher requestHasher
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
        this.requestHasher = requestHasher;
    }

    @Transactional
    public <T> T execute(
        IdempotencyCommand command,
        Class<T> resultType,
        Supplier<IdempotentResult<T>> operation
    ) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(operation, "operation");
        String operationKey = AuditService.normalizedOperationKey(command.operationKey(), true);
        String requestHash = requestHasher.hash(command.method(), command.path(), command.requestBody());
        long candidateId = IdWorker.getId();
        JdbcTemplate jdbc = jdbc();
        JsonNode before = sanitizer.sanitize(command.beforeSnapshot());
        jdbc.update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,
                before_json,after_json,reason,trace_id,operation_key,request_hash,result,ip_masked
            ) VALUES (?,?,?,?,?,NULL,CAST(? AS JSON),NULL,?,?,?,?,'SUCCESS',?)
            ON DUPLICATE KEY UPDATE id=id
            """,
            candidateId,
            command.actorUserId(),
            command.actorType().name(),
            command.action(),
            command.resourceType(),
            json(before),
            limited(sanitizer.sanitizeText(command.reason()), 500),
            traceId(),
            operationKey,
            requestHash,
            limited(command.ipMasked(), 64)
        );

        StoredOperation stored = lock(jdbc, command.actorUserId(), operationKey);
        if (stored == null) {
            throw new IllegalStateException("idempotency reservation was not created");
        }
        if (stored.id() != candidateId) {
            verifyReplay(command, requestHash, stored);
            return replay(stored.afterJson(), resultType);
        }

        IdempotentResult<T> result = Objects.requireNonNull(operation.get(), "operation result");
        JsonNode replay = objectMapper.valueToTree(result.value());
        if (sanitizer.containsSensitiveData(replay)) {
            throw new IllegalArgumentException("idempotent result contains sensitive data");
        }
        ObjectNode storedAfter = objectMapper.createObjectNode();
        storedAfter.set("replay", replay);
        JsonNode afterSnapshot = sanitizer.sanitize(result.afterSnapshot());
        if (afterSnapshot != null && !afterSnapshot.isNull()) {
            storedAfter.set("snapshot", afterSnapshot);
        }
        int updated = jdbc.update("""
            UPDATE audit_log SET resource_id=?,after_json=CAST(? AS JSON) WHERE id=?
            """, result.resourceId(), json(storedAfter), candidateId);
        if (updated != 1) {
            throw new IllegalStateException("idempotency result could not be stored");
        }
        return result.value();
    }

    private static StoredOperation lock(JdbcTemplate jdbc, long actorUserId, String operationKey) {
        return jdbc.query("""
            SELECT id,action,resource_type,resource_id,request_hash,CAST(after_json AS CHAR) AS after_json
            FROM audit_log WHERE actor_user_id=? AND operation_key=? FOR UPDATE
            """, result -> result.next() ? new StoredOperation(
            result.getLong("id"),
            result.getString("action"),
            result.getString("resource_type"),
            result.getObject("resource_id", Long.class),
            result.getString("request_hash"),
            result.getString("after_json")
        ) : null, actorUserId, operationKey);
    }

    private static void verifyReplay(
        IdempotencyCommand command,
        String requestHash,
        StoredOperation stored
    ) {
        if (!command.action().equals(stored.action())
            || !command.resourceType().equals(stored.resourceType())
            || !requestHash.equals(stored.requestHash())) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "幂等键已用于其他路径或参数"
            );
        }
        if (stored.resourceId() == null || stored.afterJson() == null) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_REPLAY_UNAVAILABLE",
                "原请求结果不可重放"
            );
        }
    }

    private <T> T replay(String storedAfterJson, Class<T> resultType) {
        try {
            JsonNode root = objectMapper.readTree(storedAfterJson);
            JsonNode replay = root.get("replay");
            if (replay == null || replay.isNull()) {
                throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_REPLAY_UNAVAILABLE",
                    "原请求结果不可重放"
                );
            }
            return objectMapper.treeToValue(replay, resultType);
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored idempotency result is invalid", exception);
        }
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "IDEMPOTENCY_DEPENDENCY_UNAVAILABLE",
                "幂等数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private String json(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("idempotency payload cannot be serialized", exception);
        }
    }

    private static String limited(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private static String traceId() {
        String current = TraceFilter.current();
        return current == null ? UUID.randomUUID().toString() : current;
    }

    private record StoredOperation(
        long id,
        String action,
        String resourceType,
        Long resourceId,
        String requestHash,
        String afterJson
    ) {
    }
}
