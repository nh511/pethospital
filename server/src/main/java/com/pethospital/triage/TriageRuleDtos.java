package com.pethospital.triage;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public final class TriageRuleDtos {
    private TriageRuleDtos() {
    }

    public record RuleCreateRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,63}") String ruleCode,
        @NotBlank @Pattern(regexp = "CAT|DOG", flags = Pattern.Flag.CASE_INSENSITIVE) String speciesCode,
        @NotBlank @Size(max = 128) String title,
        @NotNull JsonNode predicate,
        @NotBlank @Pattern(regexp = "EMERGENCY|URGENT|ROUTINE|UNKNOWN", flags = Pattern.Flag.CASE_INSENSITIVE)
        String riskLevel,
        @NotBlank @Size(max = 10_000) String fixedAdvice,
        @NotBlank @Size(max = 1000) String sourceUrl
    ) {
    }

    public record RulePatchRequest(
        @Pattern(regexp = "CAT|DOG", flags = Pattern.Flag.CASE_INSENSITIVE) String speciesCode,
        @Size(min = 1, max = 128) String title,
        JsonNode predicate,
        @Pattern(regexp = "EMERGENCY|URGENT|ROUTINE|UNKNOWN", flags = Pattern.Flag.CASE_INSENSITIVE)
        String riskLevel,
        @Size(min = 1, max = 10_000) String fixedAdvice,
        @Size(min = 1, max = 1000) String sourceUrl,
        @NotNull Integer version
    ) {
    }

    public record RuleSubmitReviewRequest(@NotNull Integer version) {
    }

    public record RuleReviewRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT", flags = Pattern.Flag.CASE_INSENSITIVE) String decision,
        @Size(max = 500) String reviewNote,
        @NotNull Integer version
    ) {
    }

    public record RuleEnableRequest(@NotNull Integer version) {
    }

    public record RuleDisableRequest(
        @NotBlank @Size(max = 500) String reason,
        @NotNull Integer version
    ) {
    }

    public record RulePreviewRequest(
        @NotBlank @Pattern(regexp = "CAT|DOG", flags = Pattern.Flag.CASE_INSENSITIVE) String speciesCode,
        @NotBlank @Size(max = 4000) String content,
        @NotNull @Size(max = 50) Map<String, @NotBlank @Size(max = 64) String> screeningAnswers
    ) {
    }

    public record PreviewRuleResult(
        String ruleId,
        String ruleCode,
        int revisionNo,
        String riskLevel,
        String fixedAdvice,
        String outcome,
        String truth,
        boolean coverageLimited,
        List<TriageDtos.Evidence> evidence
    ) {
        public PreviewRuleResult {
            evidence = List.copyOf(evidence);
        }
    }

    public record RulePreviewView(
        String speciesCode,
        int evaluatedCount,
        int matchedCount,
        List<PreviewRuleResult> results
    ) {
        public RulePreviewView {
            results = List.copyOf(results);
        }
    }

    public record RuleView(
        String ruleId,
        String ruleCode,
        int revisionNo,
        String speciesCode,
        String title,
        JsonNode predicate,
        String riskLevel,
        String fixedAdvice,
        String sourceUrl,
        String reviewStatus,
        boolean enabled,
        String creatorUserId,
        String reviewerUserId,
        String reviewNote,
        String reviewedAt,
        int version,
        String createdAt,
        String updatedAt
    ) {
    }

    public record RulePage(List<RuleView> items, int page, int pageSize, long total) {
        public RulePage {
            items = List.copyOf(items);
        }
    }
}
