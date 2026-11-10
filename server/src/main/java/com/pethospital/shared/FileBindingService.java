package com.pethospital.shared;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal attachment boundary for business services. This deliberately has no controller:
 * callers bind files while changing the owning business object in the same transaction.
 */
@Service
public class FileBindingService {
    private static final int MAX_FILES_PER_CHANGE = 20;
    private static final Set<FileBusinessType> MUTABLE_TYPES = Set.of(
        FileBusinessType.PET,
        FileBusinessType.HEALTH_LOG,
        FileBusinessType.MEDICAL_DRAFT,
        FileBusinessType.GUIDE,
        FileBusinessType.QUALIFICATION,
        FileBusinessType.USER_AVATAR
    );
    private static final Set<FileBusinessType> IMMUTABLE_TYPES = Set.of(
        FileBusinessType.MEDICAL_VERSION,
        FileBusinessType.KNOWLEDGE_VERSION
    );

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;

    public FileBindingService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
    }

    /** Replace the complete attachment set of a mutable draft/log/profile object. */
    @Transactional
    public void replaceMutable(FileBusinessType businessType, long businessId, List<Long> fileIds) {
        requireType(MUTABLE_TYPES, businessType, "IMMUTABLE_FILE_BINDING");
        CurrentIdentity actor = identities.require();
        long ownerUserId = authorizeTarget(actor, businessType, businessId);
        relationGuard().requireFileBindingTarget(businessType, businessId, ownerUserId);
        List<Long> normalized = normalizeFileIds(fileIds);
        validateFiles(actor, businessType, normalized);

        JdbcTemplate jdbc = jdbc();
        jdbc.update(
            "DELETE FROM file_binding WHERE business_type=? AND business_id=?",
            businessType.name(),
            businessId
        );
        insertMissing(jdbc, actor.userId(), ownerUserId, businessType, businessId, normalized);
    }

    /** Append evidence to an immutable medical/knowledge version. Existing rows are never removed. */
    @Transactional
    public void appendImmutable(FileBusinessType businessType, long businessId, List<Long> fileIds) {
        requireType(IMMUTABLE_TYPES, businessType, "MUTABLE_FILE_BINDING");
        CurrentIdentity actor = identities.require();
        long ownerUserId = authorizeTarget(actor, businessType, businessId);
        relationGuard().requireFileBindingTarget(businessType, businessId, ownerUserId);
        List<Long> normalized = normalizeFileIds(fileIds);
        if (normalized.isEmpty()) {
            return;
        }
        validateFiles(actor, businessType, normalized);
        insertMissing(jdbc(), actor.userId(), ownerUserId, businessType, businessId, normalized);
    }

    private long authorizeTarget(CurrentIdentity actor, FileBusinessType type, long businessId) {
        if (businessId <= 0) {
            throw invalidTarget();
        }
        JdbcTemplate jdbc = jdbc();
        Long ownerUserId = switch (type) {
            case PET -> {
                requireAudienceRole(actor, "MINI", "USER");
                yield queryLong(jdbc,
                    "SELECT owner_user_id FROM pet WHERE id=? AND owner_user_id=? FOR UPDATE",
                    businessId, actor.userId());
            }
            case HEALTH_LOG -> {
                requireAudienceRole(actor, "MINI", "USER");
                yield queryLong(jdbc, """
                    SELECT p.owner_user_id FROM pet_health_log l
                    JOIN pet p ON p.id=l.pet_id
                    WHERE l.id=? AND l.author_user_id=? AND p.owner_user_id=? FOR UPDATE
                    """, businessId, actor.userId(), actor.userId());
            }
            case MEDICAL_DRAFT -> {
                requireAudienceRole(actor, "WEB", "DOCTOR");
                yield queryLong(jdbc, """
                    SELECT a.user_id FROM medical_record r
                    JOIN appointment a ON a.id=r.appointment_id
                    JOIN doctor_profile d ON d.id=r.doctor_id
                    WHERE r.id=? AND r.status='DRAFT' AND d.user_id=? AND d.status='ACTIVE' FOR UPDATE
                    """, businessId, actor.userId());
            }
            case MEDICAL_VERSION -> {
                requireAudienceRole(actor, "WEB", "DOCTOR");
                yield queryLong(jdbc, """
                    SELECT a.user_id FROM medical_record_version v
                    JOIN medical_record r ON r.id=v.record_id
                    JOIN appointment a ON a.id=r.appointment_id
                    JOIN doctor_profile d ON d.id=r.doctor_id
                    WHERE v.id=? AND d.user_id=? AND d.status='ACTIVE' FOR UPDATE
                    """, businessId, actor.userId());
            }
            case KNOWLEDGE_VERSION -> {
                requireWebEditor(actor);
                yield queryLong(jdbc, """
                    SELECT d.creator_user_id FROM knowledge_version v
                    JOIN knowledge_document d ON d.id=v.document_id
                    WHERE v.id=? AND d.creator_user_id=? FOR UPDATE
                    """, businessId, actor.userId());
            }
            case GUIDE -> {
                requireAudienceRole(actor, "WEB", "ADMIN");
                yield queryLong(jdbc,
                    "SELECT editor_user_id FROM health_guide WHERE id=? AND editor_user_id=? FOR UPDATE",
                    businessId, actor.userId());
            }
            case QUALIFICATION -> {
                requireAudienceRole(actor, "WEB", "ADMIN");
                yield queryLong(jdbc,
                    "SELECT user_id FROM doctor_profile WHERE id=? FOR UPDATE",
                    businessId);
            }
            case USER_AVATAR -> {
                if (businessId != actor.userId()) {
                    throw invalidTarget();
                }
                yield queryLong(jdbc,
                    "SELECT id FROM sys_user WHERE id=? AND status='ACTIVE' FOR UPDATE",
                    businessId);
            }
        };
        if (ownerUserId == null) {
            throw invalidTarget();
        }
        return ownerUserId;
    }

    private void validateFiles(CurrentIdentity actor, FileBusinessType type, List<Long> fileIds) {
        String expectedPurpose = expectedPurpose(type);
        JdbcTemplate jdbc = jdbc();
        for (long fileId : fileIds) {
            FileSource file = jdbc.query("""
                SELECT uploader_user_id,purpose,status FROM file_object
                WHERE id=? AND deleted_at IS NULL FOR UPDATE
                """, rs -> rs.next()
                    ? new FileSource(rs.getLong(1), rs.getString(2), rs.getString(3))
                    : null, fileId);
            if (file == null || !"READY".equals(file.status())) {
                throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_NOT_READY",
                    "附件不存在或尚未通过安全校验"
                );
            }
            if (!expectedPurpose.equals(file.purpose())) {
                throw new BusinessException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "FILE_PURPOSE_MISMATCH",
                    "附件用途与业务对象不匹配"
                );
            }
            if (file.uploaderUserId() != actor.userId()) {
                throw new BusinessException(
                    HttpStatus.FORBIDDEN,
                    "FILE_SOURCE_FORBIDDEN",
                    "不能绑定其他用户上传的文件"
                );
            }
        }
    }

    private static String expectedPurpose(FileBusinessType type) {
        return switch (type) {
            case PET -> "PET";
            case HEALTH_LOG -> "HEALTH_LOG";
            case MEDICAL_DRAFT, MEDICAL_VERSION -> "MEDICAL";
            case KNOWLEDGE_VERSION -> "KNOWLEDGE";
            case GUIDE -> "GUIDE";
            case QUALIFICATION -> "QUALIFICATION";
            case USER_AVATAR -> "AVATAR";
        };
    }

    private static List<Long> normalizeFileIds(List<Long> fileIds) {
        if (fileIds == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_IDS", "附件列表不能为空");
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null || fileId <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_IDS", "附件ID不合法");
            }
            normalized.add(fileId);
        }
        if (normalized.size() > MAX_FILES_PER_CHANGE) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TOO_MANY_FILES", "单次最多绑定20个附件");
        }
        return List.copyOf(normalized);
    }

    private static void insertMissing(
        JdbcTemplate jdbc,
        long actorUserId,
        long ownerUserId,
        FileBusinessType type,
        long businessId,
        List<Long> fileIds
    ) {
        for (long fileId : fileIds) {
            Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM file_binding
                WHERE file_id=? AND business_type=? AND business_id=?
                """, Integer.class, fileId, type.name(), businessId);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbc.update("""
                INSERT INTO file_binding(id,file_id,business_type,business_id,owner_user_id,created_by)
                VALUES (?,?,?,?,?,?)
                """, IdWorker.getId(), fileId, type.name(), businessId, ownerUserId, actorUserId);
        }
    }

    private static void requireType(Set<FileBusinessType> allowed, FileBusinessType type, String code) {
        if (type == null || !allowed.contains(type)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                code,
                "该附件类型不允许执行此绑定操作"
            );
        }
    }

    private static void requireAudienceRole(CurrentIdentity actor, String audience, String role) {
        if (!audience.equals(actor.clientType()) || !actor.roles().contains(role)) {
            throw invalidTarget();
        }
    }

    private static void requireWebEditor(CurrentIdentity actor) {
        if (!"WEB".equals(actor.clientType())
            || (!actor.roles().contains("DOCTOR") && !actor.roles().contains("ADMIN"))) {
            throw invalidTarget();
        }
    }

    private static BusinessException invalidTarget() {
        return new BusinessException(
            HttpStatus.FORBIDDEN,
            "FILE_BINDING_TARGET_FORBIDDEN",
            "无权修改该业务对象的附件"
        );
    }

    private static Long queryLong(JdbcTemplate jdbc, String sql, Object... arguments) {
        return jdbc.query(sql, rs -> rs.next() ? rs.getLong(1) : null, arguments);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    private CrossTableRelationGuard relationGuard() {
        return new CrossTableRelationGuard(dataSource());
    }

    private DataSource dataSource() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return dataSource;
    }

    private record FileSource(long uploaderUserId, String purpose, String status) { }
}
