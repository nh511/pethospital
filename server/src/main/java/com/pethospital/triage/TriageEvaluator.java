package com.pethospital.triage;

import com.fasterxml.jackson.databind.JsonNode;
import com.pethospital.triage.TriageDtos.Evaluation;
import com.pethospital.triage.TriageDtos.Evidence;
import com.pethospital.triage.TriageDtos.Observation;
import com.pethospital.triage.TriageDtos.ObservationContext;
import com.pethospital.triage.TriageDtos.Outcome;
import com.pethospital.triage.TriageDtos.TriageInput;
import com.pethospital.triage.TriageDtos.TruthValue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public final class TriageEvaluator {
    private static final Set<String> SUPPORTED_SPECIES = Set.of("CAT", "DOG");
    private static final int MAX_DEPTH = 5;
    private static final int MAX_NODES = 50;

    public void validate(JsonNode predicate) {
        compile(predicate);
    }

    public Evaluation evaluate(JsonNode predicate, TriageInput input) {
        CompiledRule rule = compile(predicate);
        String species = input.speciesCode();
        if (!rule.species().contains(species)) {
            boolean coverageLimited = !SUPPORTED_SPECIES.contains(species);
            return new Evaluation(
                Outcome.NOT_APPLICABLE,
                TruthValue.UNKNOWN,
                coverageLimited,
                List.of()
            );
        }
        NodeResult result = evaluate(rule.expression(), input);
        Outcome outcome = switch (result.truth()) {
            case TRUE -> Outcome.MATCHED;
            case FALSE -> Outcome.NOT_MATCHED;
            case UNKNOWN -> Outcome.UNKNOWN;
        };
        return new Evaluation(outcome, result.truth(), false, result.evidence());
    }

    private CompiledRule compile(JsonNode predicate) {
        requireObject(predicate, "$");
        requireExactFields(predicate, "$", Set.of("schemaVersion", "applicableSpecies", "expression"));
        if (!"1".equals(requireText(predicate, "schemaVersion", "$"))) {
            throw invalid("$.schemaVersion", "only schema version 1 is supported");
        }
        JsonNode speciesNode = predicate.get("applicableSpecies");
        if (speciesNode == null || !speciesNode.isArray() || speciesNode.isEmpty()
            || speciesNode.size() > SUPPORTED_SPECIES.size()) {
            throw invalid("$.applicableSpecies", "must be a non-empty array with at most two items");
        }
        var species = new LinkedHashSet<String>();
        for (int index = 0; index < speciesNode.size(); index++) {
            JsonNode item = speciesNode.get(index);
            if (!item.isTextual()) {
                throw invalid("$.applicableSpecies[" + index + "]", "must be a string");
            }
            String code = item.asText().strip().toUpperCase(Locale.ROOT);
            if (!SUPPORTED_SPECIES.contains(code)) {
                throw invalid("$.applicableSpecies[" + index + "]", "must be CAT or DOG");
            }
            if (!species.add(code)) {
                throw invalid("$.applicableSpecies", "must not contain duplicates");
            }
        }
        var counter = new AtomicInteger();
        RuleNode expression = compileNode(predicate.get("expression"), "$.expression", 1, counter);
        return new CompiledRule(Set.copyOf(species), expression);
    }

    private RuleNode compileNode(JsonNode node, String path, int depth, AtomicInteger counter) {
        requireObject(node, path);
        if (depth > MAX_DEPTH) {
            throw invalid(path, "nesting depth exceeds " + MAX_DEPTH);
        }
        if (counter.incrementAndGet() > MAX_NODES) {
            throw invalid(path, "node count exceeds " + MAX_NODES);
        }
        if (node.has("fact")) {
            return compileCondition(node, path);
        }
        requireExactFields(node, path, Set.of("operator", "children"));
        String operator = requireText(node, "operator", path);
        if (!"ALL".equals(operator) && !"ANY".equals(operator)) {
            throw invalid(path + ".operator", "group operator must be ALL or ANY");
        }
        JsonNode children = node.get("children");
        if (children == null || !children.isArray() || children.isEmpty() || children.size() > 20) {
            throw invalid(path + ".children", "must contain between 1 and 20 nodes");
        }
        var compiled = new ArrayList<RuleNode>();
        for (int index = 0; index < children.size(); index++) {
            compiled.add(compileNode(children.get(index), path + ".children[" + index + "]",
                depth + 1, counter));
        }
        return new GroupNode(GroupOperator.valueOf(operator), List.copyOf(compiled));
    }

    private RuleNode compileCondition(JsonNode node, String path) {
        String fact = requireText(node, "fact", path);
        return switch (fact) {
            case "SYMPTOM" -> {
                requireExactFields(node, path, Set.of("fact", "code", "operator"));
                String operator = requireText(node, "operator", path);
                if (!"IS_PRESENT".equals(operator)) {
                    throw invalid(path + ".operator", "SYMPTOM only supports IS_PRESENT");
                }
                yield new SymptomNode(requireCode(node, "code", path));
            }
            case "SCREENING" -> compileScreening(node, path);
            case "DURATION_MINUTES" -> {
                requireExactFields(node, path, Set.of("fact", "code", "operator", "value"));
                String operator = requireText(node, "operator", path);
                if (!"GTE".equals(operator) && !"LTE".equals(operator)) {
                    throw invalid(path + ".operator", "DURATION_MINUTES supports GTE or LTE");
                }
                int value = requireBoundedInteger(node, "value", path, 0, 525_600);
                yield new DurationNode(requireCode(node, "code", path),
                    NumericOperator.valueOf(operator), value);
            }
            default -> throw invalid(path + ".fact", "unsupported fact: " + fact);
        };
    }

    private RuleNode compileScreening(JsonNode node, String path) {
        String operator = requireText(node, "operator", path);
        String key = requireScreeningKey(node, path);
        if ("EQ".equals(operator)) {
            requireExactFields(node, path, Set.of("fact", "code", "operator", "value"));
            return new ScreeningNode(key, Set.of(requireCode(node, "value", path)), "EQ");
        }
        if ("IN".equals(operator)) {
            requireExactFields(node, path, Set.of("fact", "code", "operator", "values"));
            JsonNode values = node.get("values");
            if (values == null || !values.isArray() || values.isEmpty() || values.size() > 20) {
                throw invalid(path + ".values", "must contain between 1 and 20 values");
            }
            var expected = new LinkedHashSet<String>();
            for (int index = 0; index < values.size(); index++) {
                JsonNode value = values.get(index);
                if (!value.isTextual()) {
                    throw invalid(path + ".values[" + index + "]", "must be a string code");
                }
                String code = normalizeCode(value.asText(), path + ".values[" + index + "]");
                if (!expected.add(code)) {
                    throw invalid(path + ".values", "must not contain duplicates");
                }
            }
            return new ScreeningNode(key, Set.copyOf(expected), "IN");
        }
        throw invalid(path + ".operator", "SCREENING supports EQ or IN");
    }

    private NodeResult evaluate(RuleNode node, TriageInput input) {
        if (node instanceof GroupNode group) {
            var evidence = new ArrayList<Evidence>();
            var truths = new ArrayList<TruthValue>();
            for (RuleNode child : group.children()) {
                NodeResult childResult = evaluate(child, input);
                truths.add(childResult.truth());
                evidence.addAll(childResult.evidence());
            }
            return new NodeResult(combine(group.operator(), truths), List.copyOf(evidence));
        }
        if (node instanceof SymptomNode symptom) {
            Observation observation = input.symptoms().get(symptom.code());
            TruthValue truth = symptomTruth(observation);
            return leaf("SYMPTOM", symptom.code(), "IS_PRESENT", observationState(observation), truth,
                observation == null ? "" : observation.excerpt());
        }
        if (node instanceof ScreeningNode screening) {
            String answer = input.screeningAnswers().get(screening.code());
            TruthValue truth = answer == null || "UNKNOWN".equals(answer)
                ? TruthValue.UNKNOWN
                : screening.expected().contains(answer) ? TruthValue.TRUE : TruthValue.FALSE;
            return leaf("SCREENING", screening.code(), screening.operator(),
                answer == null ? "MISSING" : answer,
                truth, "");
        }
        DurationNode duration = (DurationNode) node;
        Observation observation = input.symptoms().get(duration.code());
        TruthValue truth;
        if (observation == null || observation.context() == ObservationContext.UNKNOWN) {
            truth = TruthValue.UNKNOWN;
        } else if (observation.context() != ObservationContext.PRESENT) {
            truth = TruthValue.FALSE;
        } else if (observation.durationMinutes() == null) {
            truth = TruthValue.UNKNOWN;
        } else {
            boolean matches = duration.operator() == NumericOperator.GTE
                ? observation.durationMinutes() >= duration.value()
                : observation.durationMinutes() <= duration.value();
            truth = matches ? TruthValue.TRUE : TruthValue.FALSE;
        }
        String observed = observation == null
            ? "MISSING"
            : observation.context() + ":" + (observation.durationMinutes() == null
                ? "UNKNOWN" : observation.durationMinutes());
        return leaf("DURATION_MINUTES", duration.code(), duration.operator().name(), observed, truth,
            observation == null ? "" : observation.excerpt());
    }

    private static TruthValue combine(GroupOperator operator, List<TruthValue> values) {
        if (operator == GroupOperator.ALL) {
            if (values.contains(TruthValue.FALSE)) {
                return TruthValue.FALSE;
            }
            return values.contains(TruthValue.UNKNOWN) ? TruthValue.UNKNOWN : TruthValue.TRUE;
        }
        if (values.contains(TruthValue.TRUE)) {
            return TruthValue.TRUE;
        }
        return values.contains(TruthValue.UNKNOWN) ? TruthValue.UNKNOWN : TruthValue.FALSE;
    }

    private static TruthValue symptomTruth(Observation observation) {
        if (observation == null || observation.context() == ObservationContext.UNKNOWN) {
            return TruthValue.UNKNOWN;
        }
        return observation.context() == ObservationContext.PRESENT
            ? TruthValue.TRUE
            : TruthValue.FALSE;
    }

    private static String observationState(Observation observation) {
        return observation == null ? "MISSING" : observation.context().name();
    }

    private static NodeResult leaf(
        String fact,
        String code,
        String operator,
        String observed,
        TruthValue truth,
        String excerpt
    ) {
        return new NodeResult(truth, List.of(new Evidence(
            fact, code, operator, observed, truth, excerpt
        )));
    }

    private static void requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw invalid(path, "must be an object");
        }
    }

    private static void requireExactFields(JsonNode node, String path, Set<String> allowed) {
        var actual = new LinkedHashSet<String>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(allowed)) {
            var missing = new LinkedHashSet<>(allowed);
            missing.removeAll(actual);
            var unknown = new LinkedHashSet<>(actual);
            unknown.removeAll(allowed);
            throw invalid(path, "fields must match exactly; missing=" + missing + ", unknown=" + unknown);
        }
    }

    private static String requireText(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw invalid(path + "." + field, "must be a non-blank string");
        }
        return value.asText().strip();
    }

    private static String requireCode(JsonNode node, String field, String path) {
        return normalizeCode(requireText(node, field, path), path + "." + field);
    }

    private static String requireScreeningKey(JsonNode node, String path) {
        String key = requireText(node, "code", path);
        if (!key.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw invalid(path + ".code", "invalid screening key");
        }
        return key;
    }

    private static String normalizeCode(String value, String path) {
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw invalid(path, "must be an uppercase code");
        }
        return normalized;
    }

    private static int requireBoundedInteger(
        JsonNode node,
        String field,
        String path,
        int minimum,
        int maximum
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(path + "." + field, "must be an integer");
        }
        int result = value.intValue();
        if (result < minimum || result > maximum) {
            throw invalid(path + "." + field, "must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private static InvalidTriagePredicateException invalid(String path, String message) {
        return new InvalidTriagePredicateException(path, message);
    }

    private sealed interface RuleNode permits GroupNode, SymptomNode, ScreeningNode, DurationNode { }

    private record CompiledRule(Set<String> species, RuleNode expression) { }

    private record GroupNode(GroupOperator operator, List<RuleNode> children) implements RuleNode { }

    private record SymptomNode(String code) implements RuleNode { }

    private record ScreeningNode(String code, Set<String> expected, String operator)
        implements RuleNode { }

    private record DurationNode(String code, NumericOperator operator, int value) implements RuleNode { }

    private record NodeResult(TruthValue truth, List<Evidence> evidence) { }

    private enum GroupOperator { ALL, ANY }

    private enum NumericOperator { GTE, LTE }
}
