package com.pethospital.triage;

import com.pethospital.triage.TriageDtos.Evidence;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class TriageServiceDtos {
    private TriageServiceDtos() {
    }

    public record TriageRequest(
        String speciesCode,
        String userMessage,
        Map<String, String> screeningAnswers
    ) {
        public TriageRequest {
            speciesCode = required(speciesCode, "speciesCode").toUpperCase(Locale.ROOT);
            if (!"CAT".equals(speciesCode) && !"DOG".equals(speciesCode)) {
                throw new IllegalArgumentException("speciesCode must be CAT or DOG");
            }
            userMessage = required(userMessage, "userMessage");
            if (userMessage.length() > 4000) {
                throw new IllegalArgumentException("userMessage must not exceed 4000 characters");
            }
            if (screeningAnswers == null) {
                screeningAnswers = Map.of();
            } else {
                if (screeningAnswers.size() > 50) {
                    throw new IllegalArgumentException("screeningAnswers must not exceed 50 entries");
                }
                var copy = new LinkedHashMap<String, String>();
                screeningAnswers.forEach((key, value) -> {
                    String normalizedKey = required(key, "screening answer key");
                    String normalizedValue = required(value, "screening answer value");
                    if (normalizedKey.length() > 64 || normalizedValue.length() > 64) {
                        throw new IllegalArgumentException("screening answer key/value must not exceed 64 characters");
                    }
                    copy.put(normalizedKey, normalizedValue);
                });
                screeningAnswers = Map.copyOf(copy);
            }
        }
    }

    public record HospitalContact(
        String name,
        String address,
        String phone,
        String businessHours
    ) {
        public HospitalContact {
            name = optional(name);
            address = optional(address);
            phone = optional(phone);
            businessHours = optional(businessHours);
        }
    }

    public record MatchedRule(
        String ruleId,
        String ruleCode,
        int revisionNo,
        String riskLevel,
        String fixedAdvice,
        List<Evidence> evidence
    ) {
        public MatchedRule {
            ruleId = required(ruleId, "ruleId");
            ruleCode = required(ruleCode, "ruleCode");
            riskLevel = required(riskLevel, "riskLevel");
            fixedAdvice = required(fixedAdvice, "fixedAdvice");
            evidence = List.copyOf(evidence);
        }
    }

    public record RiskDecision(
        String source,
        String riskLevel,
        boolean emergency,
        boolean shortCircuitExternalServices,
        boolean skipRemainingQuestionnaire,
        boolean continueWithAi,
        boolean coverageLimited,
        String visibleContent,
        String fixedAdvice,
        HospitalContact hospitalContact,
        String disclaimer,
        int evaluatedRuleCount,
        int unknownRuleCount,
        List<String> symptomCodes,
        List<MatchedRule> matchedRules
    ) {
        public RiskDecision {
            source = required(source, "source");
            riskLevel = required(riskLevel, "riskLevel");
            visibleContent = required(visibleContent, "visibleContent");
            fixedAdvice = optional(fixedAdvice);
            hospitalContact = Objects.requireNonNull(hospitalContact, "hospitalContact");
            disclaimer = required(disclaimer, "disclaimer");
            symptomCodes = List.copyOf(symptomCodes);
            matchedRules = List.copyOf(matchedRules);
        }
    }

    private static String required(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.strip();
    }
}
