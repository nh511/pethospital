package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import com.pethospital.triage.TriageRiskPolicy;
import com.pethospital.triage.TriageRuleDtos.PreviewRuleResult;
import com.pethospital.triage.TriageRuleDtos.RulePreviewRequest;
import com.pethospital.triage.TriageRuleRuntimeService;
import com.pethospital.triage.TriageService;
import com.pethospital.triage.TriageServiceDtos.TriageRequest;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag(TestTags.MYSQL_IT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "hospital.name=回归测试宠物医院",
    "hospital.address=测试路106号",
    "hospital.phone=021-10060000",
    "hospital.business-hours=24小时急诊",
    "ai.deepseek.endpoint=http://127.0.0.1:1",
    "ai.deepseek.api-key="
})
class M1006IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long ADMIN = 100_601;
    private static final long DOCTOR = 100_602;
    private static final Set<String> REQUIRED_CONTEXTS = Set.of(
        "POSITIVE", "NEGATED", "HYPOTHETICAL", "HISTORICAL_RESOLVED", "UNKNOWN", "SPECIES_MISMATCH"
    );

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TriageRuleRuntimeService runtime;
    @Autowired TriageService triageService;
    @Autowired TriageRiskPolicy riskPolicy;

    private RegressionFixture fixture;

    @BeforeAll
    void loadAndSeedVersionedFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
            "/triage/m10-06-regression-v1.json"
        )) {
            assertThat(input).as("versioned regression fixture").isNotNull();
            fixture = objectMapper.readValue(input, RegressionFixture.class);
        }
        jdbc.update("DELETE FROM triage_event");
        jdbc.update("DELETE FROM triage_rule");
        jdbc.update("DELETE FROM audit_log WHERE actor_user_id>=100600");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=100600");
        jdbc.update("DELETE FROM sys_user WHERE id>=100600");
        seedUser(ADMIN, "triage-regression-admin");
        seedUser(DOCTOR, "triage-regression-doctor");
        for (int index = 0; index < fixture.rules().size(); index++) {
            insertApprovedRule(120_601L + index, fixture.rules().get(index));
        }
    }

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @Test
    void fixtureIsVersionedDemoOnlyAndEveryRuleHasCompleteContextMatrix() {
        assertThat(fixture.fixtureVersion()).isEqualTo("1");
        assertThat(fixture.clinicalUse()).isEqualTo("DEMO_NOT_CLINICALLY_VALIDATED");
        assertThat(fixture.doctorApprovalRequired()).isTrue();
        assertThat(fixture.rules()).hasSize(2);
        fixture.rules().forEach(rule -> {
            String ruleVersion = version(rule);
            assertThat(rule.sourceUrl()).as(ruleVersion).contains("/demo-only/");
            assertThat(rule.cases()).as(ruleVersion).hasSize(6);
            assertThat(rule.cases()).extracting(CaseFixture::context)
                .as(ruleVersion).containsExactlyInAnyOrderElementsOf(REQUIRED_CONTEXTS);
            assertThat(rule.cases()).allSatisfy(testCase ->
                assertThat(testCase.expectedRuleVersion()).isEqualTo(ruleVersion));
        });
    }

    @Test
    void enabledFixtureRulesAreDoctorApprovedAndDatabaseRejectsUnreviewedEnable() throws Exception {
        assertThat(count("""
            SELECT COUNT(*) FROM triage_rule
            WHERE enabled=1 AND (review_status<>'APPROVED' OR reviewer_user_id IS NULL OR reviewed_at IS NULL)
            """)).isZero();

        String predicate = objectMapper.writeValueAsString(objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"IS_PRESENT"}}
            """));
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO triage_rule(
                id,rule_code,revision_no,species_code,title,predicate_json,risk_level,fixed_advice,
                source_url,review_status,enabled,creator_user_id,version
            ) VALUES (129999,'UNREVIEWED_DEMO',1,'CAT','未审核演示',CAST(? AS JSON),'EMERGENCY',
                      '不应启用','https://example.org/demo-only/unreviewed','DRAFT',1,?,0)
            """, predicate, ADMIN))
            .isInstanceOf(DataAccessException.class);
        assertThat(count("SELECT COUNT(*) FROM triage_rule WHERE id=129999")).isZero();
    }

    @Test
    void modelRiskCanRaiseButNeverLowerRuleRisk() {
        assertThat(riskPolicy.highest("EMERGENCY", "ROUTINE")).isEqualTo("EMERGENCY");
        assertThat(riskPolicy.highest("URGENT", "UNKNOWN")).isEqualTo("URGENT");
        assertThat(riskPolicy.highest("ROUTINE", "EMERGENCY")).isEqualTo("EMERGENCY");
    }

    @TestFactory
    Stream<DynamicTest> everyDemoRuleRunsAllVersionLocatedCases() {
        return fixture.rules().stream().flatMap(rule -> rule.cases().stream().map(testCase ->
            DynamicTest.dynamicTest(version(rule) + "/" + testCase.caseId(),
                () -> assertRegressionCase(rule, testCase))
        ));
    }

    private void assertRegressionCase(RuleFixture rule, CaseFixture testCase) {
        String location = testCase.expectedRuleVersion() + "/" + testCase.caseId();
        var preview = runtime.evaluateEnabledRules(new RulePreviewRequest(
            testCase.speciesCode(), testCase.userMessage(), testCase.screeningAnswers()
        ));
        PreviewRuleResult target = preview.results().stream()
            .filter(result -> result.ruleCode().equals(rule.ruleCode()))
            .findFirst()
            .orElse(null);

        if ("SPECIES_MISMATCH".equals(testCase.context())) {
            assertThat(target).as(location + " target species must not be evaluated").isNull();
            assertThat(testCase.expectedOutcome()).as(location).isEqualTo("NOT_APPLICABLE");
            assertThat(testCase.expectedEvidenceState()).as(location)
                .isEqualTo("NOT_EVALUATED_SPECIES");
        } else {
            assertThat(target).as(location + " target rule result").isNotNull();
            assertThat(target.outcome()).as(location + " outcome")
                .isEqualTo(testCase.expectedOutcome());
            assertThat(target.evidence()).as(location + " evidence").singleElement()
                .satisfies(evidence -> assertThat(evidence.observedState())
                    .as(location + " evidence state")
                    .isEqualTo(testCase.expectedEvidenceState()));
        }

        var decision = triageService.triage(new TriageRequest(
            testCase.speciesCode(), testCase.userMessage(), testCase.screeningAnswers()
        ));
        assertThat(decision.riskLevel()).as(location + " decision risk")
            .isEqualTo(testCase.expectedRiskLevel());
        assertThat(decision.emergency()).as(location + " emergency")
            .isEqualTo(testCase.expectedEmergency());
        if (testCase.expectedEmergency()) {
            assertThat(decision.shortCircuitExternalServices()).as(location).isTrue();
            assertThat(decision.matchedRules()).extracting(ruleResult -> ruleResult.ruleCode())
                .as(location).contains(rule.ruleCode());
        } else {
            assertThat(decision.visibleContent()).as(location).contains("不代表已排除急症");
        }
    }

    private void insertApprovedRule(long id, RuleFixture rule) throws Exception {
        jdbc.update("""
            INSERT INTO triage_rule(
                id,rule_code,revision_no,species_code,title,predicate_json,risk_level,fixed_advice,
                source_url,review_status,enabled,creator_user_id,reviewer_user_id,review_note,
                reviewed_at,version
            ) VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,?,'APPROVED',1,?,?,?, ?,0)
            """, id, rule.ruleCode(), rule.revisionNo(), rule.speciesCode(),
            rule.ruleCode() + "演示规则", objectMapper.writeValueAsString(rule.predicate()),
            rule.riskLevel(), rule.fixedAdvice(), rule.sourceUrl(), ADMIN, DOCTOR,
            "仅用于自动化回归，非临床验证", Timestamp.from(Instant.now()));
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

    private static String version(RuleFixture rule) {
        return rule.ruleCode() + "@" + rule.revisionNo();
    }

    private record RegressionFixture(
        String fixtureVersion,
        String clinicalUse,
        boolean doctorApprovalRequired,
        List<RuleFixture> rules
    ) {
    }

    private record RuleFixture(
        String ruleCode,
        int revisionNo,
        String speciesCode,
        String riskLevel,
        String fixedAdvice,
        String sourceUrl,
        JsonNode predicate,
        List<CaseFixture> cases
    ) {
    }

    private record CaseFixture(
        String caseId,
        String context,
        String speciesCode,
        String userMessage,
        Map<String, String> screeningAnswers,
        String expectedOutcome,
        String expectedEvidenceState,
        String expectedRiskLevel,
        boolean expectedEmergency,
        String expectedRuleVersion
    ) {
    }
}
