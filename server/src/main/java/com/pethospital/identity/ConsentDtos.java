package com.pethospital.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ConsentDtos {
    private ConsentDtos() {
    }

    public record PolicyDocumentView(
        ConsentPurpose purpose,
        String policyVersion,
        String noticeHash,
        String text
    ) {
    }

    public record PoliciesView(List<PolicyDocumentView> items) {
        public PoliciesView {
            items = List.copyOf(items);
        }
    }

    public record ConsentCommand(
        @NotNull ConsentPurpose purpose,
        @NotBlank @Size(max = 32) String policyVersion,
        @NotBlank
        @Pattern(regexp = "[0-9a-f]{64}", message = "noticeHash必须是64位小写SHA-256")
        String noticeHash,
        @NotNull ConsentAction action
    ) {
    }

    public record ConsentStatusView(
        ConsentPurpose purpose,
        String currentPolicyVersion,
        String currentNoticeHash,
        ConsentAction action,
        String policyVersion,
        String noticeHash,
        Instant actedAt,
        boolean effective
    ) {
    }

    public record ConsentListView(List<ConsentStatusView> items) {
        public ConsentListView {
            items = List.copyOf(items);
        }
    }
}
