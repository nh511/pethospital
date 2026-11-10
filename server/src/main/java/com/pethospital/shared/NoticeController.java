package com.pethospital.shared;

import com.pethospital.shared.NoticeDtos.NoticePage;
import com.pethospital.shared.NoticeDtos.NoticeReadView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/notices")
public class NoticeController {
    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    @GetMapping
    ResponseEntity<ApiResponse<NoticePage>> list(
        @RequestParam(required = false) Boolean unread,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(notices.list(unread, page, pageSize)));
    }

    @PostMapping("/{noticeId}/read")
    ResponseEntity<ApiResponse<NoticeReadView>> read(
        @PathVariable @Positive long noticeId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(notices.read(noticeId, operationKey)));
    }
}
