package com.pethospital.triage;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.IdempotencyService;
import com.pethospital.triage.TriageRuleDtos.RuleCreateRequest;
import com.pethospital.triage.TriageRuleDtos.RuleDisableRequest;
import com.pethospital.triage.TriageRuleDtos.RuleEnableRequest;
import com.pethospital.triage.TriageRuleDtos.RulePage;
import com.pethospital.triage.TriageRuleDtos.RulePatchRequest;
import com.pethospital.triage.TriageRuleDtos.RuleReviewRequest;
import com.pethospital.triage.TriageRuleDtos.RuleSubmitReviewRequest;
import com.pethospital.triage.TriageRuleDtos.RuleView;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TriageRuleService {
    private static final String SELECT_COLUMNS = """
        id,rule_code,revision_no,species_code,title,CAST(predicate_json AS CHAR) AS predicate_json,
        risk_level,fixed_advice,source_url,review_status,enabled,creator_user_id,reviewer_user_id,
        review_note,reviewed_at,version,created_at,updated_at
        """;

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final TriageEvaluator evaluator;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public TriageRuleService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        TriageEvaluator evaluator,
        IdempotencyService idempotency,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.evaluator = evaluator;
        this.idempotency = idempotency;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RulePage list(
        int page,
        int pageSize,
        String ruleCode,
        String speciesCode,
        String reviewStatus
    ) {
        requireReader();
        List<Object> arguments = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (ruleCode != null && !ruleCode.isBlank()) {
            where.append(" AND rule_code=?");
            arguments.add(code(ruleCode));
        }
        if (speciesCode != null && !speciesCode.isBlank()) {
            where.append(" AND species_code=?");
            arguments.add(species(speciesCode));
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            String normalized = reviewStatus.strip().toUpperCase(Locale.ROOT);
            if (!List.of("DRAFT", "IN_REVIEW", "APPROVED", "REJECTED").contains(normalized)) {
                throw badRequest("reviewStatus不合法");
            }
            where.append(" AND review_status=?");
            arguments.add(normalized);
        }
        JdbcTemplate jdbc = jdbc();
        long total = value(jdbc.queryForObject(
            "SELECT COUNT(*) FROM triage_rule" + where, Long.class, arguments.toArray()));
        arguments.add(pageSize);
        arguments.add(Math.multiplyExact((long) page - 1L, pageSize));
        List<RuleView> items = jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM triage_rule" + where
                + " ORDER BY rule_code ASC,revision_no DESC,id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> view(result), arguments.toArray());
        return new RulePage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public RuleView get(long ruleId) {
        requireReader();
        return find(jdbc(), ruleId, false);
    }

    @Transactional
    public RuleView create(RuleCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        validatePredicate(request.predicate(), request.speciesCode());
        sourceUrl(request.sourceUrl());
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "TRIAGE_RULE_REVISION_CREATE", "TRIAGE_RULE",
            "POST", "/api/v1/staff/triage-rules", operationKey, request, null, null, null
        );
        return idempotency.execute(command, RuleView.class, () -> createRevision(actor, request));
    }

    @Transactional
    public RuleView patch(long ruleId, RulePatchRequest request) {
        CurrentIdentity actor = requireAdmin();
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        if (request.speciesCode() == null && request.title() == null && request.predicate() == null
            && request.riskLevel() == null && request.fixedAdvice() == null && request.sourceUrl() == null) {
            throw badRequest("至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = jdbc();
        RuleRow current = row(jdbc, ruleId, true);
        if (current.version() != request.version()) {
            throw versionConflict();
        }
        if (!"DRAFT".equals(current.reviewStatus()) && !"REJECTED".equals(current.reviewStatus())) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "TRIAGE_RULE_IMMUTABLE",
                "审核中或已批准规则不能原地修改"
            );
        }
        String nextSpecies = request.speciesCode() == null ? current.speciesCode() : species(request.speciesCode());
        JsonNode nextPredicate = request.predicate() == null ? current.predicate() : request.predicate();
        validatePredicate(nextPredicate, nextSpecies);
        String nextSource = request.sourceUrl() == null ? current.sourceUrl() : sourceUrl(request.sourceUrl());
        String nextRisk = request.riskLevel() == null ? current.riskLevel() : risk(request.riskLevel());
        String nextTitle = request.title() == null ? current.title() : required(request.title(), "标题不能为空");
        String nextAdvice = request.fixedAdvice() == null
            ? current.fixedAdvice() : required(request.fixedAdvice(), "固定建议不能为空");
        int updated = jdbc.update("""
            UPDATE triage_rule
            SET species_code=?,title=?,predicate_json=CAST(? AS JSON),risk_level=?,fixed_advice=?,
                source_url=?,review_status='DRAFT',reviewer_user_id=NULL,review_note=NULL,
                reviewed_at=NULL,enabled=0,version=version+1
            WHERE id=? AND version=?
            """, nextSpecies, nextTitle, json(nextPredicate), nextRisk, nextAdvice,
            nextSource, ruleId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        RuleView result = find(jdbc, ruleId, false);
        audit.append(new AuditEvent(
            actor.userId(), ActorType.ADMIN, "TRIAGE_RULE_UPDATE",
            new ResourceReference("TRIAGE_RULE", ruleId),
            objectMapper.valueToTree(snapshot(current)), objectMapper.valueToTree(snapshot(result)),
            null, null, null, AuditResult.SUCCESS, null
        ));
        return result;
    }

    @Transactional
    public RuleView submitReview(long ruleId, RuleSubmitReviewRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        requireNonNegativeVersion(request.version());
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "TRIAGE_RULE_SUBMIT_REVIEW", "TRIAGE_RULE",
            "POST", "/api/v1/staff/triage-rules/" + ruleId + "/submit-review",
            operationKey, request, null, null, null
        );
        return idempotency.execute(command, RuleView.class, () -> submitReview(ruleId, request.version()));
    }

    private IdempotentResult<RuleView> submitReview(long ruleId, int version) {
        JdbcTemplate jdbc = jdbc();
        RuleRow current = row(jdbc, ruleId, true);
        if (current.version() != version) {
            throw versionConflict();
        }
        if (!"DRAFT".equals(current.reviewStatus())) {
            throw reviewStateConflict("只有草稿规则可以提交审核");
        }
        validateStoredRule(current);
        int updated = jdbc.update("""
            UPDATE triage_rule
            SET review_status='IN_REVIEW',reviewer_user_id=NULL,review_note=NULL,
                reviewed_at=NULL,enabled=0,version=version+1
            WHERE id=? AND version=? AND review_status='DRAFT'
            """, ruleId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        RuleView result = find(jdbc, ruleId, false);
        return new IdempotentResult<>(result, ruleId, objectMapper.valueToTree(snapshot(result)));
    }

    @Transactional
    public RuleView review(long ruleId, RuleReviewRequest request, String operationKey) {
        CurrentIdentity actor = requireDoctorReviewer();
        requireNonNegativeVersion(request.version());
        String decision = request.decision().strip().toUpperCase(Locale.ROOT);
        String note = nullable(request.reviewNote());
        if ("REJECT".equals(decision) && note == null) {
            throw badRequest("驳回审核必须填写reviewNote");
        }
        JdbcTemplate jdbc = jdbc();
        ensureActiveDoctor(jdbc, actor.userId());
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.DOCTOR, "TRIAGE_RULE_REVIEW", "TRIAGE_RULE",
            "POST", "/api/v1/staff/triage-rules/" + ruleId + "/review",
            operationKey, request, null, note, null
        );
        return idempotency.execute(command, RuleView.class,
            () -> review(jdbc, ruleId, request.version(), decision, note, actor.userId()));
    }

    private IdempotentResult<RuleView> review(
        JdbcTemplate jdbc,
        long ruleId,
        int version,
        String decision,
        String note,
        long reviewerUserId
    ) {
        RuleRow current = row(jdbc, ruleId, true);
        if (current.version() != version) {
            throw versionConflict();
        }
        if (!"IN_REVIEW".equals(current.reviewStatus())) {
            throw reviewStateConflict("只有审核中的规则可以执行审核");
        }
        String nextStatus = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        int updated = jdbc.update("""
            UPDATE triage_rule
            SET review_status=?,reviewer_user_id=?,review_note=?,reviewed_at=CURRENT_TIMESTAMP(3),
                enabled=0,version=version+1
            WHERE id=? AND version=? AND review_status='IN_REVIEW'
            """, nextStatus, reviewerUserId, note, ruleId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        RuleView result = find(jdbc, ruleId, false);
        return new IdempotentResult<>(result, ruleId, objectMapper.valueToTree(snapshot(result)));
    }

    @Transactional
    public RuleView enable(long ruleId, RuleEnableRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        requireNonNegativeVersion(request.version());
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "TRIAGE_RULE_ENABLE", "TRIAGE_RULE",
            "POST", "/api/v1/staff/triage-rules/" + ruleId + "/enable",
            operationKey, request, null, null, null
        );
        return idempotency.execute(command, RuleView.class,
            () -> enableRule(ruleId, request.version(), actor.userId()));
    }

    @Transactional
    public RuleView disable(long ruleId, RuleDisableRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        requireNonNegativeVersion(request.version());
        String reason = required(request.reason(), "停用原因不能为空");
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "TRIAGE_RULE_DISABLE", "TRIAGE_RULE",
            "POST", "/api/v1/staff/triage-rules/" + ruleId + "/disable",
            operationKey, request, null, reason, null
        );
        return idempotency.execute(command, RuleView.class,
            () -> disableRule(ruleId, request.version()));
    }

    private IdempotentResult<RuleView> enableRule(long ruleId, int version, long actorUserId) {
        JdbcTemplate jdbc = jdbc();
        RuleRow preliminary = row(jdbc, ruleId, false);
        List<RuleRow> revisions = jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM triage_rule WHERE rule_code=? ORDER BY id FOR UPDATE",
            (result, rowNumber) -> row(result), preliminary.ruleCode());
        RuleRow current = revisions.stream().filter(item -> item.id() == ruleId).findFirst()
            .orElseThrow(() -> new BusinessException(
                HttpStatus.NOT_FOUND, "TRIAGE_RULE_NOT_FOUND", "急症规则不存在"));
        if (current.version() != version) {
            throw versionConflict();
        }
        if (!"APPROVED".equals(current.reviewStatus())) {
            throw new BusinessException(
                HttpStatus.CONFLICT, "TRIAGE_RULE_NOT_APPROVED", "只有已批准规则可以启用");
        }
        if (current.enabled()) {
            throw enabledStateConflict("规则已经启用");
        }
        for (RuleRow previous : revisions) {
            if (previous.id() == ruleId || !previous.enabled()) {
                continue;
            }
            int disabled = jdbc.update("""
                UPDATE triage_rule SET enabled=0,version=version+1 WHERE id=? AND version=? AND enabled=1
                """, previous.id(), previous.version());
            if (disabled != 1) {
                throw versionConflict();
            }
            RuleView after = find(jdbc, previous.id(), false);
            audit.append(new AuditEvent(
                actorUserId, ActorType.ADMIN, "TRIAGE_RULE_AUTO_DISABLE",
                new ResourceReference("TRIAGE_RULE", previous.id()),
                objectMapper.valueToTree(snapshot(previous)), objectMapper.valueToTree(snapshot(after)),
                "启用同code新修订", null, null, AuditResult.SUCCESS, null
            ));
        }
        int enabled = jdbc.update("""
            UPDATE triage_rule SET enabled=1,version=version+1
            WHERE id=? AND version=? AND review_status='APPROVED' AND enabled=0
            """, ruleId, version);
        if (enabled != 1) {
            throw versionConflict();
        }
        RuleView result = find(jdbc, ruleId, false);
        return new IdempotentResult<>(result, ruleId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<RuleView> disableRule(long ruleId, int version) {
        JdbcTemplate jdbc = jdbc();
        RuleRow current = row(jdbc, ruleId, true);
        if (current.version() != version) {
            throw versionConflict();
        }
        if (!current.enabled()) {
            throw enabledStateConflict("只有已启用规则可以停用");
        }
        int disabled = jdbc.update("""
            UPDATE triage_rule SET enabled=0,version=version+1
            WHERE id=? AND version=? AND enabled=1
            """, ruleId, version);
        if (disabled != 1) {
            throw versionConflict();
        }
        RuleView result = find(jdbc, ruleId, false);
        return new IdempotentResult<>(result, ruleId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<RuleView> createRevision(CurrentIdentity actor, RuleCreateRequest request) {
        JdbcTemplate jdbc = jdbc();
        String ruleCode = code(request.ruleCode());
        Integer current = jdbc.queryForObject(
            "SELECT COALESCE(MAX(revision_no),0) FROM triage_rule WHERE rule_code=?",
            Integer.class,
            ruleCode
        );
        int revision = Math.addExact(current == null ? 0 : current, 1);
        long ruleId = IdWorker.getId();
        try {
            jdbc.update("""
                INSERT INTO triage_rule(
                    id,rule_code,revision_no,species_code,title,predicate_json,risk_level,
                    fixed_advice,source_url,review_status,enabled,creator_user_id,version
                ) VALUES (?,?,?,?,?,CAST(? AS JSON),?,?,?,'DRAFT',0,?,0)
                """, ruleId, ruleCode, revision, species(request.speciesCode()),
                required(request.title(), "标题不能为空"), json(request.predicate()), risk(request.riskLevel()),
                required(request.fixedAdvice(), "固定建议不能为空"), sourceUrl(request.sourceUrl()), actor.userId());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "TRIAGE_REVISION_CONFLICT",
                "同一规则的新修订正在创建，请刷新后重试"
            );
        }
        RuleView result = find(jdbc, ruleId, false);
        return new IdempotentResult<>(result, ruleId, objectMapper.valueToTree(snapshot(result)));
    }

    private void validatePredicate(JsonNode predicate, String speciesCode) {
        try {
            evaluator.validate(predicate);
        } catch (InvalidTriagePredicateException exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRIAGE_PREDICATE", exception.getMessage());
        }
        String normalized = species(speciesCode);
        boolean present = StreamSupport.stream(
                predicate.path("applicableSpecies").spliterator(), false)
            .map(JsonNode::asText)
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(normalized::equals);
        if (!present) {
            throw badRequest("speciesCode必须包含在predicate.applicableSpecies中");
        }
    }

    private void validateStoredRule(RuleRow row) {
        validatePredicate(row.predicate(), row.speciesCode());
        required(row.title(), "标题不能为空");
        risk(row.riskLevel());
        required(row.fixedAdvice(), "固定建议不能为空");
        sourceUrl(row.sourceUrl());
    }

    private CurrentIdentity requireReader() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("ADMIN") && !actor.roles().contains("DOCTOR"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生或管理员可查看急症规则");
        }
        return actor;
    }

    private CurrentIdentity requireAdmin() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可编辑急症规则");
        }
        return actor;
    }

    private CurrentIdentity requireDoctorReviewer() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("DOCTOR")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生可审核急症规则");
        }
        return actor;
    }

    private static void ensureActiveDoctor(JdbcTemplate jdbc, long userId) {
        Long doctorId = jdbc.query("""
            SELECT id FROM doctor_profile WHERE user_id=? AND status='ACTIVE'
            """, result -> result.next() ? result.getLong("id") : null, userId);
        if (doctorId == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "DOCTOR_PROFILE_INACTIVE", "医生资料无效或已停用");
        }
    }

    private RuleView find(JdbcTemplate jdbc, long ruleId, boolean lock) {
        return view(row(jdbc, ruleId, lock));
    }

    private RuleRow row(JdbcTemplate jdbc, long ruleId, boolean lock) {
        RuleRow result = jdbc.query(
            "SELECT " + SELECT_COLUMNS + " FROM triage_rule WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? row(rs) : null,
            ruleId
        );
        if (result == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TRIAGE_RULE_NOT_FOUND", "急症规则不存在");
        }
        return result;
    }

    private RuleRow row(ResultSet result) throws SQLException {
        return new RuleRow(
            result.getLong("id"), result.getString("rule_code"), result.getInt("revision_no"),
            result.getString("species_code"), result.getString("title"), parse(result.getString("predicate_json")),
            result.getString("risk_level"), result.getString("fixed_advice"), result.getString("source_url"),
            result.getString("review_status"), result.getBoolean("enabled"), result.getLong("creator_user_id"),
            result.getObject("reviewer_user_id", Long.class), result.getString("review_note"),
            result.getTimestamp("reviewed_at"), result.getInt("version"),
            result.getTimestamp("created_at"), result.getTimestamp("updated_at")
        );
    }

    private RuleView view(ResultSet result) throws SQLException {
        return view(row(result));
    }

    private static RuleView view(RuleRow row) {
        return new RuleView(
            Long.toString(row.id()), row.ruleCode(), row.revisionNo(), row.speciesCode(), row.title(),
            row.predicate(), row.riskLevel(), row.fixedAdvice(), row.sourceUrl(), row.reviewStatus(),
            row.enabled(), Long.toString(row.creatorUserId()),
            row.reviewerUserId() == null ? null : Long.toString(row.reviewerUserId()), row.reviewNote(),
            instant(row.reviewedAt()), row.version(), instant(row.createdAt()), instant(row.updatedAt())
        );
    }

    private static Map<String, Object> snapshot(RuleRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", row.ruleCode());
        result.put("revisionNo", row.revisionNo());
        result.put("speciesCode", row.speciesCode());
        result.put("title", row.title());
        result.put("riskLevel", row.riskLevel());
        result.put("sourceUrl", row.sourceUrl());
        result.put("reviewStatus", row.reviewStatus());
        result.put("enabled", row.enabled());
        result.put("version", row.version());
        return result;
    }

    private static Map<String, Object> snapshot(RuleView view) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleCode", view.ruleCode());
        result.put("revisionNo", view.revisionNo());
        result.put("speciesCode", view.speciesCode());
        result.put("title", view.title());
        result.put("riskLevel", view.riskLevel());
        result.put("sourceUrl", view.sourceUrl());
        result.put("reviewStatus", view.reviewStatus());
        result.put("enabled", view.enabled());
        result.put("version", view.version());
        return result;
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored triage predicate is invalid JSON", exception);
        }
    }

    private String json(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw badRequest("predicate不是合法JSON");
        }
    }

    private static String sourceUrl(String value) {
        String normalized = required(value, "来源URL不能为空");
        try {
            URI uri = URI.create(normalized);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw badRequest("来源URL必须是带主机的HTTPS地址");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw badRequest("来源URL不合法");
        }
    }

    private static String code(String value) {
        return required(value, "ruleCode不能为空").toUpperCase(Locale.ROOT);
    }

    private static String species(String value) {
        String normalized = required(value, "speciesCode不能为空").toUpperCase(Locale.ROOT);
        if (!List.of("CAT", "DOG").contains(normalized)) {
            throw badRequest("speciesCode仅支持CAT或DOG");
        }
        return normalized;
    }

    private static String risk(String value) {
        String normalized = required(value, "riskLevel不能为空").toUpperCase(Locale.ROOT);
        if (!List.of("EMERGENCY", "URGENT", "ROUTINE", "UNKNOWN").contains(normalized)) {
            throw badRequest("riskLevel不合法");
        }
        return normalized;
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw badRequest(message);
        }
        return normalized;
    }

    private static String nullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    private static void requireNonNegativeVersion(Integer version) {
        if (version == null || version < 0) {
            throw badRequest("version不能为负数");
        }
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private JdbcTemplate jdbc() {
        DataSource source = dataSourceProvider.getIfAvailable();
        if (source == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(source);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRIAGE_RULE", message);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "规则已被其他操作修改");
    }

    private static BusinessException reviewStateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TRIAGE_REVIEW_STATE_CONFLICT", message);
    }

    private static BusinessException enabledStateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TRIAGE_RULE_ENABLED_STATE_CONFLICT", message);
    }

    private record RuleRow(
        long id, String ruleCode, int revisionNo, String speciesCode, String title,
        JsonNode predicate, String riskLevel, String fixedAdvice, String sourceUrl,
        String reviewStatus, boolean enabled, long creatorUserId, Long reviewerUserId,
        String reviewNote, Timestamp reviewedAt, int version, Timestamp createdAt, Timestamp updatedAt
    ) {
    }
}
