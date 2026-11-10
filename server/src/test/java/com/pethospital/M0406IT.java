package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pethospital.shared.BusinessException;
import com.pethospital.shared.NoticeDtos.NoticeCommand;
import com.pethospital.shared.NoticeDtos.NoticeType;
import com.pethospital.shared.NoticeService;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag(TestTags.MYSQL_IT)
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true"
})
class M0406IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long RECIPIENT = 40_601;
    private static final long OTHER = 40_602;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NoticeService notices;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    MockMvc mockMvc;

    TransactionTemplate transactions;

    @BeforeEach
    void resetFixtures() {
        SecurityContextHolder.clearContext();
        transactions = new TransactionTemplate(transactionManager);
        cleanup();
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,'m0406-recipient',NULL,'消息用户','ACTIVE',0,0),
                   (?,'m0406-other',NULL,'其他用户','ACTIVE',0,0)
            """, RECIPIENT, OTHER);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @Test
    void publishRequiresBusinessTransactionDeduplicatesAndRejectsEventKeyReuse() {
        NoticeCommand command = appointment(
            RECIPIENT, "m0406-confirmed-event-01", NoticeType.APPOINTMENT_CONFIRMED, 60_601
        );
        assertThatThrownBy(() -> notices.publish(command))
            .isInstanceOf(IllegalTransactionStateException.class);

        Long first = transactions.execute(status -> notices.publish(command));
        Long replay = transactions.execute(status -> notices.publish(command));
        assertThat(replay).isEqualTo(first);
        assertThat(count("recipient_user_id=" + RECIPIENT)).isEqualTo(1);

        assertThatThrownBy(() -> transactions.execute(status -> notices.publish(appointment(
            RECIPIENT,
            "m0406-confirmed-event-01",
            NoticeType.APPOINTMENT_REJECTED,
            60_601
        )))).isInstanceOfSatisfying(BusinessException.class, error ->
            assertThat(error.code).isEqualTo("NOTICE_EVENT_CONFLICT")
        );
        assertThat(count("recipient_user_id=" + RECIPIENT)).isEqualTo(1);
    }

    @Test
    void outerBusinessRollbackLeavesNoNotice() {
        transactions.executeWithoutResult(status -> {
            notices.publish(appointment(
                RECIPIENT, "m0406-rollback-event-01", NoticeType.APPOINTMENT_CANCELLED, 60_602
            ));
            status.setRollbackOnly();
        });

        assertThat(count("recipient_user_id=" + RECIPIENT)).isZero();
    }

    @Test
    void listIsRecipientScopedPagedAndContainsOnlyGenericNavigationText() throws Exception {
        long unreadId = publish(appointment(
            RECIPIENT, "m0406-list-unread-001", NoticeType.APPOINTMENT_CONFIRMED, 60_603
        ));
        long readId = publish(appointment(
            RECIPIENT, "m0406-list-read-event", NoticeType.APPOINTMENT_REJECTED, 60_604
        ));
        publish(appointment(
            OTHER, "m0406-list-other-001", NoticeType.APPOINTMENT_CANCELLED, 60_605
        ));
        jdbc.update("UPDATE user_notice SET read_at=CURRENT_TIMESTAMP(3) WHERE id=?", readId);

        mockMvc.perform(get("/api/v1/notices?unread=true&page=1&pageSize=10")
                .with(jwtFor(RECIPIENT, "MINI", "USER")))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items.length()").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(unreadId)))
            .andExpect(jsonPath("$.data.items[0].body")
                .value("您的预约状态已更新，请打开预约查看详情。"))
            .andExpect(jsonPath("$.data.items[0].resourceType").value("APPOINTMENT"))
            .andExpect(jsonPath("$.data.items[0].eventKey").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].diagnosis").doesNotExist());

        mockMvc.perform(get("/api/v1/notices?unread=false&page=1&pageSize=1")
                .with(jwtFor(RECIPIENT, "WEB", "DOCTOR")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items[0].id").value(Long.toString(readId)));
    }

    @Test
    void markReadRejectsOtherRecipientAndSameKeyReplaysOneAudit() throws Exception {
        long noticeId = publish(appointment(
            RECIPIENT, "m0406-read-own-event", NoticeType.APPOINTMENT_RESCHEDULED, 60_606
        ));

        mockMvc.perform(post("/api/v1/notices/{noticeId}/read", noticeId)
                .header("Idempotency-Key", "m0406-other-read-key")
                .with(jwtFor(OTHER, "MINI", "USER")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));

        var own = jwtFor(RECIPIENT, "MINI", "USER");
        mockMvc.perform(post("/api/v1/notices/{noticeId}/read", noticeId)
                .header("Idempotency-Key", "m0406-own-read-key01")
                .with(own))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(Long.toString(noticeId)))
            .andExpect(jsonPath("$.data.readAt").isString());
        mockMvc.perform(post("/api/v1/notices/{noticeId}/read", noticeId)
                .header("Idempotency-Key", "m0406-own-read-key01")
                .with(jwtFor(RECIPIENT, "MINI", "USER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(Long.toString(noticeId)));

        assertThat(jdbc.queryForObject(
            "SELECT read_at IS NOT NULL FROM user_notice WHERE id=?", Boolean.class, noticeId
        )).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM audit_log
            WHERE actor_user_id=? AND operation_key='m0406-own-read-key01'
            """, Integer.class, RECIPIENT)).isEqualTo(1);
    }

    @Test
    void differentReadKeysRemainSideEffectFreeAndIdempotencyKeyCannotCrossNotice() {
        long first = publish(appointment(
            RECIPIENT, "m0406-read-first-001", NoticeType.APPOINTMENT_CONFIRMED, 60_607
        ));
        long second = publish(appointment(
            RECIPIENT, "m0406-read-second-01", NoticeType.APPOINTMENT_CONFIRMED, 60_608
        ));

        authenticate(RECIPIENT, "MINI", "USER");
        String readAt = notices.read(first, "m0406-read-idempotent1").readAt();
        assertThat(notices.read(first, "m0406-read-another-key").readAt()).isEqualTo(readAt);
        assertThatThrownBy(() -> notices.read(second, "m0406-read-idempotent1"))
            .isInstanceOfSatisfying(BusinessException.class, error ->
                assertThat(error.code).isEqualTo("IDEMPOTENCY_CONFLICT")
            );
        assertThat(jdbc.queryForObject(
            "SELECT read_at IS NULL FROM user_notice WHERE id=?", Boolean.class, second
        )).isTrue();
    }

    private long publish(NoticeCommand command) {
        Long result = transactions.execute(status -> notices.publish(command));
        if (result == null) throw new IllegalStateException("notice transaction returned null");
        return result;
    }

    private static NoticeCommand appointment(
        long recipient,
        String eventKey,
        NoticeType type,
        long appointmentId
    ) {
        return new NoticeCommand(recipient, eventKey, type, "APPOINTMENT", appointmentId);
    }

    private int count(String predicate) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_notice WHERE " + predicate,
            Integer.class
        );
    }

    private void cleanup() {
        if (!tableReady()) return;
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id IN (?,?)", RECIPIENT, OTHER);
        jdbc.update("DELETE FROM user_notice WHERE recipient_user_id IN (?,?)", RECIPIENT, OTHER);
        jdbc.update("DELETE FROM sys_user WHERE id IN (?,?)", RECIPIENT, OTHER);
    }

    private boolean tableReady() {
        try {
            jdbc.queryForObject("SELECT COUNT(*) FROM user_notice", Integer.class);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
        jwtFor(long userId, String audience, String role) {
        return jwt()
            .jwt(builder -> builder
                .claim("userId", Long.toString(userId))
                .claim("sessionId", "40601")
                .claim("roles", List.of(role))
                .audience(List.of(audience)))
            .authorities(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            );
    }

    private static void authenticate(long userId, String audience, String role) {
        Instant now = Instant.now();
        Jwt token = Jwt.withTokenValue("m0406-token")
            .header("alg", "RS256")
            .subject(Long.toString(userId))
            .claim("userId", Long.toString(userId))
            .claim("sessionId", "40601")
            .claim("roles", List.of(role))
            .audience(List.of(audience))
            .issuedAt(now.minusSeconds(1))
            .expiresAt(now.plusSeconds(300))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
            token,
            List.of(
                new SimpleGrantedAuthority("AUD_" + audience),
                new SimpleGrantedAuthority("ROLE_" + role)
            )
        ));
    }
}
