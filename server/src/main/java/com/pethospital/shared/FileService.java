package com.pethospital.shared;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.MedicalAccess;
import com.pethospital.identity.ResourceAccessPolicy.PetAccess;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.FileDtos.DiscardRequest;
import com.pethospital.shared.FileDtos.DiscardView;
import com.pethospital.shared.FileDtos.DownloadTicketRequest;
import com.pethospital.shared.FileDtos.DownloadTicketView;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.FileDtos.FilePurpose;
import com.pethospital.shared.FileDtos.FileStatus;
import com.pethospital.shared.FileDtos.FileUploadResult;
import com.pethospital.shared.FileDtos.FileView;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;

@Service
public class FileService {
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd")
        .withZone(ZoneOffset.UTC);

    private final ObjectProvider<DataSource> dataSourceProvider;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final CurrentIdentityProvider identities;
    private final FileContentValidator validator;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ResourceAccessPolicy accessPolicy;
    private final AuditService audit;
    private final RequestHasher requestHasher;

    public FileService(
        ObjectProvider<DataSource> dataSourceProvider,
        ObjectProvider<ObjectStorageAdapter> storageProvider,
        CurrentIdentityProvider identities,
        FileContentValidator validator,
        IdempotencyService idempotency,
        ObjectMapper objectMapper,
        Clock clock,
        ResourceAccessPolicy accessPolicy,
        AuditService audit,
        RequestHasher requestHasher
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.storageProvider = storageProvider;
        this.identities = identities;
        this.validator = validator;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
        this.audit = audit;
        this.requestHasher = requestHasher;
    }

