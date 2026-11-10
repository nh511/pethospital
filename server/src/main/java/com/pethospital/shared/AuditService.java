package com.pethospital.shared;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.shared.AuditDtos.AuditEvent;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectMapper objectMapper;
    private final AuditSanitizer sanitizer;

    public AuditService(
        ObjectProvider<DataSource> dataSourceProvider,
        ObjectMapper objectMapper,
        AuditSanitizer sanitizer
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.objectMapper = objectMapper;
        this.sanitizer = sanitizer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long append(AuditEvent event) {
        validate(event);
        long auditId = IdWorker.getId();
        JsonNode before = sanitizer.sanitize(event.beforeSnapshot());
        JsonNode after = sanitizer.sanitize(event.afterSnapshot());
        jdbc().update("""
            INSERT INTO audit_log(
                id,actor_user_id,actor_type,action,resource_type,resource_id,
                before_json,after_json,reason,trace_id,operation_key,request_hash,result,ip_masked
            ) VALUES (?,?,?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),?,?,?,?,?,?)
            """,
            auditId,
            event.actorUserId(),
            event.actorType().name(),
            event.action(),
            event.resource().type(),
            event.resource().id(),
            json(before),
            json(after),
            limited(sanitizer.sanitizeText(event.reason()), 500),
            traceId(),
            normalizedOperationKey(event.operationKey(), false),
            normalizedHash(event.requestHash(), false),
            event.result().name(),
            limited(event.ipMasked(), 64)
        );
        return auditId;
    }

    private static void validate(AuditEvent event) {
        if (event.actorUserId() == null && event.operationKey() != null) {
            throw new IllegalArgumentException("system audit must use a business unique key");
        }
        normalizedOperationKey(event.operationKey(), false);
        normalizedHash(event.requestHash(), false);
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUDIT_DEPENDENCY_UNAVAILABLE",
                "审计数据源不可用"
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
            throw new IllegalArgumentException("audit snapshot cannot be serialized", exception);
        }
    }

    static String normalizedOperationKey(String value, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("operation key is required");
            }
            return null;
        }
        String normalized = value.strip();
        if (!normalized.matches("[A-Za-z0-9._:-]{16,64}")) {
            throw new IllegalArgumentException("operation key has invalid format");
        }
        return normalized;
    }

    static String normalizedHash(String value, boolean required) {
        if (value == null) {
            if (required) {
                throw new IllegalArgumentException("request hash is required");
            }
            return null;
        }
        String normalized = value.strip().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("request hash has invalid format");
        }
        return normalized;
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
}
