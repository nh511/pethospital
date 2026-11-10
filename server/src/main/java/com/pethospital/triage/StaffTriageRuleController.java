package com.pethospital.triage;

import com.pethospital.shared.ApiResponse;
import com.pethospital.triage.TriageRuleDtos.RuleCreateRequest;
import com.pethospital.triage.TriageRuleDtos.RuleDisableRequest;
import com.pethospital.triage.TriageRuleDtos.RuleEnableRequest;
import com.pethospital.triage.TriageRuleDtos.RulePage;
import com.pethospital.triage.TriageRuleDtos.RulePatchRequest;
import com.pethospital.triage.TriageRuleDtos.RulePreviewRequest;
import com.pethospital.triage.TriageRuleDtos.RulePreviewView;
import com.pethospital.triage.TriageRuleDtos.RuleReviewRequest;
import com.pethospital.triage.TriageRuleDtos.RuleSubmitReviewRequest;
import com.pethospital.triage.TriageRuleDtos.RuleView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/staff/triage-rules")
public class StaffTriageRuleController {
    private final TriageRuleService rules;
    private final TriageRuleRuntimeService runtime;

    public StaffTriageRuleController(TriageRuleService rules, TriageRuleRuntimeService runtime) {
        this.rules = rules;
        this.runtime = runtime;
    }

    @GetMapping
    ResponseEntity<ApiResponse<RulePage>> list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 64) String ruleCode,
        @RequestParam(required = false) @Size(max = 32) String speciesCode,
        @RequestParam(required = false) @Size(max = 16) String reviewStatus
    ) {
        return ok(rules.list(page, pageSize, ruleCode, speciesCode, reviewStatus));
    }

    @GetMapping("/{ruleId}")
    ResponseEntity<ApiResponse<RuleView>> get(@PathVariable @Positive long ruleId) {
        return ok(rules.get(ruleId));
    }

    @PostMapping
    ResponseEntity<ApiResponse<RuleView>> create(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RuleCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(rules.create(request, operationKey)));
    }

    @PatchMapping("/{ruleId}")
    ResponseEntity<ApiResponse<RuleView>> patch(
        @PathVariable @Positive long ruleId,
        @Valid @RequestBody RulePatchRequest request
    ) {
        return ok(rules.patch(ruleId, request));
    }

    @PostMapping("/{ruleId}/submit-review")
    ResponseEntity<ApiResponse<RuleView>> submitReview(
        @PathVariable @Positive long ruleId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RuleSubmitReviewRequest request
    ) {
        return ok(rules.submitReview(ruleId, request, operationKey));
    }

    @PostMapping("/{ruleId}/review")
    ResponseEntity<ApiResponse<RuleView>> review(
        @PathVariable @Positive long ruleId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RuleReviewRequest request
    ) {
        return ok(rules.review(ruleId, request, operationKey));
    }

    @PostMapping("/{ruleId}/enable")
    ResponseEntity<ApiResponse<RuleView>> enable(
        @PathVariable @Positive long ruleId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RuleEnableRequest request
    ) {
        return ok(rules.enable(ruleId, request, operationKey));
    }

    @PostMapping("/{ruleId}/disable")
    ResponseEntity<ApiResponse<RuleView>> disable(
        @PathVariable @Positive long ruleId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RuleDisableRequest request
    ) {
        return ok(rules.disable(ruleId, request, operationKey));
    }

    @PostMapping("/test")
    ResponseEntity<ApiResponse<RulePreviewView>> preview(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody RulePreviewRequest request
    ) {
        return ok(runtime.preview(request));
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