    public FileView upload(MultipartFile file, String purposeText, String operationKey) {
        ObjectStorageAdapter storage = storage();
        CurrentIdentity actor = identities.require();
        FilePurpose purpose = FilePurpose.parse(purposeText);
        requireUploadPermission(actor, purpose);
        String originalName = safeOriginalName(file == null ? null : file.getOriginalFilename());
        byte[] original = read(file);
        String originalHash = sha256(original);
        var validation = validator.validate(purpose, originalName, original);
        var requestBody = Map.of(
            "purpose", purpose.name(),
            "originalName", originalName,
            "sizeBytes", original.length,
            "contentSha256", originalHash
        );
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "FILE_UPLOAD", "FILE_OBJECT",
            "POST", "/api/v1/files", operationKey, requestBody, null, null, null
        );
        FileUploadResult result = idempotency.execute(
            command,
            FileUploadResult.class,
            () -> persist(actor.userId(), originalName, purpose, validation, originalHash, storage)
        );
        if (result.rejected()) {
            throw new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                result.rejectionCode(),
                result.rejectionMessage(),
                Map.of("fileId", result.file().id(), "status", result.file().status())
            );
        }
        return result.file();
    }

    @Transactional(readOnly = true)
    public FileView detail(long fileId) {
        CurrentIdentity actor = identities.require();
        FileRow row = jdbc().query("""
            SELECT id,uploader_user_id,original_name,mime_type,size_bytes,purpose,status
            FROM file_object WHERE id=? AND deleted_at IS NULL
            """, result -> result.next() ? new FileRow(
            result.getLong("id"),
            result.getLong("uploader_user_id"),
            result.getString("original_name"),
            result.getString("mime_type"),
            result.getLong("size_bytes"),
            result.getString("purpose"),
            result.getString("status")
        ) : null, fileId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
        }
        if (row.uploaderUserId() != actor.userId() && !hasBindingAccess(fileId, actor.userId())
            && !accessPolicy.canReadKnowledgeReviewSource(fileId, null)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FILE_ACCESS_DENIED", "无权查看文件元数据");
        }
        return view(row);
    }

    @Transactional
    public DownloadTicketView downloadTicket(
        long fileId,
        DownloadTicketRequest request,
        String operationKey
    ) {
        CurrentIdentity actor = identities.require();
        DownloadRow file = requireDownloadable(fileId);
        List<BindingRow> bindings = bindings(fileId);
        if (bindings.isEmpty()) {
            if (file.uploaderUserId() != actor.userId()
                || request.businessType() != null || request.businessId() != null) {
                throw accessDenied();
            }
        } else {
            FileBusinessType type = parseBusinessType(request.businessType());
            long businessId = parseBusinessId(request.businessId());
            BindingRow binding = bindings.stream()
                .filter(item -> item.type() == type && item.businessId() == businessId)
                .findFirst().orElseThrow(FileService::accessDenied);
            if (!canReadBinding(actor, binding, fileId)) {
                throw accessDenied();
            }
        }
        ObjectStorageAdapter.DownloadTicket ticket = storage().presignDownload(file.objectKey());
        audit.append(new AuditEvent(
            actor.userId(), actorType(actor), "FILE_DOWNLOAD_TICKET",
            new ResourceReference("FILE_OBJECT", fileId), null,
            objectMapper.valueToTree(Map.of("expiresAt", ticket.expiresAt().toString())),
            null, operationKey,
            requestHasher.hash("POST", "/api/v1/files/" + fileId + "/download-ticket", request),
            AuditResult.SUCCESS, null
        ));
        return new DownloadTicketView(ticket.url().toString(), ticket.expiresAt().toString());
    }

    @Transactional
    public DiscardView discard(long fileId, DiscardRequest request, String operationKey) {
        CurrentIdentity actor = identities.require();
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "FILE_DISCARD", "FILE_OBJECT",
            "POST", "/api/v1/files/" + fileId + "/discard", operationKey, request,
            objectMapper.valueToTree(Map.of("status", "READY")), request.reason(), null
        );
        return idempotency.execute(
            command,
            DiscardView.class,
            () -> persistDiscard(actor.userId(), fileId)
        );
    }

    private IdempotentResult<DiscardView> persistDiscard(long actorUserId, long fileId) {
        DownloadRow file = requireDownloadableForUpdate(fileId);
        if (file.uploaderUserId() != actorUserId || !bindings(fileId).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "FILE_DISCARD_FORBIDDEN", "仅可删除本人尚未绑定的文件");
        }
        try {
            storage().delete(file.objectKey());
        } catch (RuntimeException exception) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "对象清理失败，文件状态未改变"
            );
        }
        int updated = jdbc().update("""
            UPDATE file_object SET status='DELETED',deleted_at=CURRENT_TIMESTAMP(3)
            WHERE id=? AND status='READY' AND deleted_at IS NULL
            """, fileId);
        if (updated != 1) {
            throw new IllegalStateException("file metadata did not transition to DELETED");
        }
        DiscardView view = new DiscardView(Long.toString(fileId), "DELETED");
        return new IdempotentResult<>(
            view,
            fileId,
            objectMapper.valueToTree(Map.of("status", "DELETED"))
        );
    }

    private DownloadRow requireDownloadable(long fileId) {
        return downloadRow(fileId, false);
    }

    private DownloadRow requireDownloadableForUpdate(long fileId) {
        return downloadRow(fileId, true);
    }

    private DownloadRow downloadRow(long fileId, boolean lock) {
        DownloadRow row = jdbc().query("""
            SELECT id,uploader_user_id,object_key FROM file_object
            WHERE id=? AND status='READY' AND deleted_at IS NULL
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
                ? new DownloadRow(rs.getLong(1), rs.getLong(2), rs.getString(3)) : null, fileId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "文件不存在");
        }
        return row;
    }

    private List<BindingRow> bindings(long fileId) {
        return jdbc().query("""
            SELECT business_type,business_id,owner_user_id,created_by
            FROM file_binding WHERE file_id=?
            """, (rs, row) -> new BindingRow(
            FileBusinessType.valueOf(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4)
        ), fileId);
    }

    private boolean canReadBinding(CurrentIdentity actor, BindingRow binding, long fileId) {
        return switch (binding.type()) {
            case PET -> accessPolicy.pet(binding.businessId()) != PetAccess.NONE;
            case HEALTH_LOG -> accessPolicy.healthLog(binding.businessId()) != PetAccess.NONE;
            case MEDICAL_DRAFT -> accessPolicy.medicalRecord(binding.businessId()) == MedicalAccess.AUTHOR;
            case MEDICAL_VERSION -> accessPolicy.medicalRecordVersion(binding.businessId()) != MedicalAccess.NONE;
            case KNOWLEDGE_VERSION -> binding.ownerUserId() == actor.userId()
                || binding.createdBy() == actor.userId() || accessPolicy.canReadKnowledgeReviewSource(fileId, binding.businessId());
            case GUIDE -> binding.ownerUserId() == actor.userId()
                || publishedGuide(binding.businessId());
            case QUALIFICATION -> binding.ownerUserId() == actor.userId()
                || ("WEB".equals(actor.clientType()) && actor.roles().contains("ADMIN"));
            case USER_AVATAR -> binding.ownerUserId() == actor.userId();
        };
    }

    private boolean publishedGuide(long guideId) {
        Boolean exists = jdbc().queryForObject(
            "SELECT EXISTS(SELECT 1 FROM health_guide WHERE id=? AND status='PUBLISHED')",
            Boolean.class, guideId);
        return Boolean.TRUE.equals(exists);
    }

    private static FileBusinessType parseBusinessType(String value) {
        try {
            return FileBusinessType.valueOf(value == null ? "" : value.strip().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw accessDenied();
        }
    }

    private static long parseBusinessId(String value) {
        try {
            long parsed = Long.parseLong(value == null ? "" : value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw accessDenied();
        }
    }

    private static BusinessException accessDenied() {
        return new BusinessException(HttpStatus.FORBIDDEN, "FILE_ACCESS_DENIED", "无权访问该文件");
    }

    private IdempotentResult<FileUploadResult> persist(
        long uploaderUserId,
        String originalName,
        FilePurpose purpose,
        FileContentValidator.Validation validation,
        String originalHash,
        ObjectStorageAdapter storage
    ) {
        long fileId = IdWorker.getId();
        String objectKey = "quarantine/" + KEY_DATE.format(clock.instant()) + "/"
            + fileId + "-" + UUID.randomUUID();
        byte[] storedBytes = validation.accepted() ? validation.bytes() : new byte[0];
        long size = validation.accepted() ? storedBytes.length : validation.observedSize();
        String digest = validation.accepted() ? sha256(storedBytes) : originalHash;
        JdbcTemplate jdbc = jdbc();
        jdbc.update("""
            INSERT INTO file_object(
                id,uploader_user_id,object_key,original_name,mime_type,size_bytes,sha256,purpose,status
            ) VALUES (?,?,?,?,?,?,?,?,'QUARANTINED')
            """,
            fileId, uploaderUserId, objectKey, originalName, validation.mimeType(), size, digest, purpose.name()
        );
        FileStatus status;
        if (validation.accepted()) {
            put(storage, objectKey, storedBytes, validation.mimeType());
            registerRollbackCleanup(storage, objectKey);
            int updated = jdbc.update("""
                UPDATE file_object SET status='READY',validated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='QUARANTINED'
                """, fileId);
            if (updated != 1) {
                throw new IllegalStateException("file metadata did not transition to READY");
            }
            status = FileStatus.READY;
        } else {
            int updated = jdbc.update("""
                UPDATE file_object SET status='REJECTED',validated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND status='QUARANTINED'
                """, fileId);
            if (updated != 1) {
                throw new IllegalStateException("file metadata did not transition to REJECTED");
            }
            status = FileStatus.REJECTED;
        }
        FileView view = new FileView(
            Long.toString(fileId), originalName, validation.mimeType(), Long.toString(size),
            purpose.name(), status.name()
        );
        FileUploadResult result = new FileUploadResult(
            view, validation.rejectionCode(), validation.rejectionMessage()
        );
        return new IdempotentResult<>(
            result,
            fileId,
            objectMapper.valueToTree(Map.of(
                "id", view.id(),
                "purpose", view.purpose(),
                "mimeType", view.mimeType(),
                "sizeBytes", view.sizeBytes(),
                "status", view.status()
            ))
        );
    }

    private boolean hasBindingAccess(long fileId, long userId) {
        Integer count = jdbc().queryForObject("""
            SELECT COUNT(*) FROM file_binding
            WHERE file_id=? AND (owner_user_id=? OR created_by=?)
            """, Integer.class, fileId, userId, userId);
        return count != null && count > 0;
    }

    private static void requireUploadPermission(CurrentIdentity actor, FilePurpose purpose) {
        boolean mini = "MINI".equals(actor.clientType());
        boolean web = "WEB".equals(actor.clientType());
        boolean user = actor.roles().contains("USER");
        boolean doctor = actor.roles().contains("DOCTOR");
        boolean admin = actor.roles().contains("ADMIN");
        boolean allowed = switch (purpose) {
            case AVATAR -> (mini && user) || (web && (doctor || admin));
            case PET, HEALTH_LOG -> mini && user;
            case MEDICAL -> web && doctor;
            case KNOWLEDGE -> web && (doctor || admin);
            case GUIDE, QUALIFICATION -> web && admin;
        };
        if (!allowed) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FILE_PURPOSE_FORBIDDEN", "无权上传该用途文件");
        }
    }

    private static ActorType actorType(CurrentIdentity actor) {
        if (actor.roles().contains("ADMIN")) {
            return ActorType.ADMIN;
        }
        if (actor.roles().contains("DOCTOR")) {
            return ActorType.DOCTOR;
        }
        return ActorType.USER;
    }

    private static String safeOriginalName(String value) {
        if (value == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME", "文件名不能为空");
        }
        String normalized = value.strip().replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.equals(".")
            || normalized.equals("..") || normalized.codePoints().anyMatch(codePoint ->
                Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT
            )) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME", "文件名不合法");
        }
        return normalized;
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "文件不能为空");
        }
        if (file.getSize() > FileContentValidator.PDF_LIMIT) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_TOO_LARGE", "文件超过10MB限制");
        }
        try {
            return file.getBytes();
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "FILE_READ_FAILED", "无法读取上传文件");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void put(ObjectStorageAdapter storage, String key, byte[] bytes, String contentType) {
        try {
            storage.put(key, bytes, contentType);
        } catch (SdkException exception) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "对象存储暂时不可用"
            );
        }
    }

    private static void registerRollbackCleanup(ObjectStorageAdapter storage, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException ignored) {
                        // A later orphan cleanup may retry; never hide the original transaction failure.
                    }
                }
            }
        });
    }

    private ObjectStorageAdapter storage() {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "OBJECT_STORAGE_UNAVAILABLE",
                "对象存储未配置"
            );
        }
        return storage;
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "数据库不可用");
        }
        return new JdbcTemplate(dataSource);
    }

    private static FileView view(FileRow row) {
        return new FileView(
            Long.toString(row.id()), row.originalName(), row.mimeType(), Long.toString(row.sizeBytes()),
            row.purpose(), row.status()
        );
    }

    private record FileRow(
        long id,
        long uploaderUserId,
        String originalName,
        String mimeType,
        long sizeBytes,
        String purpose,
        String status
    ) {
    }

    private record DownloadRow(long id, long uploaderUserId, String objectKey) { }

    private record BindingRow(
        FileBusinessType type,
        long businessId,
        long ownerUserId,
        long createdBy
    ) { }
}
