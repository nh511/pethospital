package com.pethospital.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.shared.TraceFilter;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginSecurityAuditService {
    private static final Logger LOG = LoggerFactory.getLogger(LoginSecurityAuditService.class);

    private final ObjectProvider<DataSource> dataSourceProvider;

    public LoginSecurityAuditService(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long targetUserId, String clientType, String reasonCode) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return;
        }
        String traceId = TraceFilter.current();
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            new JdbcTemplate(dataSource).update("""
                INSERT INTO audit_log(
                    id,actor_user_id,actor_type,action,resource_type,resource_id,
                    after_json,trace_id,result
                ) VALUES (?,NULL,'SYSTEM','AUTH_LOGIN_FAILURE','AUTH_ACCOUNT',?,
                    JSON_OBJECT('clientType',?,'reason',?),?,'FAILURE')
                """,
                IdWorker.getId(),
                targetUserId,
                clientType,
                reasonCode,
                traceId
            );
        } catch (DataAccessException exception) {
            LOG.warn("Unable to persist login failure audit: reason={} traceId={}", reasonCode, traceId);
        }
    }
}
