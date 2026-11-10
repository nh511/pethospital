package com.pethospital.shared;

import com.pethospital.shared.OperationsLogDtos.AiCallLogPage;
import com.pethospital.shared.OperationsLogDtos.AuditLogPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
public class OperationsLogController {
    private final OperationsLogService logs;

    public OperationsLogController(OperationsLogService logs) {
        this.logs = logs;
    }

    @GetMapping("/audit-logs")
    ResponseEntity<ApiResponse<AuditLogPage>> auditLogs(
        @RequestParam(required = false) @Positive Long actorUserId,
        @RequestParam(required = false) @Size(max = 64) String resourceType,
        @RequestParam(required = false) @Positive Long resourceId,
        @RequestParam(required = false) @Size(max = 128) String action,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
            logs.auditLogs(actorUserId, resourceType, resourceId, action, from, to, page, pageSize)
        ));
    }

    @GetMapping("/ai/call-logs")
    ResponseEntity<ApiResponse<AiCallLogPage>> aiCallLogs(
        @RequestParam(required = false) @Size(max = 16) String callKind,
        @RequestParam(required = false) @Size(max = 64) String provider,
        @RequestParam(required = false) @Size(max = 128) String model,
        @RequestParam(required = false) @Size(max = 24) String status,
        @RequestParam(required = false) Boolean includeContext,
        @RequestParam(required = false) String from,
        @RequestParam(required = false) String to,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.ok(
            logs.aiCallLogs(callKind, provider, model, status, includeContext, from, to, page, pageSize)
        ));
    }
}
