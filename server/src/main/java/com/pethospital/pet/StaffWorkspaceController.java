package com.pethospital.pet;

import com.pethospital.pet.PetHealthLogDtos.HealthLogPage;
import com.pethospital.pet.StaffWorkspaceDtos.StaffDashboard;
import com.pethospital.pet.StaffWorkspaceDtos.StaffPetDetail;
import com.pethospital.pet.StaffWorkspaceDtos.StaffPetPage;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/staff")
public class StaffWorkspaceController {
    private final StaffWorkspaceService workspace;

    public StaffWorkspaceController(StaffWorkspaceService workspace) {
        this.workspace = workspace;
    }

    @GetMapping("/dashboard")
    ResponseEntity<ApiResponse<StaffDashboard>> dashboard() {
        return response(workspace.dashboard());
    }

    @GetMapping("/pets")
    ResponseEntity<ApiResponse<StaffPetPage>> pets(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(workspace.list(page, pageSize));
    }

    @GetMapping("/pets/{petId}")
    ResponseEntity<ApiResponse<StaffPetDetail>> detail(@PathVariable @Positive long petId) {
        return response(workspace.detail(petId));
    }

    @GetMapping("/pets/{petId}/health-logs")
    ResponseEntity<ApiResponse<HealthLogPage>> healthLogs(
        @PathVariable @Positive long petId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(workspace.healthLogs(petId, from, to, page, pageSize));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
