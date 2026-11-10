package com.pethospital.identity;

import com.pethospital.identity.ConsentDtos.ConsentCommand;
import com.pethospital.identity.ConsentDtos.ConsentListView;
import com.pethospital.identity.ConsentDtos.ConsentStatusView;
import com.pethospital.identity.ConsentDtos.PoliciesView;
import com.pethospital.shared.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ConsentController {
    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @GetMapping("/policies/current")
    ResponseEntity<ApiResponse<PoliciesView>> currentPolicies() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(consents.currentPolicies()));
    }

    @GetMapping("/me/consents")
    ResponseEntity<ApiResponse<ConsentListView>> currentConsents() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(consents.currentConsents()));
    }

    @PostMapping("/me/consents")
    ResponseEntity<ApiResponse<ConsentStatusView>> append(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
        @Valid @RequestBody ConsentCommand command
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(consents.append(command, idempotencyKey)));
    }
}
