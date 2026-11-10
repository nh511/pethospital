package com.pethospital.clinical;

import com.pethospital.clinical.MedicalReadDtos.Detail;
import com.pethospital.clinical.MedicalReadDtos.Page;
import com.pethospital.clinical.MedicalReadDtos.Summary;
import com.pethospital.clinical.MedicalReadDtos.Version;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
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
@RequestMapping("/api/v1")
public class MedicalReadController {
    private final MedicalReadService records;

    public MedicalReadController(MedicalReadService records) {
        this.records = records;
    }

    @GetMapping("/pets/{petId}/medical-records")
    ResponseEntity<ApiResponse<Page<Summary>>> ownerList(
        @PathVariable @Positive long petId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(records.ownerList(petId, page, pageSize));
    }

    @GetMapping("/staff/pets/{petId}/medical-records")
    ResponseEntity<ApiResponse<Page<Summary>>> staffList(
        @PathVariable @Positive long petId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(records.staffList(petId, page, pageSize));
    }

    @GetMapping("/medical-records/{recordId}")
    ResponseEntity<ApiResponse<Detail>> detail(@PathVariable @Positive long recordId) {
        return response(records.detail(recordId));
    }

    @GetMapping("/medical-records/{recordId}/versions")
    ResponseEntity<ApiResponse<Page<Version>>> versions(
        @PathVariable @Positive long recordId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return response(records.versions(recordId, page, pageSize));
    }

    private static <T> ResponseEntity<ApiResponse<T>> response(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
