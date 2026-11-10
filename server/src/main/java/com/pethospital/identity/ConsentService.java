package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.identity.ConsentDtos.ConsentCommand;
import com.pethospital.identity.ConsentDtos.ConsentListView;
import com.pethospital.identity.ConsentDtos.ConsentStatusView;
import com.pethospital.identity.ConsentDtos.PoliciesView;
import com.pethospital.identity.ConsentDtos.PolicyDocumentView;
import com.pethospital.shared.BusinessException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConsentService implements AiConsentEvaluator {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final PolicyCatalog policies;

    public ConsentService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        PolicyCatalog policies
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.policies = policies;
    }

    public PoliciesView currentPolicies() {
        return new PoliciesView(policies.currentPolicies());
    }

    public ConsentListView currentConsents() {
        long userId = identities.require().userId();
        JdbcTemplate jdbc = jdbc();
        List<ConsentStatusView> items = Arrays.stream(ConsentPurpose.values())
            .map(purpose -> status(jdbc, userId, purpose))
            .toList();
        return new ConsentListView(items);
    }

    @Transactional
    public ConsentStatusView append(ConsentCommand command, String idempotencyKey) {
        long userId = identities.require().userId();
        JdbcTemplate jdbc = jdbc();
        lockActiveUser(jdbc, userId);

        ConsentRow replay = findByIdempotencyKey(jdbc, userId, idempotencyKey, true);
        if (replay != null) {
            if (replay.purpose() != command.purpose()
                || replay.action() != command.action()
                || !replay.policyVersion().equals(command.policyVersion())
                || !replay.noticeHash().equals(command.noticeHash())) {
                throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_CONFLICT",
                    "幂等键已用于其他授权请求"
                );
            }
            return view(replay);
        }

        requireCurrentPolicy(command);
        long consentId = IdWorker.getId();
        jdbc.update("""
            INSERT INTO consent_record(
                id,user_id,purpose,policy_version,action,notice_hash,idempotency_key
            ) VALUES (?,?,?,?,?,?,?)
            """,
            consentId,
            userId,
            command.purpose().name(),
            command.policyVersion(),
            command.action().name(),
            command.noticeHash(),
            idempotencyKey
        );
        ConsentRow inserted = findById(jdbc, consentId);
        if (inserted == null) {
            throw new IllegalStateException("授权事件写入后不可见");
        }
        return view(inserted);
    }

    @Override
    public boolean hasValidConsent(long userId) {
        ConsentRow latest = latest(jdbc(), userId, ConsentPurpose.AI_SERVICE);
        return latest != null
            && latest.action() == ConsentAction.ACCEPT
            && policies.matchesCurrent(
                ConsentPurpose.AI_SERVICE,
                latest.policyVersion(),
                latest.noticeHash()
            );
    }

    @Override
    public void requireValidConsent(long userId) {
        if (!hasValidConsent(userId)) {
            throw new BusinessException(
                HttpStatus.FORBIDDEN,
                "CONSENT_REQUIRED",
                "请先接受当前AI服务授权"
            );
        }
    }

    private ConsentStatusView status(JdbcTemplate jdbc, long userId, ConsentPurpose purpose) {
        ConsentRow latest = latest(jdbc, userId, purpose);
        if (latest == null) {
            PolicyDocumentView current = policies.current(purpose);
            return new ConsentStatusView(
                purpose,
                current.policyVersion(),
                current.noticeHash(),
                null,
                null,
                null,
                null,
                false
            );
        }
        return view(latest);
    }

    private ConsentStatusView view(ConsentRow row) {
        PolicyDocumentView current = policies.current(row.purpose());
        boolean effective = row.action() == ConsentAction.ACCEPT
            && policies.matchesCurrent(row.purpose(), row.policyVersion(), row.noticeHash());
        return new ConsentStatusView(
            row.purpose(),
            current.policyVersion(),
            current.noticeHash(),
            row.action(),
            row.policyVersion(),
            row.noticeHash(),
            row.createdAt(),
            effective
        );
    }

    private void requireCurrentPolicy(ConsentCommand command) {
        if (!policies.current(command.purpose()).policyVersion().equals(command.policyVersion())) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "POLICY_VERSION_OUTDATED",
                "政策版本已更新，请重新阅读"
            );
        }
        if (!policies.current(command.purpose()).noticeHash().equals(command.noticeHash())) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "POLICY_HASH_MISMATCH",
                "政策摘要不匹配"
            );
        }
    }

    private static void lockActiveUser(JdbcTemplate jdbc, long userId) {
        String status = jdbc.query("SELECT status FROM sys_user WHERE id=? FOR UPDATE", result ->
            result.next() ? result.getString(1) : null, userId);
        if (!"ACTIVE".equals(status)) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号不可用");
        }
    }

    private static ConsentRow latest(JdbcTemplate jdbc, long userId, ConsentPurpose purpose) {
        return jdbc.query("""
            SELECT id,purpose,policy_version,action,notice_hash,idempotency_key,created_at
            FROM consent_record
            WHERE user_id=? AND purpose=?
            ORDER BY created_at DESC,id DESC
            LIMIT 1
            """, result -> result.next() ? row(result) : null, userId, purpose.name());
    }

    private static ConsentRow findByIdempotencyKey(
        JdbcTemplate jdbc,
        long userId,
        String idempotencyKey,
        boolean forUpdate
    ) {
        String sql = """
            SELECT id,purpose,policy_version,action,notice_hash,idempotency_key,created_at
            FROM consent_record
            WHERE user_id=? AND idempotency_key=?
            """ + (forUpdate ? " FOR UPDATE" : "");
        return jdbc.query(sql, result -> result.next() ? row(result) : null, userId, idempotencyKey);
    }

    private static ConsentRow findById(JdbcTemplate jdbc, long consentId) {
        return jdbc.query("""
            SELECT id,purpose,policy_version,action,notice_hash,idempotency_key,created_at
            FROM consent_record WHERE id=?
            """, result -> result.next() ? row(result) : null, consentId);
    }

    private static ConsentRow row(java.sql.ResultSet result) throws java.sql.SQLException {
        Timestamp createdAt = result.getTimestamp("created_at");
        return new ConsentRow(
            result.getLong("id"),
            ConsentPurpose.valueOf(result.getString("purpose")),
            result.getString("policy_version"),
            ConsentAction.valueOf(result.getString("action")),
            result.getString("notice_hash"),
            result.getString("idempotency_key"),
            createdAt.toInstant()
        );
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE",
                "数据库连接不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private record ConsentRow(
        long id,
        ConsentPurpose purpose,
        String policyVersion,
        ConsentAction action,
        String noticeHash,
        String idempotencyKey,
        Instant createdAt
    ) {
    }
}
