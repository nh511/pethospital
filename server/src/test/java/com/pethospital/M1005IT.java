package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import com.pethospital.triage.TriageService;
import com.pethospital.triage.TriageServiceDtos.RiskDecision;
import com.pethospital.triage.TriageServiceDtos.TriageRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "hospital.name=测试宠物医院",
    "hospital.address=测试路100号",
    "hospital.phone=021-12345678",
    "hospital.business-hours=24小时急诊",
    "ai.deepseek.endpoint=http://127.0.0.1:1",
    "ai.deepseek.api-key=",
    "ai.qdrant.endpoint=http://127.0.0.1:1"
})
class M1005IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 100_501;
    private static final long DOCTOR = 100_502;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TriageService triageService;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM triage_event");
        jdbc.update("DELETE FROM triage_rule");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=100500");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=100500");
        jdbc.update("DELETE FROM sys_user WHERE id>=100500");
        seedUser(ADMIN, "triage-service-admin");
        seedUser(DOCTOR, "triage-service-doctor");
    }

    @Test
    void explicitEmergencyReturnsFixedReplyWithoutWaitingForQuestionnaire() throws Exception {
        insertSymptomRule(120_501, "SEIZURE_EMERGENCY", "CAT", "SEIZURE", "EMERGENCY",
            "保持环境安全并立即送医", true, "APPROVED", 1);

        RiskDecision decision = triageService.triage(new TriageRequest(
            "cat", "猫咪现在正在抽搐", Map.of()
        ));

        assertThat(decision.riskLevel()).isEqualTo("EMERGENCY");
        assertThat(decision.emergency()).isTrue();
        assertThat(decision.shortCircuitExternalServices()).isTrue();
        assertThat(decision.skipRemainingQuestionnaire()).isTrue();
        assertThat(decision.continueWithAi()).isFalse();
        assertThat(decision.fixedAdvice()).isEqualTo("保持环境安全并立即送医");
        assertThat(decision.visibleContent())
            .contains("保持环境安全并立即送医", "测试宠物医院", "021-12345678", "24小时急诊");
        assertThat(decision.disclaimer()).isEqualTo(TriageService.DISCLAIMER);
        assertThat(decision.matchedRules()).singleElement()
            .satisfies(rule -> assertThat(rule.evidence()).singleElement()
                .satisfies(evidence -> assertThat(evidence.observedState()).isEqualTo("PRESENT")));
        assertThat(decision.symptomCodes()).containsExactly("SEIZURE");
    }

    @Test
    void screeningAnswerCanTriggerEmergencyWhenMessageIsOtherwiseUnclear() throws Exception {
        insertScreeningRule(120_511, "POISONING_SCREEN", "CAT", "suspectedPoisoning", "YES",
            "请携带疑似毒物包装立即就医");

        RiskDecision decision = triageService.triage(new TriageRequest(
            "CAT", "情况说不清楚", Map.of("suspectedPoisoning", "yes")
        ));

        assertThat(decision.emergency()).isTrue();
        assertThat(decision.matchedRules()).singleElement()
            .satisfies(rule -> assertThat(rule.evidence()).singleElement()
                .satisfies(evidence -> {
                    assertThat(evidence.fact()).isEqualTo("SCREENING");
                    assertThat(evidence.observedState()).isEqualTo("YES");
                }));
    }

    @Test
    void noMatchRemainsUnknownAndExplicitlyDoesNotExcludeEmergency() throws Exception {
        insertSymptomRule(120_521, "NO_SAFE_CLAIM", "CAT", "SEIZURE", "EMERGENCY",
            "立即就医", true, "APPROVED", 1);

        RiskDecision decision = triageService.triage(new TriageRequest(
            "CAT", "猫咪今天没有抽搐", Map.of()
        ));

        assertThat(decision.riskLevel()).isEqualTo("UNKNOWN");
        assertThat(decision.emergency()).isFalse();
        assertThat(decision.continueWithAi()).isTrue();
        assertThat(decision.matchedRules()).isEmpty();
        assertThat(decision.visibleContent())
            .isEqualTo(TriageService.NO_MATCH_WARNING)
            .contains("不代表已排除急症");
        assertThat(decision.visibleContent()).doesNotContain("安全", "已排除急症。");
    }

    @Test
    void onlyEnabledApprovedSpeciesRulesParticipateAndContextsStaySafe() throws Exception {
        insertSymptomRule(120_531, "ACTIVE_CAT", "CAT", "SEIZURE", "EMERGENCY",
            "立即就医", true, "APPROVED", 1);
        insertSymptomRule(120_532, "DISABLED_CAT", "CAT", "SEIZURE", "EMERGENCY",
            "不应返回", false, "APPROVED", 1);
        insertSymptomRule(120_533, "DRAFT_CAT", "CAT", "SEIZURE", "EMERGENCY",
            "不应返回", false, "DRAFT", 1);
        insertSymptomRule(120_534, "ACTIVE_DOG", "DOG", "SEIZURE", "EMERGENCY",
            "不应返回", true, "APPROVED", 1);

        RiskDecision hypothetical = triageService.triage(new TriageRequest(
            "CAT", "如果猫咪抽搐应该怎么办", Map.of()
        ));
        RiskDecision history = triageService.triage(new TriageRequest(
            "CAT", "之前抽搐但现在已经好了", Map.of()
        ));

        assertThat(hypothetical.evaluatedRuleCount()).isOne();
        assertThat(hypothetical.emergency()).isFalse();
        assertThat(history.emergency()).isFalse();
        assertThat(hypothetical.matchedRules()).isEmpty();
        assertThat(history.matchedRules()).isEmpty();
    }

    @Test
    void emergencyHasHighestPriorityAndTriageNeverPersistsOrCallsExternalServices() throws Exception {
        insertSymptomRule(120_541, "A_URGENT", "CAT", "UNCONTROLLED_BLEEDING", "URGENT",
            "尽快就医", true, "APPROVED", 1);
        insertSymptomRule(120_542, "Z_EMERGENCY", "CAT", "SEIZURE", "EMERGENCY",
            "立即急诊", true, "APPROVED", 1);

        RiskDecision decision = triageService.triage(new TriageRequest(
            "CAT", "猫咪正在抽搐并且大量出血", Map.of()
        ));

        assertThat(decision.riskLevel()).isEqualTo("EMERGENCY");
        assertThat(decision.fixedAdvice()).isEqualTo("立即急诊");
        assertThat(decision.matchedRules()).extracting(rule -> rule.riskLevel())
            .containsExactly("EMERGENCY", "URGENT");
        assertThat(decision.symptomCodes()).containsExactly("UNCONTROLLED_BLEEDING", "SEIZURE");
        assertThat(count("SELECT COUNT(*) FROM triage_event")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_message")).isZero();
        assertThat(count("SELECT COUNT(*) FROM ai_call_log")).isZero();
        assertThat(count("SELECT COUNT(*) FROM audit_log")).isZero();

        assertThatThrownBy(() -> triageService.triage(new TriageRequest(
            "BIRD", "正在抽搐", Map.of()
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CAT or DOG");
    }

    private void insertSymptomRule(
        long id,
        String code,
        String species,
        String symptom,
        String risk,
        String advice,
        boolean enabled,
        String status,
        int revision
    ) throws Exception {
        String predicate = objectMapper.writeValueAsString(objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["%s"],
             "expression":{"fact":"SYMPTOM","code":"%s","operator":"IS_PRESENT"}}
            """.formatted(species, symptom)));
        insertRule(id, code, species, risk, advice, enabled, status, revision, predicate);
    }

    private void insertScreeningRule(
        long id,
        String code,
        String species,
        String screeningCode,
        String expected,
        String advice
    ) throws Exception {
        String predicate = objectMapper.writeValueAsString(objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["%s"],
             "expression":{"fact":"SCREENING","code":"%s","operator":"EQ","value":"%s"}}
            """.formatted(species, screeningCode, expected)));
        insertRule(id, code, species, "EMERGENCY", advice, true, "APPROVED", 1, predicate);
    }

    private void insertRule(
        long id,
        String code,
        String species,
        String risk,
        String advice,
        boolean enabled,
        String status,
        int revision,
        String predicate
    ) {
        boolean reviewed = "APPROVED".equals(status) || "REJECTED".equals(status);
        jdbc.update("""
            INSERT INTO triage_rule(
                id,rule_code,revision_no,species_code,title,predicate_json,risk_level,fixed_advice,
                source_url,review_status,enabled,creator_user_id,reviewer_user_id,review_note,
                reviewed_at,version
            ) VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,'https://example.org/triage-source',
                      ?,?,?,?,?,?,0)
            """, id, code, revision, species, code + "标题", predicate, risk, advice,
            status, enabled, ADMIN, reviewed ? DOCTOR : null, reviewed ? "已审核" : null,
            reviewed ? Timestamp.from(Instant.now()) : null);
    }

    private void seedUser(long id, String username) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, username);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
