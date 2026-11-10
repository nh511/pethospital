package com.pethospital.clinical;

import com.pethospital.clinical.MedicalDraftDtos.Revised;
import com.pethospital.clinical.MedicalDraftDtos.RevisionRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(MedicalRevisionController.RECORDS)
public class MedicalRevisionController {
    static final String RECORDS = "/api/v1/staff/medical-records";
    private final MedicalDraftService records;

    public MedicalRevisionController(MedicalDraftService records) {
        this.records = records;
    }

    @PostMapping("/{recordId}/revisions")
    ResponseEntity<ApiResponse<Revised>> revise(
        @PathVariable @Positive long recordId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody RevisionRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(records.revise(recordId, key, request)));
    }
}
