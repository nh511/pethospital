package com.pethospital.knowledge;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.doctor.DoctorSlotService;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.knowledge.KnowledgeDtos.CreateDocument;
import com.pethospital.knowledge.KnowledgeDtos.CreateVersion;
import com.pethospital.knowledge.KnowledgeDtos.CreatedDocument;
import com.pethospital.knowledge.KnowledgeDtos.CreatedVersion;
import com.pethospital.knowledge.KnowledgeDtos.DocumentView;
import com.pethospital.knowledge.KnowledgeDtos.Page;
import com.pethospital.knowledge.KnowledgeDtos.PatchDocument;
import com.pethospital.knowledge.KnowledgeDtos.PatchVersion;
import com.pethospital.knowledge.KnowledgeDtos.ReviewReceipt;
import com.pethospital.knowledge.KnowledgeDtos.ReviewVersion;
import com.pethospital.knowledge.KnowledgeDtos.SubmitReview;
import com.pethospital.knowledge.KnowledgeDtos.VersionDetail;
import com.pethospital.knowledge.KnowledgeDtos.VersionSummary;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class KnowledgeService {
    private static final String DOCUMENT_COLUMNS = """
        d.id,d.title,d.species_code,d.category_code,d.tags_json,d.status,d.active_version_id,
        d.creator_user_id,d.version,d.created_at,d.updated_at
        """;
    private static final String VERSION_COLUMNS = """
        id,document_id,version_no,source_title,source_url,source_file_id,content_hash,review_status,reviewer_user_id,
        review_note,reviewed_at,published_at,version,created_at,updated_at
        """;
    private static final String DOCUMENTS = KnowledgeController.BASE + "/documents";
    private static final String VALID_POINTER = """
        (d.active_version_id IS NULL OR EXISTS (
          SELECT 1 FROM knowledge_version v WHERE v.id=d.active_version_id AND v.document_id=d.id
            AND v.review_status='APPROVED' AND v.published_at IS NOT NULL))
        """;

    private final ObjectProvider<JdbcTemplate> templates;
    private final CurrentIdentityProvider identities;
    private final ResourceAccessPolicy access;
    private final DoctorSlotService doctors;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final FileBindingService files;
    private final ObjectMapper json;

    public KnowledgeService(
        ObjectProvider<JdbcTemplate> templates, CurrentIdentityProvider identities, ResourceAccessPolicy access,
        DoctorSlotService doctors, IdempotencyService idempotency, AuditService audit, FileBindingService files, ObjectMapper json
    ) {
        this.templates = templates;
        this.identities = identities;
        this.access = access;
        this.doctors = doctors;
        this.idempotency = idempotency;
        this.audit = audit;
        this.files = files;
        this.json = json;
    }

    public Page<DocumentView> documents(int page, int pageSize) {
        requireReader();
        JdbcTemplate jdbc = templates.getObject();
        String scope = " FROM knowledge_document d WHERE " + VALID_POINTER;
        List<DocumentView> items = jdbc.query(
            "SELECT " + DOCUMENT_COLUMNS + scope + " ORDER BY d.id DESC LIMIT ? OFFSET ?",
            (rs, row) -> documentView(rs), pageSize, offset(page, pageSize));
        return new Page<>(items, page, pageSize, jdbc.queryForObject("SELECT COUNT(*)" + scope, Long.class));
    }

    public DocumentView document(long id) {
        requireReader();
        return findDocument(templates.getObject(), id, false);
    }

    @Transactional
    public CreatedDocument createDocument(CreateDocument request, String key) {
        CurrentIdentity actor = requireAdmin();
        return idempotency.execute(command(actor, "KNOWLEDGE_DOCUMENT_CREATE", "KNOWLEDGE_DOCUMENT", DOCUMENTS, key, request),
            CreatedDocument.class, () -> {
                long id = IdWorker.getId();
                templates.getObject().update("""
                    INSERT INTO knowledge_document(id,title,species_code,category_code,tags_json,creator_user_id)
                    VALUES (?,?,?,?,CAST(? AS JSON),?)
                    """, id, request.title().strip(), code(request.speciesCode()), code(request.categoryCode()),
                    encodeTags(tags(request.tags())), actor.userId());
                CreatedDocument result = new CreatedDocument(Long.toString(id), 0);
                return new IdempotentResult<>(result, id, json.valueToTree(result));
            });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public DocumentView patchDocument(long id, PatchDocument request) {
        CurrentIdentity actor = requireAdmin();
        if (request.title() == null && request.speciesCode() == null && request.categoryCode() == null && request.tags() == null) {
            throw invalid("至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = templates.getObject();
        DocumentView current = findDocument(jdbc, id, true);
        requireVersion(current.version(), request.version());
        String species = request.speciesCode() == null ? current.speciesCode() : code(request.speciesCode());
        String category = request.categoryCode() == null ? current.categoryCode() : code(request.categoryCode());
        List<String> tags = request.tags() == null ? current.tags() : tags(request.tags());
        boolean classificationChanged = !species.equals(current.speciesCode()) || !category.equals(current.categoryCode())
            || !tags.equals(current.tags());
        if (classificationChanged && wasPublished(jdbc, id, current)) {
            throw conflict("KNOWLEDGE_CLASSIFICATION_FROZEN", "已发布过的文档不能修改物种、分类或标签");
        }
        jdbc.update("""
            UPDATE knowledge_document SET title=?,species_code=?,category_code=?,tags_json=CAST(? AS JSON),version=version+1
            WHERE id=? AND version=?
            """, requiredOrCurrent(request.title(), current.title()), species, category, encodeTags(tags), id, current.version());
        DocumentView result = findDocument(jdbc, id, false);
        recordChange(actor, "KNOWLEDGE_DOCUMENT_UPDATE", "KNOWLEDGE_DOCUMENT", id,
            json.valueToTree(Map.of("version", current.version())), json.valueToTree(Map.of("version", result.version())));
        return result;
    }

    public Page<VersionSummary> versions(long documentId, int page, int pageSize) {
        requireReader();
        JdbcTemplate jdbc = templates.getObject();
        findDocument(jdbc, documentId, false);
        List<VersionSummary> items = jdbc.query("SELECT " + VERSION_COLUMNS + """
            FROM knowledge_version WHERE document_id=? ORDER BY version_no DESC LIMIT ? OFFSET ?
            """, (rs, row) -> versionSummary(rs), documentId, pageSize, offset(page, pageSize));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_version WHERE document_id=?", Long.class, documentId);
        return new Page<>(items, page, pageSize, total);
    }

    public VersionDetail version(long id) {
        requireReader();
        return findVersion(templates.getObject(), id, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatedVersion createVersion(long documentId, CreateVersion request, String key) {
        CurrentIdentity actor = requireAdmin();
        Source source = source(request.sourceUrl(), request.sourceFileId());
        return idempotency.execute(command(actor, "KNOWLEDGE_VERSION_CREATE", "KNOWLEDGE_VERSION",
            DOCUMENTS + "/" + documentId + "/versions", key, request), CreatedVersion.class, () -> {
                JdbcTemplate jdbc = templates.getObject();
                findDocument(jdbc, documentId, true);
                int number = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(version_no),0)+1 FROM knowledge_version WHERE document_id=?
                    """, Integer.class, documentId);
                long id = IdWorker.getId();
                requireFileExists(jdbc, source.fileId());
                jdbc.update("""
                    INSERT INTO knowledge_version(id,document_id,version_no,content,source_title,source_url,source_file_id,content_hash)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, id, documentId, number, request.content(), request.sourceTitle().strip(), source.url(), source.fileId(),
                    contentHash(request.content()));
                bindSource(id, source.fileId());
                CreatedVersion result = new CreatedVersion(Long.toString(documentId), Long.toString(id), number, 0);
                return new IdempotentResult<>(result, id, json.valueToTree(result));
            });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public VersionDetail patchVersion(long id, PatchVersion request) {
        CurrentIdentity actor = requireAdmin();
        boolean sourceChanged = request.sourceUrl() != null || request.sourceFileId() != null;
        if (!sourceChanged && request.content() == null && request.sourceTitle() == null) {
            throw invalid("至少提交一个可修改字段");
        }
        JdbcTemplate jdbc = templates.getObject();
        VersionDetail current = lockVersion(jdbc, id);
        VersionSummary metadata = current.metadata();
        requireVersion(metadata.version(), request.version());
        if (!List.of("DRAFT", "REJECTED").contains(metadata.reviewStatus())) {
            throw conflict("KNOWLEDGE_VERSION_IMMUTABLE", "审核中或已批准的版本不能原地修改");
        }
        Long previousFile = metadata.sourceFileId() == null ? null : Long.valueOf(metadata.sourceFileId());
        Source source = sourceChanged ? source(request.sourceUrl(), request.sourceFileId())
            : new Source(metadata.sourceUrl(), previousFile);
        boolean newFile = source.fileId() != null && !Objects.equals(previousFile, source.fileId());
        if (newFile) {
            requireFileExists(jdbc, source.fileId());
        }
        String content = request.content() == null ? current.content() : required(request.content());
        String title = requiredOrCurrent(request.sourceTitle(), metadata.sourceTitle());
        if ("REJECTED".equals(metadata.reviewStatus()) && content.equals(current.content())
            && title.equals(metadata.sourceTitle()) && Objects.equals(source.url(), metadata.sourceUrl())
            && Objects.equals(source.fileId(), previousFile)) {
            throw invalid("驳回后须修改正文或来源");
        }
        jdbc.update("""
            UPDATE knowledge_version SET content=?,source_title=?,source_url=?,source_file_id=?,content_hash=?,
                review_status='DRAFT',reviewer_user_id=NULL,review_note=NULL,reviewed_at=NULL,version=version+1
            WHERE id=? AND version=?
            """, content, title, source.url(), source.fileId(), contentHash(content), id, metadata.version());
        if (newFile) {
            bindSource(id, source.fileId());
        }
        VersionDetail result = findVersion(jdbc, id, false);
        recordChange(actor, "KNOWLEDGE_VERSION_UPDATE", "KNOWLEDGE_VERSION", id,
            versionAudit(metadata), versionAudit(result.metadata()));
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewReceipt submitReview(long id, SubmitReview request, String key) {
        CurrentIdentity actor = requireAdmin();
        return idempotency.execute(reviewCommand(actor, ActorType.ADMIN, "KNOWLEDGE_REVIEW_SUBMIT", id,
            "submit-review", key, request, null), ReviewReceipt.class, () -> {
                JdbcTemplate jdbc = templates.getObject();
                VersionDetail current = lockVersion(jdbc, id);
                requireReviewState(current, request.version(), "DRAFT");
                requireStoredSource(jdbc, id, current);
                jdbc.update("""
                    UPDATE knowledge_version SET review_status='IN_REVIEW',reviewer_user_id=NULL,review_note=NULL,
                        reviewed_at=NULL,version=version+1 WHERE id=?
                    """, id);
                return reviewResult(jdbc, id);
            });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReviewReceipt review(long id, ReviewVersion request, String key) {
        CurrentIdentity actor = identities.require();
        long doctorId = access.requireDoctorScope().doctorId();
        boolean approved = "APPROVE".equals(request.decision());
        String note = request.reviewNote() == null || request.reviewNote().isBlank() ? null : request.reviewNote().strip();
        if (!approved && note == null) {
            throw invalid("驳回须填写审核意见");
        }
        return idempotency.execute(reviewCommand(actor, ActorType.DOCTOR, "KNOWLEDGE_REVIEW_DECIDE", id,
            "review", key, request, note), ReviewReceipt.class, () -> {
                doctors.lockEligibleDoctorUser(doctorId);
                JdbcTemplate jdbc = templates.getObject();
                VersionDetail current = lockVersion(jdbc, id);
                requireReviewState(current, request.version(), "IN_REVIEW");
                if (approved) {
                    requireStoredSource(jdbc, id, current);
                }
                jdbc.update("""
                    UPDATE knowledge_version SET review_status=?,reviewer_user_id=?,review_note=?,
                        reviewed_at=CURRENT_TIMESTAMP(3),version=version+1 WHERE id=?
                    """, approved ? "APPROVED" : "REJECTED", actor.userId(), note, id);
                return reviewResult(jdbc, id);
            });
    }

    private VersionDetail lockVersion(JdbcTemplate jdbc, long id) {
        Long documentId = jdbc.query("SELECT document_id FROM knowledge_version WHERE id=?",
            rs -> rs.next() ? rs.getLong(1) : null, id);
        if (documentId == null) {
            throw missingVersion();
        }
        findDocument(jdbc, documentId, true);
        return findVersion(jdbc, id, true);
    }

    private static void requireReviewState(VersionDetail current, int version, String state) {
        requireVersion(current.metadata().version(), version);
        if (!state.equals(current.metadata().reviewStatus())) {
            throw conflict("KNOWLEDGE_REVIEW_STATE_CONFLICT", "当前版本状态不允许此审核操作");
        }
    }

    private static void requireStoredSource(JdbcTemplate jdbc, long id, VersionDetail current) {
        VersionSummary metadata = current.metadata();
        required(current.content());
        required(metadata.sourceTitle());
        Long fileId = metadata.sourceFileId() == null ? null : Long.valueOf(metadata.sourceFileId());
        source(metadata.sourceUrl(), fileId);
        if (fileId != null && !Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(
                SELECT 1 FROM knowledge_version v JOIN knowledge_document d ON d.id=v.document_id
                JOIN file_object f ON f.id=v.source_file_id AND f.uploader_user_id=d.creator_user_id
                JOIN file_binding b ON b.business_id=v.id AND b.file_id=f.id AND b.owner_user_id=d.creator_user_id
                WHERE v.id=? AND b.business_type='KNOWLEDGE_VERSION'
                  AND f.purpose='KNOWLEDGE' AND f.status='READY' AND f.deleted_at IS NULL)
            """, Boolean.class, id))) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "KNOWLEDGE_SOURCE_UNAVAILABLE", "来源附件不可核查");
        }
    }

    private IdempotentResult<ReviewReceipt> reviewResult(JdbcTemplate jdbc, long id) {
        VersionSummary version = findVersion(jdbc, id, false).metadata();
        ReviewReceipt result = new ReviewReceipt(version.knowledgeVersionId(), version.documentId(), version.reviewStatus(),
            version.reviewerUserId(), version.reviewedAt(), version.version());
        return new IdempotentResult<>(result, id, versionAudit(version));
    }

    private static IdempotencyCommand reviewCommand(
        CurrentIdentity actor, ActorType role, String action, long id, String suffix, String key, Object request, String note
    ) {
        String path = KnowledgeController.BASE + "/versions/" + id + "/" + suffix;
        return new IdempotencyCommand(actor.userId(), role, action, "KNOWLEDGE_VERSION", "POST", path, key,
            request, null, note, null);
    }

    private DocumentView findDocument(JdbcTemplate jdbc, long id, boolean lock) {
        DocumentView document = jdbc.query("SELECT " + DOCUMENT_COLUMNS + " FROM knowledge_document d WHERE d.id=? AND "
            + VALID_POINTER + (lock ? " FOR UPDATE" : ""), rs -> rs.next() ? documentView(rs) : null, id);
        if (document == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "KNOWLEDGE_DOCUMENT_NOT_FOUND", "知识文档不存在");
        }
        return document;
    }

    private static VersionDetail findVersion(JdbcTemplate jdbc, long id, boolean lock) {
        VersionDetail version = jdbc.query("SELECT " + VERSION_COLUMNS
            + ",content FROM knowledge_version WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new VersionDetail(versionSummary(rs), rs.getString("content")) : null, id);
        if (version == null) {
            throw missingVersion();
        }
        return version;
    }

    private static boolean wasPublished(JdbcTemplate jdbc, long id, DocumentView current) {
        return "PUBLISHED".equals(current.status()) || "OFFLINE".equals(current.status())
            || Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM knowledge_version WHERE document_id=? AND published_at IS NOT NULL)
                """, Boolean.class, id));
    }

    private static void requireFileExists(JdbcTemplate jdbc, Long fileId) {
        if (fileId != null && jdbc.query("SELECT id FROM file_object WHERE id=? FOR UPDATE",
            rs -> rs.next() ? rs.getLong(1) : null, fileId) == null) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_NOT_READY", "来源附件不存在");
        }
    }

    private void bindSource(long versionId, Long fileId) {
        if (fileId != null) {
            files.appendImmutable(FileBusinessType.KNOWLEDGE_VERSION, versionId, List.of(fileId));
        }
    }

    private CurrentIdentity requireAdmin() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.authorities().contains("ROLE_ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员可编辑知识库");
        }
        return actor;
    }

    private void requireReader() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.authorities().contains("ROLE_ADMIN")) {
            access.requireDoctorScope();
        }
    }

    private static IdempotencyCommand command(CurrentIdentity actor, String action, String type, String path, String key, Object body) {
        return new IdempotencyCommand(actor.userId(), ActorType.ADMIN, action, type, "POST", path, key, body, null, null, null);
    }

    private void recordChange(CurrentIdentity actor, String action, String type, long id, JsonNode before, JsonNode after) {
        audit.append(new AuditEvent(actor.userId(), ActorType.ADMIN, action, new ResourceReference(type, id), before, after,
            null, null, null, AuditResult.SUCCESS, null));
    }

    private JsonNode versionAudit(VersionSummary version) {
        return json.valueToTree(Map.of("version", version.version(), "contentHash", version.contentHash(),
            "reviewStatus", version.reviewStatus()));
    }

    private DocumentView documentView(ResultSet rs) throws SQLException {
        return new DocumentView(rs.getString("id"), rs.getString("title"), rs.getString("species_code"),
            rs.getString("category_code"), decodeTags(rs.getString("tags_json")), rs.getString("status"),
            rs.getString("active_version_id"), rs.getString("creator_user_id"), rs.getInt("version"),
            instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static VersionSummary versionSummary(ResultSet rs) throws SQLException {
        return new VersionSummary(rs.getString("id"), rs.getString("document_id"), rs.getInt("version_no"),
            rs.getString("source_title"), rs.getString("source_url"), rs.getString("source_file_id"), rs.getString("content_hash"),
            rs.getString("review_status"), rs.getString("reviewer_user_id"), rs.getString("review_note"),
            instant(rs, "reviewed_at"), instant(rs, "published_at"), rs.getInt("version"),
            instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        LocalDateTime time = rs.getObject(field, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private List<String> decodeTags(String value) {
        try {
            return value == null ? List.of() : json.readValue(value, new TypeReference<List<String>>() { });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("知识标签无法读取", invalid);
        }
    }

    private String encodeTags(List<String> value) {
        return json.valueToTree(value).toString();
    }

    private static List<String> tags(List<String> input) {
        return input == null ? List.of() : input.stream().map(String::strip).distinct().toList();
    }

    private static long offset(int page, int pageSize) {
        return (page - 1L) * pageSize;
    }

    private static String code(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static String requiredOrCurrent(String proposed, String current) {
        return proposed == null ? current : required(proposed).strip();
    }

    private static String required(String value) {
        if (value.isBlank()) {
            throw invalid("知识内容不能为空");
        }
        return value;
    }

    private static Source source(String url, Long fileId) {
        if (url == null || url.isBlank()) {
            if (fileId == null) {
                throw invalid("须提供来源链接或附件");
            }
            return new Source(null, fileId);
        }
        try {
            URI uri = URI.create(url.strip());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) {
                return new Source(url.strip(), fileId);
            }
        } catch (IllegalArgumentException invalid) {
            throw invalid("来源链接须为有效的 HTTPS URL");
        }
        throw invalid("来源链接须为有效的 HTTPS URL");
    }

    private static String contentHash(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireVersion(int current, int expected) {
        if (current != expected) {
            throw conflict("VERSION_CONFLICT", "知识记录已更新，请刷新后重试");
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_KNOWLEDGE_INPUT", message);
    }

    private static BusinessException missingVersion() {
        return new BusinessException(HttpStatus.NOT_FOUND, "KNOWLEDGE_VERSION_NOT_FOUND", "知识版本不存在");
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }

    private record Source(String url, Long fileId) { }
}
