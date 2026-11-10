package com.pethospital.pet;

import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.DoctorScope;
import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.pet.StaffWorkspaceDtos.StaffDashboard;
import com.pethospital.pet.StaffWorkspaceDtos.StaffPetDetail;
import com.pethospital.pet.StaffWorkspaceDtos.StaffPetPage;
import com.pethospital.pet.StaffWorkspaceQuery.StaffPetRow;
import com.pethospital.shared.BusinessException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StaffWorkspaceService {
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Asia/Shanghai");

    private final ResourceAccessPolicy access;
    private final StaffWorkspaceQuery workspace;
    private final PetService pets;
    private final PetHealthLogService healthLogs;

    public StaffWorkspaceService(
        ResourceAccessPolicy access, StaffWorkspaceQuery workspace, PetService pets, PetHealthLogService healthLogs
    ) {
        this.access = access;
        this.workspace = workspace;
        this.pets = pets;
        this.healthLogs = healthLogs;
    }

    public StaffDashboard dashboard() {
        DoctorScope scope = access.requireDoctorScope();
        LocalDate today = scope.now().atZone(HOSPITAL_ZONE).toLocalDate();
        Instant from = today.atStartOfDay(HOSPITAL_ZONE).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(HOSPITAL_ZONE).toInstant();
        return new StaffDashboard(today, HOSPITAL_ZONE.getId(), workspace.today(scope, from, to),
            workspace.pending(scope), workspace.recentCompleted(scope));
    }

    public StaffPetPage list(int page, int pageSize) {
        DoctorScope scope = access.requireDoctorScope();
        long offset = (page - 1L) * pageSize;
        return new StaffPetPage(workspace.listPets(scope, pageSize, offset), page, pageSize, workspace.countPets(scope));
    }

    public StaffPetDetail detail(long petId) {
        StaffPetRow row = requirePet(petId);
        return new StaffPetDetail(row.pet(), pets.clinicalSummary(petId));
    }

    public HealthLogPage healthLogs(long petId, Instant from, Instant to, int page, int pageSize) {
        StaffPetRow row = requirePet(petId);
        return healthLogs.listOwned(petId, row.ownerId(), from, to, page, pageSize);
    }

    public long requirePetOwner(long petId) {
        return requirePet(petId).ownerId();
    }

    private StaffPetRow requirePet(long petId) {
        StaffPetRow row = workspace.findPet(access.requireDoctorScope(), petId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        return row;
    }
}
