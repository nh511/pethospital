package com.pethospital.triage;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class TriageRiskPolicy {
    private static final Map<String, Integer> PRIORITY = Map.of(
        "EMERGENCY", 0,
        "URGENT", 1,
        "ROUTINE", 2,
        "UNKNOWN", 3
    );

    public String highest(String ruleRisk, String modelRisk) {
        String rule = normalize(ruleRisk, "ruleRisk");
        String model = normalize(modelRisk, "modelRisk");
        return PRIORITY.get(rule) <= PRIORITY.get(model) ? rule : model;
    }

    private static String normalize(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip().toUpperCase(Locale.ROOT);
        if (!PRIORITY.containsKey(normalized)) {
            throw new IllegalArgumentException(name + " must be EMERGENCY, URGENT, ROUTINE or UNKNOWN");
        }
        return normalized;
    }
}
