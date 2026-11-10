package com.pethospital.triage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.BusinessException;
import com.pethospital.triage.TriageRuleDtos.PreviewRuleResult;
import com.pethospital.triage.TriageRuleDtos.RulePreviewRequest;
import com.pethospital.triage.TriageRuleDtos.RulePreviewView;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriageRuleRuntimeService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final TriageEvaluator evaluator;
    private final TriageTextInterpreter interpreter;
    private final ObjectMapper objectMapper;

    public TriageRuleRuntimeService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        TriageEvaluator evaluator,
        TriageTextInterpreter interpreter,
        ObjectMapper objectMapper
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.evaluator = evaluator;
        this.interpreter = interpreter;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RulePreviewView preview(RulePreviewRequest request) {
        requireReader();
        return evaluateEnabledRules(request);
    }

    public RulePreviewView evaluateEnabledRules(RulePreviewRequest request) {
        String species = request.speciesCode().strip().toUpperCase(Locale.ROOT);
        List<RuntimeRule> rules = jdbc().query("""
            SELECT id,rule_code,revision_no,risk_level,fixed_advice,
                   CAST(predicate_json AS CHAR) AS predicate_json
            FROM triage_rule
            WHERE enabled=1 AND review_status='APPROVED' AND species_code=?
            ORDER BY rule_code ASC,revision_no DESC,id DESC
            """, (result, rowNumber) -> new RuntimeRule(
                result.getLong("id"), result.getString("rule_code"), result.getInt("revision_no"),
                result.getString("risk_level"), result.getString("fixed_advice"),
                parse(result.getString("predicate_json"))
            ), species);
        List<PreviewRuleResult> results = rules.stream().map(rule -> {
            var input = interpreter.interpret(
                rule.predicate(), species, request.content(), request.screeningAnswers());
            var evaluation = evaluator.evaluate(rule.predicate(), input);
            return new PreviewRuleResult(
                Long.toString(rule.id()), rule.ruleCode(), rule.revisionNo(), rule.riskLevel(),
                rule.fixedAdvice(), evaluation.outcome().name(), evaluation.truth().name(),
                evaluation.coverageLimited(), evaluation.evidence()
            );
        }).toList();
        int matched = (int) results.stream().filter(result -> "MATCHED".equals(result.outcome())).count();
        return new RulePreviewView(species, results.size(), matched, results);
    }

    private CurrentIdentity requireReader() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("ADMIN") && !actor.roles().contains("DOCTOR"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生或管理员可预览急症规则");
        }
        return actor;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored triage predicate is invalid JSON", exception);
        }
    }

    private JdbcTemplate jdbc() {
        DataSource source = dataSourceProvider.getIfAvailable();
        if (source == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(source);
    }

    private record RuntimeRule(
        long id,
        String ruleCode,
        int revisionNo,
        String riskLevel,
        String fixedAdvice,
        JsonNode predicate
    ) {
    }
}
