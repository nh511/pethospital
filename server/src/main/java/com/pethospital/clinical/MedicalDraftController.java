package com.pethospital.clinical;

import com.pethospital.clinical.MedicalDraftDtos.DraftResult;
import com.pethospital.clinical.MedicalDraftDtos.CompleteRequest;
import com.pethospital.clinical.MedicalDraftDtos.Completed;
import com.pethospital.clinical.MedicalDraftDtos.SaveRequest;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping(MedicalDraftController.APPOINTMENTS + "/{appointmentId}")
public class MedicalDraftController {
    static final String APPOINTMENTS = "/api/v1/staff/appointments";
    private final MedicalDraftService drafts;

    public MedicalDraftController(MedicalDraftService drafts) {
        this.drafts = drafts;
    }

    @GetMapping("/medical-draft")
    ResponseEntity<ApiResponse<DraftResult>> get(@PathVariable @Positive long appointmentId) {
        return respond(drafts.get(appointmentId));
    }

    @PutMapping("/medical-draft")
    ResponseEntity<ApiResponse<DraftResult>> save(@PathVariable @Positive long appointmentId,
                                                 @Valid @RequestBody SaveRequest request) {
        return respond(drafts.save(appointmentId, request));
    }

    @PostMapping("/complete")
    ResponseEntity<ApiResponse<Completed>> complete(
        @PathVariable @Positive long appointmentId,
        @RequestHeader("Idempotency-Key") @Pattern(regexp = "[A-Za-z0-9._:-]{16,64}") String key,
        @Valid @RequestBody CompleteRequest request
    ) {
        return respond(drafts.complete(appointmentId, key, request));
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(T result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(result));
    }
}
