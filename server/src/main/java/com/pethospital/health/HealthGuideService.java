package com.pethospital.health;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.health.HealthGuideDtos.GuideCreateRequest;
import com.pethospital.health.HealthGuideDtos.GuidePage;
import com.pethospital.health.HealthGuideDtos.GuidePatchRequest;
import com.pethospital.health.HealthGuideDtos.GuidePublishRequest;
import com.pethospital.health.HealthGuideDtos.GuideOfflineRequest;
import com.pethospital.health.HealthGuideDtos.GuideView;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
import com.pethospital.shared.IdempotencyService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HealthGuideService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final GuideHtmlSanitizer html;
    private final FileBindingService fileBindings;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public HealthGuideService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        GuideHtmlSanitizer html,
        FileBindingService fileBindings,
        IdempotencyService idempotency,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.html = html;
        this.fileBindings = fileBindings;
        this.idempotency = idempotency;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public GuidePage list(
        int page,
        int pageSize,
        String status,
        String speciesCode,
        String categoryCode,
        String keyword
    ) {
        requireReader();
        List<Object> arguments = new ArrayList<>();
        String where = filters(status, speciesCode, categoryCode, keyword, arguments);
        JdbcTemplate jdbc = jdbc();
        long total = value(jdbc.queryForObject(
            "SELECT COUNT(*) FROM health_guide" + where,
            Long.class,
            arguments.toArray()
        ));
        arguments.add(pageSize);
        arguments.add(Math.multiplyExact((long) page - 1L, pageSize));
        List<GuideView> items = jdbc.query("""
            SELECT id,title,species_code,category_code,body,cover_file_id,source_note,status,
                   editor_user_id,version,published_at,created_at,updated_at
            FROM health_guide
            """ + where + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> view(result), arguments.toArray());
        return new GuidePage(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public GuideView get(long guideId) {
        requireReader();
        return find(jdbc(), guideId, false);
    }

    @Transactional
    public GuideView create(GuideCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "HEALTH_GUIDE_CREATE", "HEALTH_GUIDE",
            "POST", "/api/v1/staff/health/guides", operationKey, request, null, null, null
        );
        return idempotency.execute(command, GuideView.class, () -> createGuide(actor, request));
    }

    @Transactional
    public GuideView patch(long guideId, GuidePatchRequest request) {
        CurrentIdentity actor = requireAdmin();
        if (request.version() < 0) {
            throw badRequest("version不能为负数");
        }
        if (request.title() == null && request.speciesCode() == null && request.categoryCode() == null
            && request.body() == null && request.coverFileId() == null && request.sourceNote() == null) {
            throw badRequest("至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = jdbc();
        GuideRow current = row(jdbc, guideId, true);
        if (current.version() != request.version()) {
            throw versionConflict();
        }
        if ("PUBLISHED".equals(current.status())) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "PUBLISHED_GUIDE_IMMUTABLE",
                "已发布指南需先下架后再编辑"
            );
        }
        String title = request.title() == null ? current.title() : required(request.title(), "标题不能为空");
        String species = request.speciesCode() == null ? current.speciesCode() : code(request.speciesCode());
        String category = request.categoryCode() == null ? current.categoryCode() : code(request.categoryCode());
        String body = request.body() == null ? current.body() : sanitizedBody(request.body());
        String source = request.sourceNote() == null ? current.sourceNote() : required(request.sourceNote(), "来源不能为空");
        Long coverFileId = request.coverFileId() == null ? current.coverFileId() : request.coverFileId();
        int updated = jdbc.update("""
            UPDATE health_guide
            SET title=?,species_code=?,category_code=?,body=?,cover_file_id=?,source_note=?,
                editor_user_id=?,version=version+1
            WHERE id=? AND version=?
            """, title, species, category, body, coverFileId, source,
            actor.userId(), guideId, request.version());
        if (updated != 1) {
            throw versionConflict();
        }
        if (request.coverFileId() != null) {
            fileBindings.replaceMutable(FileBusinessType.GUIDE, guideId, List.of(coverFileId));
        }
        GuideView result = find(jdbc, guideId, false);
        audit.append(new AuditEvent(
            actor.userId(), ActorType.ADMIN, "HEALTH_GUIDE_UPDATE",
            new ResourceReference("HEALTH_GUIDE", guideId),
            objectMapper.valueToTree(snapshot(current)), objectMapper.valueToTree(snapshot(result)),
            null, null, null, AuditResult.SUCCESS, null
        ));
        return result;
    }

    @Transactional
    public GuideView publish(long guideId, GuidePublishRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "HEALTH_GUIDE_PUBLISH", "HEALTH_GUIDE",
            "POST", "/api/v1/staff/health/guides/" + guideId + "/publish",
            operationKey, request, null, null, null
        );
        return idempotency.execute(command, GuideView.class,
            () -> publishGuide(actor, guideId, request.version()));
    }

    @Transactional
    public GuideView offline(long guideId, GuideOfflineRequest request, String operationKey) {
        CurrentIdentity actor = requireAdmin();
        String reason = required(request.reason(), "下架原因不能为空");
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.ADMIN, "HEALTH_GUIDE_OFFLINE", "HEALTH_GUIDE",
            "POST", "/api/v1/staff/health/guides/" + guideId + "/offline",
            operationKey, request, null, reason, null
        );
        return idempotency.execute(command, GuideView.class,
            () -> offlineGuide(actor, guideId, request.version()));
    }

    private IdempotentResult<GuideView> publishGuide(
        CurrentIdentity actor,
        long guideId,
        int version
    ) {
        JdbcTemplate jdbc = jdbc();
        GuideRow current = row(jdbc, guideId, true);
        requireVersion(current, version);
        if (!"DRAFT".equals(current.status()) && !"OFFLINE".equals(current.status())) {
            throw stateConflict("只有草稿或已下架指南可以发布");
        }
        validatePublishRequirements(jdbc, current);
        int updated = jdbc.update("""
            UPDATE health_guide
            SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3),editor_user_id=?,version=version+1
            WHERE id=? AND version=?
            """, actor.userId(), guideId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        GuideView result = find(jdbc, guideId, false);
        return new IdempotentResult<>(result, guideId, objectMapper.valueToTree(snapshot(result)));
    }

    private IdempotentResult<GuideView> offlineGuide(
        CurrentIdentity actor,
        long guideId,
        int version
    ) {
        JdbcTemplate jdbc = jdbc();
        GuideRow current = row(jdbc, guideId, true);
        requireVersion(current, version);
        if (!"PUBLISHED".equals(current.status())) {
            throw stateConflict("只有已发布指南可以下架");
        }
        int updated = jdbc.update("""
            UPDATE health_guide
            SET status='OFFLINE',editor_user_id=?,version=version+1
            WHERE id=? AND version=?
            """, actor.userId(), guideId, version);
        if (updated != 1) {
            throw versionConflict();
        }
        GuideView result = find(jdbc, guideId, false);
        return new IdempotentResult<>(result, guideId, objectMapper.valueToTree(snapshot(result)));
    }

    private static void validatePublishRequirements(JdbcTemplate jdbc, GuideRow guide) {
        if (guide.title() == null || guide.title().isBlank()
            || guide.body() == null || guide.body().isBlank()
            || guide.sourceNote() == null || guide.sourceNote().isBlank()
            || guide.coverFileId() == null) {
            throw publishRequirements("发布前必须填写标题、正文、来源并绑定封面");
        }
        Boolean readyCover = jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM file_object f
                JOIN file_binding b ON b.file_id=f.id
                WHERE f.id=? AND f.status='READY' AND f.purpose='GUIDE'
                  AND b.business_type='GUIDE' AND b.business_id=?
            )
            """, Boolean.class, guide.coverFileId(), guide.id());
        if (!Boolean.TRUE.equals(readyCover)) {
            throw publishRequirements("封面必须是当前指南已绑定的READY/GUIDE文件");
        }
    }

    private static void requireVersion(GuideRow current, int version) {
        if (version < 0 || current.version() != version) {
            throw versionConflict();
        }
    }

    private IdempotentResult<GuideView> createGuide(CurrentIdentity actor, GuideCreateRequest request) {
        long guideId = IdWorker.getId();
        jdbc().update("""
            INSERT INTO health_guide(
                id,title,species_code,category_code,body,cover_file_id,source_note,status,
                published_at,editor_user_id,version
            ) VALUES (?,?,?,?,?,?,?,'DRAFT',NULL,?,0)
            """, guideId, required(request.title(), "标题不能为空"), code(request.speciesCode()),
            code(request.categoryCode()), sanitizedBody(request.body()), request.coverFileId(),
            required(request.sourceNote(), "来源不能为空"), actor.userId());
        if (request.coverFileId() != null) {
            fileBindings.replaceMutable(FileBusinessType.GUIDE, guideId, List.of(request.coverFileId()));
        }
        GuideView result = find(jdbc(), guideId, false);
        return new IdempotentResult<>(result, guideId, objectMapper.valueToTree(snapshot(result)));
    }

    private static String filters(
        String status,
        String speciesCode,
        String categoryCode,
        String keyword,
        List<Object> arguments
    ) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (status != null && !status.isBlank()) {
            String normalized = status.strip().toUpperCase(Locale.ROOT);
            if (!SetValues.GUIDE_STATUSES.contains(normalized)) {
                throw badRequest("status不合法");
            }
            where.append(" AND status=?");
            arguments.add(normalized);
        }
        if (speciesCode != null && !speciesCode.isBlank()) {
            where.append(" AND species_code=?");
            arguments.add(code(speciesCode));
        }
        if (categoryCode != null && !categoryCode.isBlank()) {
            where.append(" AND category_code=?");
            arguments.add(code(categoryCode));
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND title LIKE ? ESCAPE '\\\\'");
            arguments.add("%" + like(keyword.strip()) + "%");
        }
        return where.toString();
    }

    private CurrentIdentity requireReader() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("ADMIN") && !actor.roles().contains("DOCTOR"))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅医生或管理员可查看指南后台");
        }
        return actor;
    }

    private CurrentIdentity requireAdmin() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可编辑健康指南");
        }
        return actor;
    }

    private GuideView find(JdbcTemplate jdbc, long guideId, boolean lock) {
        return view(row(jdbc, guideId, lock));
    }

    private static GuideRow row(JdbcTemplate jdbc, long guideId, boolean lock) {
        GuideRow result = jdbc.query("""
            SELECT id,title,species_code,category_code,body,cover_file_id,source_note,status,
                   editor_user_id,version,published_at,created_at,updated_at
            FROM health_guide WHERE id=?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? row(rs) : null, guideId);
        if (result == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "HEALTH_GUIDE_NOT_FOUND", "健康指南不存在");
        }
        return result;
    }

    private static GuideRow row(ResultSet result) throws SQLException {
        return new GuideRow(
            result.getLong("id"), result.getString("title"), result.getString("species_code"),
            result.getString("category_code"), result.getString("body"),
            result.getObject("cover_file_id", Long.class), result.getString("source_note"),
            result.getString("status"), result.getLong("editor_user_id"), result.getInt("version"),
            result.getTimestamp("published_at"), result.getTimestamp("created_at"), result.getTimestamp("updated_at")
        );
    }

    private static GuideView view(ResultSet result) throws SQLException {
        return view(row(result));
    }

    private static GuideView view(GuideRow row) {
        return new GuideView(
            Long.toString(row.id()), row.title(), row.speciesCode(), row.categoryCode(), row.body(),
            row.coverFileId() == null ? null : Long.toString(row.coverFileId()), row.sourceNote(),
            row.status(), Long.toString(row.editorUserId()), row.version(), instant(row.publishedAt()),
            instant(row.createdAt()), instant(row.updatedAt())
        );
    }

    private static Map<String, Object> snapshot(GuideRow row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", row.title());
        result.put("speciesCode", row.speciesCode());
        result.put("categoryCode", row.categoryCode());
        result.put("coverFileId", row.coverFileId());
        result.put("sourceNote", row.sourceNote());
        result.put("status", row.status());
        result.put("version", row.version());
        return result;
    }

    private static Map<String, Object> snapshot(GuideView view) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", view.title());
        result.put("speciesCode", view.speciesCode());
        result.put("categoryCode", view.categoryCode());
        result.put("coverFileId", view.coverFileId());
        result.put("sourceNote", view.sourceNote());
        result.put("status", view.status());
        result.put("version", view.version());
        return result;
    }

    private String sanitizedBody(String body) {
        String result = html.sanitize(required(body, "正文不能为空"));
        if (result.isBlank()) {
            throw badRequest("正文清洗后不能为空");
        }
        return result;
    }

    private static String required(String value, String message) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw badRequest(message);
        }
        return normalized;
    }

    private static String code(String value) {
        return required(value, "代码不能为空").toUpperCase(Locale.ROOT);
    }

    private static String like(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(dataSource);
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_HEALTH_GUIDE", message);
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "指南已被其他操作修改");
    }

    private static BusinessException stateConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "HEALTH_GUIDE_STATE_CONFLICT", message);
    }

    private static BusinessException publishRequirements(String message) {
        return new BusinessException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "HEALTH_GUIDE_PUBLISH_REQUIREMENTS_NOT_MET",
            message
        );
    }

    private record GuideRow(
        long id,
        String title,
        String speciesCode,
        String categoryCode,
        String body,
        Long coverFileId,
        String sourceNote,
        String status,
        long editorUserId,
        int version,
        Timestamp publishedAt,
        Timestamp createdAt,
        Timestamp updatedAt
    ) {
    }

    private static final class SetValues {
        private static final java.util.Set<String> GUIDE_STATUSES = java.util.Set.of(
            "DRAFT", "PUBLISHED", "OFFLINE"
        );

        private SetValues() {
        }
    }
}
