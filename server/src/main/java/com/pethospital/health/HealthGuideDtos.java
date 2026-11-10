package com.pethospital.health;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class HealthGuideDtos {
    private HealthGuideDtos() {
    }

    public record GuideCreateRequest(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String categoryCode,
        @NotBlank @Size(max = 100_000) String body,
        @Positive Long coverFileId,
        @NotBlank @Size(max = 1000) String sourceNote
    ) {
    }

    public record GuidePatchRequest(
        @Size(min = 1, max = 255) String title,
        @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String categoryCode,
        @Size(min = 1, max = 100_000) String body,
        @Positive Long coverFileId,
        @Size(min = 1, max = 1000) String sourceNote,
        @NotNull Integer version
    ) {
    }

    public record GuidePublishRequest(@NotNull @Min(0) Integer version) {
    }

    public record GuideOfflineRequest(
        @NotNull @Min(0) Integer version,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record GuideView(
        String guideId,
        String title,
        String speciesCode,
        String categoryCode,
        String body,
        String coverFileId,
        String sourceNote,
        String status,
        String editorUserId,
        int version,
        String publishedAt,
        String createdAt,
        String updatedAt
    ) {
    }

    public record GuidePage(List<GuideView> items, int page, int pageSize, long total) {
        public GuidePage {
            items = List.copyOf(items);
        }
    }

    public record PublicGuideSummary(
        String guideId,
        String title,
        String speciesCode,
        String categoryCode,
        String coverFileId,
        String sourceNote,
        String publishedAt
    ) {
    }

    public record PublicGuideDetail(
        String guideId,
        String title,
        String speciesCode,
        String categoryCode,
        String body,
        String coverFileId,
        String sourceNote,
        String publishedAt
    ) {
    }

    public record PublicGuidePage(List<PublicGuideSummary> items, int page, int pageSize, long total) {
        public PublicGuidePage {
            items = List.copyOf(items);
        }
    }
}
