package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.IdempotencyService;
import com.pethospital.testing.ConcurrentTestBarrier;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class M0401IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ACTOR = 40_101;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IdempotencyService idempotency;

    @Autowired
    AuditService audits;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id=?", ACTOR);
        jdbc.update("DELETE FROM user_notice WHERE recipient_user_id=?", ACTOR);
        jdbc.update("DELETE FROM sys_user WHERE id=?", ACTOR);
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0401-actor',NULL,'M04审计测试','ACTIVE',0,0)
            """, ACTOR);
    }

    @Test
    void sameRequestReplaysOriginalResourceAndDifferentRequestConflicts() {
        AtomicInteger executions = new AtomicInteger();
        var command = command(
            "m0401-replay-0001",
            "/api/v1/test-resources",
            Map.of("name", "first", "temporaryPassword", "OnlyHashedNotStored")
        );

        ReplayView first = idempotency.execute(command, ReplayView.class, () -> {
            executions.incrementAndGet();
            insertNotice(40_201, "m0401-replay");
            return result(40_201, "CREATED");
        });
        ReplayView replay = idempotency.execute(command, ReplayView.class, () -> {
            throw new AssertionError("replayed operation must not execute again");
        });

        assertThat(first).isEqualTo(new ReplayView(40_201, "CREATED"));
        assertThat(replay).isEqualTo(first);
        assertThat(executions).hasValue(1);
        assertThat(count("user_notice", "id=40201")).isEqualTo(1);
        assertThat(count("audit_log", "actor_user_id=40101 AND operation_key='m0401-replay-0001'"))
            .isEqualTo(1);

        var differentPath = command(
            "m0401-replay-0001",
            "/api/v1/other-resources",
            Map.of("name", "first", "temporaryPassword", "OnlyHashedNotStored")
        );
        assertThatThrownBy(() -> idempotency.execute(
            differentPath, ReplayView.class, () -> result(40_202, "CREATED")
        )).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("IDEMPOTENCY_CONFLICT")
        );

        String auditText = jdbc.queryForObject("""
            SELECT CONCAT(COALESCE(CAST(before_json AS CHAR),''),'|',
                          COALESCE(CAST(after_json AS CHAR),''),'|',COALESCE(reason,''))
            FROM audit_log WHERE actor_user_id=? AND operation_key=?
            """, String.class, ACTOR, "m0401-replay-0001");
        assertThat(auditText)
            .doesNotContain("OnlyHashedNotStored")
            .contains("[REDACTED]", "138****8000");
    }

    @Test
    void concurrentSameKeyExecutesBusinessOnlyOnce() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        ConcurrentTestBarrier barrier = new ConcurrentTestBarrier(2);
        var command = command(
            "m0401-concurrent-01",
            "/api/v1/test-resources/concurrent",
            Map.of("value", 1)
        );
        Callable<ReplayView> work = () -> {
            barrier.readyAndAwaitRelease(Duration.ofSeconds(10));
            return idempotency.execute(command, ReplayView.class, () -> {
                executions.incrementAndGet();
                insertNotice(40_202, "m0401-concurrent");
                return result(40_202, "CREATED");
            });
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReplayView> first = executor.submit(work);
            Future<ReplayView> second = executor.submit(work);
            barrier.awaitAllReady(Duration.ofSeconds(10));
            barrier.release();

            assertThat(get(first)).isEqualTo(new ReplayView(40_202, "CREATED"));
            assertThat(get(second)).isEqualTo(new ReplayView(40_202, "CREATED"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        assertThat(executions).hasValue(1);
        assertThat(count("user_notice", "id=40202")).isEqualTo(1);
        assertThat(count("audit_log", "actor_user_id=40101 AND operation_key='m0401-concurrent-01'"))
            .isEqualTo(1);
    }

    @Test
    void failedBusinessRollsBackReservationAndResourceThenAllowsRetry() {
        var command = command(
            "m0401-rollback-0001",
            "/api/v1/test-resources/rollback",
            Map.of("value", 2)
        );

        assertThatThrownBy(() -> idempotency.execute(command, ReplayView.class, () -> {
            insertNotice(40_203, "m0401-rollback");
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("forced rollback");
        assertThat(count("user_notice", "id=40203")).isZero();
        assertThat(count("audit_log", "actor_user_id=40101 AND operation_key='m0401-rollback-0001'"))
            .isZero();

        ReplayView retried = idempotency.execute(command, ReplayView.class, () -> {
            insertNotice(40_203, "m0401-rollback");
            return result(40_203, "CREATED");
        });
        assertThat(retried.id()).isEqualTo(40_203);
        assertThat(count("user_notice", "id=40203")).isEqualTo(1);
    }

    @Test
    void auditAppendCommitsAndRollsBackWithBusinessWhileRedactingSnapshots() {
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        transactions.executeWithoutResult(status -> {
            insertNotice(40_204, "m0401-audit-commit");
            audits.append(new AuditEvent(
                ACTOR,
                ActorType.ADMIN,
                "TEST_RESOURCE_CREATE",
                new ResourceReference("USER_NOTICE", 40_204L),
                objectMapper.valueToTree(Map.of("password", "BeforeSecret")),
                objectMapper.valueToTree(Map.of(
                    "phone", "13900139000",
                    "authorization", "Bearer after-secret-token"
                )),
                "password=ReasonSecret",
                null,
                null,
                AuditResult.SUCCESS,
                "127.0.*.*"
            ));
        });
        String stored = jdbc.queryForObject("""
            SELECT CONCAT(CAST(before_json AS CHAR),'|',CAST(after_json AS CHAR),'|',reason)
            FROM audit_log WHERE action='TEST_RESOURCE_CREATE' AND resource_id=40204
            """, String.class);
        assertThat(stored)
            .doesNotContain("BeforeSecret", "after-secret-token", "ReasonSecret")
            .contains("[REDACTED]", "139****9000");

        transactions.executeWithoutResult(status -> {
            insertNotice(40_205, "m0401-audit-rollback");
            audits.append(new AuditEvent(
                ACTOR,
                ActorType.ADMIN,
                "TEST_RESOURCE_ROLLBACK",
                new ResourceReference("USER_NOTICE", 40_205L),
                null,
                objectMapper.valueToTree(Map.of("status", "CREATED")),
                null,
                null,
                null,
                AuditResult.SUCCESS,
                null
            ));
            status.setRollbackOnly();
        });
        assertThat(count("user_notice", "id=40205")).isZero();
        assertThat(count("audit_log", "action='TEST_RESOURCE_ROLLBACK'")) .isZero();
    }

    private IdempotencyCommand command(String key, String path, Object body) {
        return new IdempotencyCommand(
            ACTOR,
            ActorType.ADMIN,
            "TEST_RESOURCE_WRITE",
            "USER_NOTICE",
            "POST",
            path,
            key,
            body,
            objectMapper.valueToTree(Map.of(
                "phone", "13800138000",
                "password", "BeforeSnapshotSecret"
            )),
            "M04-01 test",
            "127.0.*.*"
        );
    }

    private IdempotentResult<ReplayView> result(long id, String status) {
        return new IdempotentResult<>(
            new ReplayView(id, status),
            id,
            objectMapper.valueToTree(Map.of("id", id, "status", status))
        );
    }

    private void insertNotice(long id, String eventKey) {
        jdbc.update("""
            INSERT INTO user_notice(
                id,recipient_user_id,event_key,notice_type,title,body,resource_type,resource_id
            ) VALUES (?, ?, ?, 'SYSTEM', 'M04测试', '脱敏测试消息', 'SYS_USER', ?)
            """, id, ACTOR, eventKey, ACTOR);
    }

    private int count(String table, String predicate) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM " + table + " WHERE " + predicate,
            Integer.class
        );
    }

    private static ReplayView get(Future<ReplayView> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    public record ReplayView(long id, String status) {
    }
}
