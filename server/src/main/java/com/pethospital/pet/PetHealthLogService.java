package com.pethospital.pet;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.pet.PetHealthLogDtos.HealthLogCreateRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.pet.PetHealthLogDtos.HealthLogUpdateRequest;
import com.pethospital.pet.PetHealthLogDtos.HealthLogView;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.BusinessException;
import com.pethospital.shared.FileBindingService;
import com.pethospital.shared.IdempotencyService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetHealthLogService {
    private final PetMapper pets;
    private final ActivePetGuard activePets;
    private final PetHealthLogMapper logs;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final FileBindingService fileBindings;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public PetHealthLogService(
        PetMapper pets,
        ActivePetGuard activePets,
        PetHealthLogMapper logs,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        FileBindingService fileBindings,
        AuditService audit,
        ObjectMapper objectMapper
    ) {
        this.pets = pets;
        this.activePets = activePets;
        this.logs = logs;
        this.identities = identities;
        this.idempotency = idempotency;
        this.fileBindings = fileBindings;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public HealthLogView create(long petId, HealthLogCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireMiniUser();
        HealthLogCreateRequest normalized = normalized(request);
        validateContent(normalized);
        var command = new IdempotencyCommand(
            actor.userId(), ActorType.USER, "HEALTH_LOG_CREATE", "HEALTH_LOG", "POST",
            "/api/v1/pets/" + petId + "/health-logs", operationKey, normalized, null, null, null
        );
        return idempotency.execute(
            command, HealthLogView.class, () -> insertLocked(petId, actor.userId(), normalized)
        );
    }

    @Transactional(readOnly = true)
    public HealthLogPage list(long petId, Instant from, Instant to, int page, int pageSize) {
        CurrentIdentity actor = requireMiniUser();
        requireOwnedPet(petId, actor.userId());
        return listOwned(petId, actor.userId(), from, to, page, pageSize);
    }

    HealthLogPage listOwned(long petId, long ownerId, Instant from, Instant to, int page, int pageSize) {
        validateRange(from, to);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        return new HealthLogPage(
            logs.listOwned(petId, ownerId, from, to, pageSize, offset),
            page,
            pageSize,
            logs.countOwned(petId, ownerId, from, to)
        );
    }

    @Transactional(readOnly = true)
    public HealthLogView detail(long petId, long logId) {
        CurrentIdentity actor = requireMiniUser();
        requireOwnedPet(petId, actor.userId());
        HealthLogView view = logs.findOwned(petId, logId, actor.userId());
        if (view == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "HEALTH_LOG_NOT_FOUND", "健康日志不存在");
        }
        return view;
    }

    @Transactional
    public HealthLogView patch(long petId, long logId, HealthLogUpdateRequest request) {
        CurrentIdentity actor = requireMiniUser();
        HealthLogUpdateRequest normalized = normalized(request);
        validateContent(normalized);
        activePets.lockOwnedActive(petId, actor.userId());
        PetHealthLogMapper.HealthLogRow beforeRow = logs.findOwnedRow(petId, logId, actor.userId(), true);
        if (beforeRow == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "HEALTH_LOG_NOT_FOUND", "健康日志不存在");
        }
        HealthLogView before = logs.view(beforeRow);
        if (before.version() != request.version()) {
            throw versionConflict();
        }
        if (logs.updateOwned(petId, logId, actor.userId(), normalized) != 1) {
            throw versionConflict();
        }
        fileBindings.replaceMutable(FileBusinessType.HEALTH_LOG, logId, normalized.attachmentIds());
        HealthLogView after = logs.findOwned(petId, logId, actor.userId());
        if (after == null) {
            throw new IllegalStateException("健康日志更新后无法读取");
        }
        audit.append(new AuditEvent(
            actor.userId(), ActorType.USER, "HEALTH_LOG_UPDATE",
            new ResourceReference("HEALTH_LOG", logId),
            objectMapper.valueToTree(before), objectMapper.valueToTree(after),
            null, null, null, AuditResult.SUCCESS, null
        ));
        return after;
    }

    private IdempotentResult<HealthLogView> insertLocked(
        long petId, long ownerUserId, HealthLogCreateRequest request
    ) {
        activePets.lockOwnedActive(petId, ownerUserId);
        long logId = IdWorker.getId();
        logs.insert(logId, petId, ownerUserId, request);
        fileBindings.replaceMutable(FileBusinessType.HEALTH_LOG, logId, request.attachmentIds());
        HealthLogView view = logs.findOwned(petId, logId, ownerUserId);
        if (view == null) {
            throw new IllegalStateException("健康日志创建后无法读取");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("petId", view.petId());
        snapshot.put("observedAt", view.observedAt());
        snapshot.put("weightKg", view.weightKg());
        snapshot.put("attachmentCount", view.attachmentIds().size());
        return new IdempotentResult<>(view, logId, objectMapper.valueToTree(snapshot));
    }

    private void requireOwnedPet(long petId, long ownerUserId) {
        if (pets.findOwnedRow(petId, ownerUserId, false) == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
    }

    private CurrentIdentity requireMiniUser() {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问健康日志");
        }
        return actor;
    }

    private static HealthLogCreateRequest normalized(HealthLogCreateRequest request) {
        return new HealthLogCreateRequest(
            request.observedAt(), request.weightKg(), nullableText(request.dietNote()),
            nullableText(request.stoolNote()), nullableText(request.activityNote()),
            nullableText(request.symptomNote()), request.attachmentIds()
        );
    }

    private static HealthLogUpdateRequest normalized(HealthLogUpdateRequest request) {
        return new HealthLogUpdateRequest(
            request.observedAt(), request.weightKg(), nullableText(request.dietNote()),
            nullableText(request.stoolNote()), nullableText(request.activityNote()),
            nullableText(request.symptomNote()), request.attachmentIds(), request.version()
        );
    }

    private static void validateContent(HealthLogCreateRequest request) {
        if (request.weightKg() == null && request.dietNote() == null && request.stoolNote() == null
            && request.activityNote() == null && request.symptomNote() == null) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST, "EMPTY_HEALTH_LOG", "健康日志至少填写一项记录内容"
            );
        }
    }

    private static void validateContent(HealthLogUpdateRequest request) {
        if (request.weightKg() == null && request.dietNote() == null && request.stoolNote() == null
            && request.activityNote() == null && request.symptomNote() == null) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST, "EMPTY_HEALTH_LOG", "健康日志至少填写一项记录内容"
            );
        }
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "健康日志已被其他请求修改");
    }

    private static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间必须早于结束时间"
            );
        }
    }

    private static String nullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
