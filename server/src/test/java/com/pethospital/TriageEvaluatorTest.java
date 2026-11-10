package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.triage.InvalidTriagePredicateException;
import com.pethospital.triage.TriageDtos.Observation;
import com.pethospital.triage.TriageDtos.ObservationContext;
import com.pethospital.triage.TriageDtos.Outcome;
import com.pethospital.triage.TriageDtos.TriageInput;
import com.pethospital.triage.TriageDtos.TruthValue;
import com.pethospital.triage.TriageEvaluator;
import com.pethospital.triage.TriageTextInterpreter;
import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TriageEvaluatorTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TriageEvaluator evaluator = new TriageEvaluator();

    @Test
    void confirmedCurrentSymptomMatchesAndReturnsShortEvidence() throws Exception {
        var result = evaluator.evaluate(symptomPredicate(), input(
            "CAT",
            Map.of("BREATHING_DIFFICULTY", new Observation(
                ObservationContext.PRESENT, 8, "现在张口呼吸"
            )),
            Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(result.truth()).isEqualTo(TruthValue.TRUE);
        assertThat(result.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.code()).isEqualTo("BREATHING_DIFFICULTY");
            assertThat(evidence.observedState()).isEqualTo("PRESENT");
            assertThat(evidence.excerpt()).isEqualTo("现在张口呼吸");
        });
    }

    @ParameterizedTest
    @EnumSource(value = ObservationContext.class, names = {
        "ABSENT", "HYPOTHETICAL", "HISTORICAL_RESOLVED"
    })
    void negatedHypotheticalAndResolvedHistoryDoNotMatch(ObservationContext context)
        throws Exception {
        var result = evaluator.evaluate(symptomPredicate(), input(
            "CAT",
            Map.of("BREATHING_DIFFICULTY", new Observation(context, null, "测试语境")),
            Map.of()
        ));

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_MATCHED);
        assertThat(result.truth()).isEqualTo(TruthValue.FALSE);
    }

    @Test
    void ambiguousOrMissingEvidenceRemainsUnknownInsteadOfSafe() throws Exception {
        var ambiguous = evaluator.evaluate(symptomPredicate(), input(
            "CAT",
            Map.of("BREATHING_DIFFICULTY", new Observation(
                ObservationContext.UNKNOWN, null, "描述不清"
            )),
            Map.of()
        ));
        var missing = evaluator.evaluate(symptomPredicate(), input("CAT", Map.of(), Map.of()));

        assertThat(ambiguous.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(missing.outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(missing.evidence()).singleElement()
            .extracting(evidence -> evidence.observedState())
            .isEqualTo("MISSING");
    }

    @Test
    void allAndAnyUseSafeThreeValuedLogic() throws Exception {
        JsonNode all = objectMapper.readTree("""
            {
              "schemaVersion":"1",
              "applicableSpecies":["CAT","DOG"],
              "expression":{"operator":"ALL","children":[
                {"fact":"SYMPTOM","code":"UNCONTROLLED_BLEEDING","operator":"IS_PRESENT"},
                {"fact":"SCREENING","code":"consciousness","operator":"EQ","value":"UNRESPONSIVE"}
              ]}
            }
            """);
        JsonNode any = all.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) any.path("expression"))
            .put("operator", "ANY");
        TriageInput presentAndUnknown = input(
            "DOG",
            Map.of("UNCONTROLLED_BLEEDING", new Observation(
                ObservationContext.PRESENT, null, "持续出血"
            )),
            Map.of("consciousness", "UNKNOWN")
        );

        assertThat(evaluator.evaluate(all, presentAndUnknown).outcome()).isEqualTo(Outcome.UNKNOWN);
        assertThat(evaluator.evaluate(any, presentAndUnknown).outcome()).isEqualTo(Outcome.MATCHED);

        TriageInput absentAndUnknown = input(
            "DOG",
            Map.of("UNCONTROLLED_BLEEDING", new Observation(
                ObservationContext.ABSENT, null, "没有出血"
            )),
            Map.of("consciousness", "UNKNOWN")
        );
        assertThat(evaluator.evaluate(all, absentAndUnknown).outcome())
            .isEqualTo(Outcome.NOT_MATCHED);
        assertThat(evaluator.evaluate(any, absentAndUnknown).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void screeningAndDurationConditionsAreClosedAndDeterministic() throws Exception {
        JsonNode predicate = objectMapper.readTree("""
            {
              "schemaVersion":"1",
              "applicableSpecies":["CAT"],
              "expression":{"operator":"ALL","children":[
                {"fact":"SCREENING","code":"breathingDifficulty","operator":"IN","values":["YES","SEVERE"]},
                {"fact":"DURATION_MINUTES","code":"BREATHING_DIFFICULTY","operator":"GTE","value":5}
              ]}
            }
            """);
        TriageInput input = input(
            "CAT",
            Map.of("BREATHING_DIFFICULTY", new Observation(
                ObservationContext.PRESENT, 6, "呼吸困难约六分钟"
            )),
            Map.of("breathingDifficulty", "YES")
        );

        assertThat(evaluator.evaluate(predicate, input).outcome()).isEqualTo(Outcome.MATCHED);
        assertThat(evaluator.evaluate(predicate, input).evidence()).hasSize(2);
    }

    @Test
    void textInterpreterTreatsOutOfRangeDurationAsUnknownInsteadOfThrowing() throws Exception {
        JsonNode predicate = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"DURATION_MINUTES","code":"SEIZURE","operator":"GTE","value":5}}
            """);

        TriageInput input = new TriageTextInterpreter().interpret(
            predicate, "CAT", "猫咪已经抽搐9999天", Map.of()
        );

        assertThat(input.symptoms().get("SEIZURE").durationMinutes()).isNull();
        assertThat(evaluator.evaluate(predicate, input).outcome()).isEqualTo(Outcome.UNKNOWN);
    }

    @Test
    void unsupportedSpeciesIsExplicitlyNotApplicable() throws Exception {
        var other = evaluator.evaluate(symptomPredicate(), input("BIRD", Map.of(), Map.of()));
        var dogAgainstCatRule = evaluator.evaluate(symptomPredicate(), input("DOG", Map.of(), Map.of()));

        assertThat(other.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(other.coverageLimited()).isTrue();
        assertThat(dogAgainstCatRule.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(dogAgainstCatRule.coverageLimited()).isFalse();
    }

    @Test
    void scriptUnknownOperatorsAndUnknownPropertiesAreRejected() throws Exception {
        JsonNode script = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SCRIPT","code":"X","operator":"EVAL","script":"Runtime.exec()"}}
            """);
        JsonNode unknownOperator = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"REGEX"}}
            """);
        JsonNode unknownProperty = objectMapper.readTree("""
            {"schemaVersion":"1","applicableSpecies":["CAT"],"sql":"SELECT 1",
             "expression":{"fact":"SYMPTOM","code":"SEIZURE","operator":"IS_PRESENT"}}
            """);

        assertThatThrownBy(() -> evaluator.validate(script))
            .isInstanceOf(InvalidTriagePredicateException.class)
            .hasMessageContaining("unsupported fact");
        assertThatThrownBy(() -> evaluator.validate(unknownOperator))
            .isInstanceOf(InvalidTriagePredicateException.class)
            .hasMessageContaining("only supports IS_PRESENT");
        assertThatThrownBy(() -> evaluator.validate(unknownProperty))
            .isInstanceOf(InvalidTriagePredicateException.class)
            .hasMessageContaining("unknown=[sql]");
    }

    @Test
    void publishedJsonSchemaIsStrictAndMatchesCompilerVocabulary() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
            "/schema/triage-rule-predicate.schema.json"
        )) {
            assertThat(stream).isNotNull();
            JsonNode schema = objectMapper.readTree(stream);
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            assertThat(schema.path("properties").path("applicableSpecies")
                .path("items").path("enum")).hasSize(2);
            assertThat(schema.path("$defs").path("node").path("oneOf")).hasSize(5);
        }
        evaluator.validate(symptomPredicate());
    }

    private JsonNode symptomPredicate() throws Exception {
        return objectMapper.readTree("""
            {
              "schemaVersion":"1",
              "applicableSpecies":["CAT"],
              "expression":{
                "fact":"SYMPTOM",
                "code":"BREATHING_DIFFICULTY",
                "operator":"IS_PRESENT"
              }
            }
            """);
    }

    private static TriageInput input(
        String species,
        Map<String, Observation> symptoms,
        Map<String, String> answers
    ) {
        return new TriageInput(species, symptoms, answers);
    }
}
