package com.pethospital.triage;

import com.pethospital.triage.TriageRuleDtos.PreviewRuleResult;
import com.pethospital.triage.TriageRuleDtos.RulePreviewRequest;
import com.pethospital.triage.TriageServiceDtos.HospitalContact;
import com.pethospital.triage.TriageServiceDtos.MatchedRule;
import com.pethospital.triage.TriageServiceDtos.RiskDecision;
import com.pethospital.triage.TriageServiceDtos.TriageRequest;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriageService {
    public static final String DISCLAIMER = "本分诊提示仅用于紧急风险初筛，不能替代执业兽医的线下诊断与治疗。";
    public static final String NO_MATCH_WARNING =
        "当前信息未触发已启用规则的明确急症提示，但这不代表已排除急症；如症状严重、持续或恶化，请立即联系宠物医院。";
    private static final Map<String, Integer> RISK_PRIORITY = Map.of(
        "EMERGENCY", 0,
        "URGENT", 1,
        "ROUTINE", 2,
        "UNKNOWN", 3
    );

    private final TriageRuleRuntimeService runtime;
    private final HospitalContact hospitalContact;

    public TriageService(
        TriageRuleRuntimeService runtime,
        @Value("${hospital.name}") String hospitalName,
        @Value("${hospital.address}") String hospitalAddress,
        @Value("${hospital.phone}") String hospitalPhone,
        @Value("${hospital.business-hours}") String hospitalBusinessHours
    ) {
        this.runtime = runtime;
        this.hospitalContact = new HospitalContact(
            hospitalName, hospitalAddress, hospitalPhone, hospitalBusinessHours
        );
    }

    @Transactional(readOnly = true)
    public RiskDecision triage(TriageRequest request) {
        var preview = runtime.evaluateEnabledRules(new RulePreviewRequest(
            request.speciesCode(), request.userMessage(), request.screeningAnswers()
        ));
        List<PreviewRuleResult> matchedResults = preview.results().stream()
            .filter(result -> "MATCHED".equals(result.outcome()))
            .sorted(Comparator
                .comparingInt((PreviewRuleResult result) -> riskPriority(result.riskLevel()))
                .thenComparing(PreviewRuleResult::ruleCode)
                .thenComparing(PreviewRuleResult::revisionNo, Comparator.reverseOrder()))
            .toList();
        List<MatchedRule> matchedRules = matchedResults.stream()
            .map(TriageService::toMatchedRule)
            .toList();
        int unknownCount = (int) preview.results().stream()
            .filter(result -> "UNKNOWN".equals(result.outcome()))
            .count();
        boolean coverageLimited = preview.results().stream()
            .anyMatch(PreviewRuleResult::coverageLimited);
        List<String> symptomCodes = symptomCodes(preview.results());

        if (matchedResults.isEmpty()) {
            return new RiskDecision(
                "RULE", "UNKNOWN", false, false, false, true, coverageLimited,
                NO_MATCH_WARNING, "", hospitalContact, DISCLAIMER,
                preview.evaluatedCount(), unknownCount, symptomCodes, List.of()
            );
        }

        PreviewRuleResult highest = matchedResults.getFirst();
        boolean emergency = "EMERGENCY".equals(highest.riskLevel());
        String advice = emergency ? joinEmergencyAdvice(matchedResults) : highest.fixedAdvice();
        String visibleContent = emergency
            ? emergencyContent(advice)
            : "已识别到需要关注的风险，请继续补充问诊信息，并尽快联系宠物医院。";
        return new RiskDecision(
            "RULE", highest.riskLevel(), emergency, emergency, emergency, !emergency,
            coverageLimited, visibleContent, advice, hospitalContact, DISCLAIMER,
            preview.evaluatedCount(), unknownCount, symptomCodes, matchedRules
        );
    }

    private String emergencyContent(String advice) {
        var content = new StringBuilder(advice);
        if (!hospitalContact.name().isBlank()) {
            content.append("\n医院：").append(hospitalContact.name());
        }
        if (!hospitalContact.address().isBlank()) {
            content.append("\n地址：").append(hospitalContact.address());
        }
        if (!hospitalContact.phone().isBlank()) {
            content.append("\n电话：").append(hospitalContact.phone());
        }
        if (!hospitalContact.businessHours().isBlank()) {
            content.append("\n营业时间：").append(hospitalContact.businessHours());
        }
        return content.toString();
    }

    private static String joinEmergencyAdvice(List<PreviewRuleResult> matchedResults) {
        return matchedResults.stream()
            .filter(result -> "EMERGENCY".equals(result.riskLevel()))
            .map(PreviewRuleResult::fixedAdvice)
            .distinct()
            .reduce((left, right) -> left + "\n" + right)
            .orElseThrow();
    }

    private static MatchedRule toMatchedRule(PreviewRuleResult result) {
        return new MatchedRule(
            result.ruleId(), result.ruleCode(), result.revisionNo(), result.riskLevel(),
            result.fixedAdvice(), result.evidence()
        );
    }

    private static List<String> symptomCodes(List<PreviewRuleResult> results) {
        Set<String> codes = new LinkedHashSet<>();
        results.stream()
            .flatMap(result -> result.evidence().stream())
            .filter(evidence -> ("SYMPTOM".equals(evidence.fact())
                || "DURATION_MINUTES".equals(evidence.fact()))
                && !"MISSING".equals(evidence.observedState()))
            .map(TriageDtos.Evidence::code)
            .forEach(codes::add);
        return List.copyOf(codes);
    }

    private static int riskPriority(String riskLevel) {
        return RISK_PRIORITY.getOrDefault(riskLevel, Integer.MAX_VALUE);
    }
}
