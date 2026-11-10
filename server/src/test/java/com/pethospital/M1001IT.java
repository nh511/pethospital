package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import com.pethospital.triage.InvalidTriagePredicateException;
import com.pethospital.triage.TriageDtos.Observation;
import com.pethospital.triage.TriageDtos.ObservationContext;
import com.pethospital.triage.TriageDtos.Outcome;
import com.pethospital.triage.TriageDtos.TriageInput;
import com.pethospital.triage.TriageEvaluator;
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
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
})
class M1001IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final long CREATOR_ID = 100_101;
    private static final long RULE_ID = 100_201;

    private static final String PREDICATE = """
        {
          "schemaVersion":"1",
          "applicableSpecies":["CAT","DOG"],
          "expression":{"operator":"ANY","children":[
            {"fact":"SYMPTOM","code":"UNCONTROLLED_BLEEDING","operator":"IS_PRESENT"},
            {"fact":"SCREENING","code":"consciousness","operator":"EQ","value":"UNRESPONSIVE"}
          ]}
        }
        """;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TriageEvaluator evaluator;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM triage_event");
        jdbc.update("DELETE FROM triage_rule WHERE id=?", RULE_ID);
        jdbc.update("DELETE FROM sys_user WHERE id=?", CREATOR_ID);
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, CREATOR_ID, "triage-rule-creator", "急症规则创建人");
        jdbc.update("""
            INSERT INTO triage_rule(
              id,rule_code,revision_no,species_code,title,predicate_json,risk_level,
              fixed_advice,source_url,review_status,enabled,creator_user_id,version
            ) VALUES (?,?,1,'CAT',?,?,'EMERGENCY',?,?,'DRAFT',0,?,0)
            """,
            RULE_ID,
            "CRITICAL_BLEEDING_OR_UNRESPONSIVE",
            "持续出血或无反应",
            PREDICATE,
            "请立即联系医院并尽快就医",
            "https://example.invalid/veterinary-triage-source",
            CREATOR_ID
        );
    }

    @Test
    void persistedPredicateMatchesConfirmedEvidence() throws Exception {
        JsonNode predicate = storedPredicate();
        var result = evaluator.evaluate(predicate, new TriageInput(
            "CAT",
            Map.of("UNCONTROLLED_BLEEDING", new Observation(
                ObservationContext.PRESENT, 3, "伤口持续出血"
            )),
            Map.of("consciousness", "RESPONSIVE")
        ));

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.evidence()).hasSize(2);
        assertThat(result.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.code()).isEqualTo("UNCONTROLLED_BLEEDING");
            assertThat(evidence.observedState()).isEqualTo("PRESENT");
        });
    }

    @Test
    void negationHypothesisHistoryAndUnknownHaveDistinctSafeResults() throws Exception {
        JsonNode predicate = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["DOG"],
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"IS_PRESENT"}}
            """);
        for (ObservationContext context : new ObservationContext[] {
            ObservationContext.ABSENT,
            ObservationContext.HYPOTHETICAL,
            ObservationContext.HISTORICAL_RESOLVED
        }) {
            assertThat(evaluator.evaluate(predicate, dogInput(context)).outcome())
                .as(context.name())
                .isEqualTo(Outcome.NOT_MATCHED);
        }
        assertThat(evaluator.evaluate(predicate, dogInput(ObservationContext.UNKNOWN)).outcome())
            .isEqualTo(Outcome.UNKNOWN);
        assertThat(evaluator.evaluate(predicate, new TriageInput("DOG", Map.of(), Map.of())).outcome())
            .isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void databaseTextCannotIntroduceScriptOrUnknownOperation() throws Exception {
        JsonNode malicious = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"IS_PRESENT",
             "script":"java.lang.Runtime.getRuntime()"}}
            """);

        assertThatThrownBy(() -> evaluator.evaluate(
            malicious,
            new TriageInput("CAT", Map.of(), Map.of())
        )).isInstanceOf(InvalidTriagePredicateException.class)
            .hasMessageContaining("unknown=[script]");
        assertThat(evaluator.evaluate(storedPredicate(), new TriageInput(
            "BIRD", Map.of(), Map.of()
        )).coverageLimited()).isTrue();
    }

    private JsonNode storedPredicate() throws Exception {
        String json = jdbc.queryForObject(
            "SELECT CAST(predicate_json AS CHAR) FROM triage_rule WHERE id=?",
            String.class,
            RULE_ID
        );
        return objectMapper.readTree(json);
    }

    private static TriageInput dogInput(ObservationContext context) {
        return new TriageInput(
            "DOG",
            Map.of("SEIZURE", new Observation(context, null, "测试语境")),
            Map.of()
        );
    }
}
