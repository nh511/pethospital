package com.pethospital.triage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TriageDtos {
    private TriageDtos() {
    }

    public enum ObservationContext {
        PRESENT,
        ABSENT,
        HYPOTHETICAL,
        HISTORICAL_RESOLVED,
        UNKNOWN
    }

    public enum TruthValue {
        TRUE,
        FALSE,
        UNKNOWN
    }

    public enum Outcome {
        MATCHED,
        NOT_MATCHED,
        UNKNOWN,
        NOT_APPLICABLE
    }

    public record Observation(
        ObservationContext context,
        Integer durationMinutes,
        String excerpt
    ) {
        public Observation {
            context = Objects.requireNonNull(context, "context");
            if (durationMinutes != null && (durationMinutes < 0 || durationMinutes > 525_600)) {
                throw new IllegalArgumentException("durationMinutes must be between 0 and 525600");
            }
            excerpt = excerpt == null ? "" : excerpt.strip();
            if (excerpt.length() > 240) {
                throw new IllegalArgumentException("observation excerpt must not exceed 240 characters");
            }
        }
    }

    public record TriageInput(
        String speciesCode,
        Map<String, Observation> symptoms,
        Map<String, String> screeningAnswers
    ) {
        public TriageInput {
            speciesCode = normalizeCode(speciesCode, "speciesCode");
            symptoms = immutableSymptoms(symptoms);
            screeningAnswers = immutableAnswers(screeningAnswers);
        }

        private static Map<String, Observation> immutableSymptoms(Map<String, Observation> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            var normalized = new LinkedHashMap<String, Observation>();
            source.forEach((code, observation) -> normalized.put(
                normalizeCode(code, "symptom code"),
                Objects.requireNonNull(observation, "observation")
            ));
            return Map.copyOf(normalized);
        }

        private static Map<String, String> immutableAnswers(Map<String, String> source) {
            if (source == null || source.isEmpty()) {
                return Map.of();
            }
            var normalized = new LinkedHashMap<String, String>();
            source.forEach((key, value) -> {
                String normalizedKey = Objects.requireNonNull(key, "screening key").strip();
                if (!normalizedKey.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("invalid screening key: " + normalizedKey);
                }
                normalized.put(normalizedKey, normalizeCode(value, "screening answer"));
            });
            return Map.copyOf(normalized);
        }
    }

    public record Evidence(
        String fact,
        String code,
        String operator,
        String observedState,
        TruthValue result,
        String excerpt
    ) {
        public Evidence {
            fact = Objects.requireNonNull(fact, "fact");
            code = Objects.requireNonNull(code, "code");
            operator = Objects.requireNonNull(operator, "operator");
            observedState = Objects.requireNonNull(observedState, "observedState");
            result = Objects.requireNonNull(result, "result");
            excerpt = excerpt == null ? "" : excerpt;
        }
    }

    public record Evaluation(
        Outcome outcome,
        TruthValue truth,
        boolean coverageLimited,
        List<Evidence> evidence
    ) {
        public Evaluation {
            outcome = Objects.requireNonNull(outcome, "outcome");
            truth = Objects.requireNonNull(truth, "truth");
            evidence = List.copyOf(evidence);
        }
    }

    private static String normalizeCode(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid " + name + ": " + normalized);
        }
        return normalized;
    }
}
