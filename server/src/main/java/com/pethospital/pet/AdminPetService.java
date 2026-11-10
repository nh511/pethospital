package com.pethospital.pet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.pet.AdminPetDtos.AdminPetDetail;
import com.pethospital.pet.AdminPetDtos.AdminPetOwner;
import com.pethospital.pet.AdminPetDtos.AdminPetPage;
import com.pethospital.pet.AdminPetDtos.AdminPetPatchRequest;
import com.pethospital.pet.AdminPetDtos.AdminPetSummary;
import com.pethospital.pet.PetDtos.OwnerReported;
import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.AuditEvent;
import com.pethospital.shared.AuditDtos.AuditResult;
import com.pethospital.shared.AuditDtos.ResourceReference;
import com.pethospital.shared.AuditService;
import com.pethospital.shared.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminPetService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final PetMapper pets;
    private final PetHealthLogMapper healthLogs;
    private final CurrentIdentityProvider identities;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AdminPetService(
        PetMapper pets,
        PetHealthLogMapper healthLogs,
        CurrentIdentityProvider identities,
        AuditService audit,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.pets = pets;
        this.healthLogs = healthLogs;
        this.identities = identities;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AdminPetPage list(int page, int pageSize) {
        requireAdmin();
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        return new AdminPetPage(
            pets.listAdmin(pageSize, offset).stream().map(this::summary).toList(),
            page,
            pageSize,
            pets.countAdmin()
        );
    }

    @Transactional(readOnly = true)
    public AdminPetDetail detail(long petId) {
        requireAdmin();
        return detail(adminRow(petId, false));
    }

    @Transactional
    public AdminPetDetail patch(long petId, AdminPetPatchRequest request) {
        CurrentIdentity actor = requireAdmin();
        validateBirthDate(request.birthDate());
        AdminPetPatchRequest normalized = normalized(request);
        PetMapper.AdminPetRow before = adminRow(petId, true);
        if (before.pet().version() != normalized.version()) {
            throw versionConflict();
        }
        if (pets.updateAdmin(petId, normalized) != 1) {
            throw versionConflict();
        }
        PetMapper.AdminPetRow after = adminRow(petId, false);
        audit.append(new AuditEvent(
            actor.userId(),
            ActorType.ADMIN,
            "ADMIN_PET_CORRECT",
            new ResourceReference("PET", petId),
            objectMapper.valueToTree(snapshot(before.pet())),
            objectMapper.valueToTree(snapshot(after.pet())),
            normalized.reason(),
            null,
            null,
            AuditResult.SUCCESS,
            null
        ));
        return detail(after);
    }

    @Transactional
    public HealthLogPage healthLogs(long petId, Instant from, Instant to, int page, int pageSize) {
        CurrentIdentity actor = requireAdmin();
        validateRange(from, to);
        adminRow(petId, false);
        long offset = Math.multiplyExact((long) page - 1L, pageSize);
        var items = healthLogs.listAdmin(petId, from, to, pageSize, offset);
        long total = healthLogs.countAdmin(petId, from, to);
        Map<String, Object> readContext = new LinkedHashMap<>();
        readContext.put("from", from);
        readContext.put("to", to);
        readContext.put("page", page);
        readContext.put("pageSize", pageSize);
        readContext.put("resultCount", items.size());
        audit.append(new AuditEvent(
            actor.userId(),
            ActorType.ADMIN,
            "ADMIN_PET_HEALTH_LOG_READ",
            new ResourceReference("PET", petId),
            null,
            objectMapper.valueToTree(readContext),
            null,
            null,
            null,
            AuditResult.SUCCESS,
            null
        ));
        return new HealthLogPage(items, page, pageSize, total);
    }

    private CurrentIdentity requireAdmin() {
        CurrentIdentity actor = identities.require();
        if (!"WEB".equals(actor.clientType()) || !actor.roles().contains("ADMIN")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问后台宠物资料");
        }
        return actor;
    }

    private PetMapper.AdminPetRow adminRow(long petId, boolean lock) {
        PetMapper.AdminPetRow row = pets.findAdminRow(petId, lock);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        return row;
    }

    private AdminPetSummary summary(PetMapper.AdminPetRow row) {
        PetMapper.PetRow pet = row.pet();
        return new AdminPetSummary(
            Long.toString(pet.id()), pet.name(), pet.speciesCode(), pet.breed(), pet.sex(), pet.birthDate(),
            pet.neutered(), pet.status(), pet.version(), owner(row), instant(pet.createdAt()), instant(pet.updatedAt())
        );
    }

    private AdminPetDetail detail(PetMapper.AdminPetRow row) {
        PetMapper.PetRow pet = row.pet();
        return new AdminPetDetail(
            Long.toString(pet.id()), pet.name(), pet.speciesCode(), pet.breed(), pet.sex(), pet.birthDate(),
            pet.neutered(), pet.avatarFileId() == null ? null : Long.toString(pet.avatarFileId()),
            pet.status(), pet.version(), owner(row), new OwnerReported(pet.ownerHealthNotes()),
            instant(pet.createdAt()), instant(pet.updatedAt())
        );
    }

    private AdminPetOwner owner(PetMapper.AdminPetRow row) {
        return new AdminPetOwner(Long.toString(row.pet().ownerUserId()), mask(row.ownerDisplayName()));
    }

    private void validateBirthDate(LocalDate birthDate) {
        if (birthDate != null && birthDate.isAfter(LocalDate.now(clock.withZone(BUSINESS_ZONE)))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BIRTH_DATE", "宠物生日不能晚于今天");
        }
    }

    private static void validateRange(Instant from, Instant to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "健康日志时间范围无效");
        }
    }

    private static AdminPetPatchRequest normalized(AdminPetPatchRequest request) {
        return new AdminPetPatchRequest(
            request.name().strip(), request.speciesCode(), nullableText(request.breed()), request.sex(),
            request.birthDate(), request.neutered(), nullableText(request.ownerHealthNotes()),
            request.version(), request.reason().strip()
        );
    }

    private static Map<String, Object> snapshot(PetMapper.PetRow row) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ownerUserId", Long.toString(row.ownerUserId()));
        value.put("name", row.name());
        value.put("speciesCode", row.speciesCode());
        value.put("breed", row.breed());
        value.put("sex", row.sex());
        value.put("birthDate", row.birthDate());
        value.put("neutered", row.neutered());
        value.put("ownerHealthNotes", row.ownerHealthNotes());
        value.put("status", row.status());
        value.put("version", row.version());
        return value;
    }

    private static String mask(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "***";
        }
        int first = displayName.codePointAt(0);
        return new String(Character.toChars(first)) + "***";
    }

    private static String instant(java.sql.Timestamp value) {
        return value.toInstant().toString();
    }

    private static String nullableText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static BusinessException versionConflict() {
        return new BusinessException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "宠物资料已被其他请求修改");
    }
}
