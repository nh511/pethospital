package com.pethospital.pet;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.persistence.CrossTableRelationGuard.FileBusinessType;
import com.pethospital.pet.PetDtos.PetCreateRequest;
import com.pethospital.pet.PetDtos.PetArchiveRequest;
import com.pethospital.pet.PetDtos.PetArchiveResult;
import com.pethospital.pet.PetDtos.ClinicalSummary;
import com.pethospital.pet.PetDtos.MedicationSummary;
import com.pethospital.pet.PetDtos.OwnerReported;
import com.pethospital.pet.PetDtos.PetDetail;
import com.pethospital.pet.PetDtos.PetPage;
import com.pethospital.pet.PetDtos.PetUpdateRequest;
import com.pethospital.pet.PetDtos.PetView;
import com.pethospital.pet.PetDtos.SubmittedHistory;
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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final PetMapper pets;
    private final ActivePetGuard activePets;
    private final PetArchiveDependencyQuery archiveDependencies;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final FileBindingService fileBindings;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PetService(
        PetMapper pets,
        ActivePetGuard activePets,
        PetArchiveDependencyQuery archiveDependencies,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        FileBindingService fileBindings,
        AuditService audit,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.pets = pets;
        this.activePets = activePets;
        this.archiveDependencies = archiveDependencies;
        this.identities = identities;
        this.idempotency = idempotency;
        this.fileBindings = fileBindings;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public PetView create(PetCreateRequest request, String operationKey) {
        CurrentIdentity actor = requireMiniUser();
        validateBirthDate(request.birthDate());
        PetCreateRequest normalized = normalized(request);
        var command = new IdempotencyCommand(
            actor.userId(),
            ActorType.USER,
            "PET_CREATE",
            "PET",
            "POST",
            "/api/v1/pets",
            operationKey,
            normalized,
            null,
            null,
            null
        );
        return idempotency.execute(command, PetView.class, () -> insert(actor.userId(), normalized));
    }

    @Transactional(readOnly = true)
    public PetPage list(String status, int page, int pageSize) {
        CurrentIdentity actor = requireMiniUser();
        String normalizedStatus = normalizeStatus(status);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        return new PetPage(
            pets.listOwned(actor.userId(), normalizedStatus, pageSize, offset),
            page,
            pageSize,
            pets.countOwned(actor.userId(), normalizedStatus)
        );
    }

    @Transactional(readOnly = true)
    public PetDetail detail(long petId) {
        CurrentIdentity actor = requireMiniUser();
        PetMapper.PetRow row = ownedRow(petId, actor.userId(), false);
        return detail(row);
    }

    @Transactional
    public PetDetail patch(long petId, PetUpdateRequest request) {
        CurrentIdentity actor = requireMiniUser();
        validateBirthDate(request.birthDate());
        PetUpdateRequest normalized = normalized(request);
        PetMapper.PetRow before = ownedRow(petId, actor.userId(), true);
        if (before.version() != request.version()) {
            throw versionConflict();
        }
        if (pets.updateOwned(petId, actor.userId(), normalized) != 1) {
            throw versionConflict();
        }
        fileBindings.replaceMutable(
            FileBusinessType.PET,
            petId,
            normalized.avatarFileId() == null ? List.of() : List.of(normalized.avatarFileId())
        );
        PetMapper.PetRow after = ownedRow(petId, actor.userId(), false);
        audit.append(new AuditEvent(
            actor.userId(),
            ActorType.USER,
            "PET_UPDATE",
            new ResourceReference("PET", petId),
            objectMapper.valueToTree(snapshot(before)),
            objectMapper.valueToTree(snapshot(after)),
            null,
            null,
            null,
            AuditResult.SUCCESS,
            null
        ));
        return detail(after);
    }

    @Transactional
    public PetArchiveResult archive(long petId, PetArchiveRequest request, String operationKey) {
        CurrentIdentity actor = requireMiniUser();
        PetArchiveRequest normalized = new PetArchiveRequest(nullableText(request.reason()), request.version());
        var command = new IdempotencyCommand(
            actor.userId(),
            ActorType.USER,
            "PET_ARCHIVE",
            "PET",
            "POST",
            "/api/v1/pets/" + petId + "/archive",
            operationKey,
            normalized,
            null,
            normalized.reason(),
            null
        );
        return idempotency.execute(
            command,
            PetArchiveResult.class,
            () -> archiveLocked(petId, actor.userId(), normalized)
        );
    }

    private IdempotentResult<PetArchiveResult> archiveLocked(
        long petId,
        long ownerUserId,
        PetArchiveRequest request
    ) {
        PetMapper.PetRow before = activePets.lockOwnedActive(petId, ownerUserId);
        if (before.version() != request.version()) {
            throw versionConflict();
        }
        PetArchiveDependencyQuery.ArchiveDependencies dependencies = archiveDependencies.lockForPet(petId);
        if (dependencies.totalCount() > 0) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "PET_ARCHIVE_BLOCKED",
                "宠物仍有未终结预约或处理中问诊",
                Map.of(
                    "pendingAppointmentCount", dependencies.unfinishedAppointmentCount(),
                    "processingMessageCount", dependencies.processingMessageCount()
                )
            );
        }
        if (pets.archiveOwned(petId, ownerUserId, request.version()) != 1) {
            throw versionConflict();
        }
        PetArchiveResult result = new PetArchiveResult(
            Long.toString(petId),
            "ARCHIVED",
            request.version() + 1
        );
        return new IdempotentResult<>(
            result,
            petId,
            objectMapper.valueToTree(Map.of("status", result.status(), "version", result.version()))
        );
    }

    private IdempotentResult<PetView> insert(long ownerUserId, PetCreateRequest request) {
        long petId = IdWorker.getId();
        pets.insert(petId, ownerUserId, request);
        fileBindings.replaceMutable(
            FileBusinessType.PET,
            petId,
            request.avatarFileId() == null ? List.of() : List.of(request.avatarFileId())
        );
        PetView view = pets.findOwned(petId, ownerUserId);
        if (view == null) {
            throw new IllegalStateException("宠物创建后无法读取");
        }
        return new IdempotentResult<>(
            view,
            petId,
            objectMapper.valueToTree(Map.of(
                "name", view.name(),
                "speciesCode", view.speciesCode(),
                "status", view.status()
            ))
        );
    }

    private CurrentIdentity requireMiniUser() {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问宠物资料");
        }
        return actor;
    }

    private void validateBirthDate(LocalDate birthDate) {
        if (birthDate != null && birthDate.isAfter(LocalDate.now(clock.withZone(BUSINESS_ZONE)))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BIRTH_DATE", "宠物生日不能晚于今天");
        }
    }

    private static PetCreateRequest normalized(PetCreateRequest request) {
        return new PetCreateRequest(
            request.name().strip(),
            request.speciesCode(),
            nullableText(request.breed()),
            request.sex(),
            request.birthDate(),
            request.neutered(),
            request.avatarFileId(),
            nullableText(request.ownerHealthNotes())
        );
    }

    private static PetUpdateRequest normalized(PetUpdateRequest request) {
        return new PetUpdateRequest(
            request.name().strip(),
            request.speciesCode(),
            nullableText(request.breed()),
            request.sex(),
            request.birthDate(),
            request.neutered(),
            request.avatarFileId(),
            nullableText(request.ownerHealthNotes()),
            request.version()
        );
    }

    private PetMapper.PetRow ownedRow(long petId, long ownerUserId, boolean lock) {
        PetMapper.PetRow row = pets.findOwnedRow(petId, ownerUserId, lock);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        return row;
    }

    private PetDetail detail(PetMapper.PetRow row) {
        PetView base = PetMapper.view(row);
        return new PetDetail(
            base.id(),
            base.name(),
            base.speciesCode(),
            base.breed(),
            base.sex(),
            base.birthDate(),
            base.neutered(),
            base.avatarFileId(),
            base.status(),
            base.version(),
            base.createdAt(),
            base.updatedAt(),
            new OwnerReported(row.ownerHealthNotes()),
            clinicalSummary(row.id())
        );
    }

    ClinicalSummary clinicalSummary(long petId) {
        return new ClinicalSummary(pets.latestWeight(petId), submittedHistory(pets.latestSubmittedClinical(petId)));
    }

    private SubmittedHistory submittedHistory(PetMapper.SubmittedClinicalRow row) {
        if (row == null) {
            return null;
        }
        try {
            JsonNode content = objectMapper.readTree(row.contentJson());
            List<MedicationSummary> medications = new ArrayList<>();
            JsonNode medicationNodes = content.path("medications");
            if (medicationNodes.isArray()) {
                for (JsonNode medication : medicationNodes) {
                    if (medication.isObject()) {
                        medications.add(new MedicationSummary(
                            text(medication, "name"),
                            firstText(medication, "dosage", "dose"),
                            text(medication, "frequency"),
                            firstText(medication, "duration", "course")
                        ));
                    }
                }
            }
            return new SubmittedHistory(
                textList(content.path("allergies")),
                textList(content.path("chronicConditions")),
                medications,
                row.submittedAt(),
                Long.toString(row.recordId()),
                row.revisionNo()
            );
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("已提交病历JSON无法解析", exception);
        }
    }

    private static List<String> textList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual() && !item.textValue().isBlank()) {
                values.add(item.textValue().strip());
            }
        }
        return List.copyOf(values);
    }

    private static String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return value == null ? text(node, second) : value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }

    private static Map<String, Object> snapshot(PetMapper.PetRow row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", row.name());
        value.put("speciesCode", row.speciesCode());
        value.put("breed", row.breed());
        value.put("sex", row.sex());
        value.put("birthDate", row.birthDate());
        value.put("neutered", row.neutered());
        value.put("avatarFileId", row.avatarFileId());
        value.put("ownerHealthNotes", row.ownerHealthNotes());
        value.put("status", row.status());
        value.put("version", row.version());
        return value;
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "宠物资料已被其他请求修改");
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.strip().toUpperCase();
        if (!normalized.equals("ACTIVE") && !normalized.equals("ARCHIVED")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PET_STATUS", "宠物状态筛选不合法");
        }
        return normalized;
    }

    private static String nullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }
}
