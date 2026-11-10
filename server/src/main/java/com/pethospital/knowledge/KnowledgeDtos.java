package com.pethospital.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {
    private KnowledgeDtos() { }

    public record CreateDocument(
        @NotBlank @Size(max = 255) String title,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String categoryCode,
        @Size(max = 20) List<@NotBlank @Size(max = 64) String> tags
    ) { }

    public record PatchDocument(
        @Size(min = 1, max = 255) String title,
        @Pattern(regexp = "[A-Za-z0-9_]{2,32}") String speciesCode,
        @Pattern(regexp = "[A-Za-z0-9_]{2,64}") String categoryCode,
        @Size(max = 20) List<@NotBlank @Size(max = 64) String> tags,
        @NotNull @Min(0) Integer version
    ) { }

    public record CreateVersion(
        @NotBlank @Size(max = 100_000) String content,
        @NotBlank @Size(max = 255) String sourceTitle,
        @Size(max = 1000) String sourceUrl,
        @Positive Long sourceFileId
    ) { }

    public record PatchVersion(
        @Size(min = 1, max = 100_000) String content,
        @Size(min = 1, max = 255) String sourceTitle,
        @Size(max = 1000) String sourceUrl,
        @Positive Long sourceFileId,
        @NotNull @Min(0) Integer version
    ) { }

    public record SubmitReview(@NotNull @Min(0) Integer version) { }

    public record ReviewVersion(
        @NotNull @Min(0) Integer version,
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @Size(max = 500) String reviewNote
    ) { }

    public record ReviewReceipt(
        String knowledgeVersionId, String documentId, String reviewStatus, String reviewerUserId, Instant reviewedAt, int version
    ) { }

    public record DocumentView(
        String documentId, String title, String speciesCode, String categoryCode, List<String> tags,
        String status, String activeVersionId, String creatorUserId, int version, Instant createdAt, Instant updatedAt
    ) { }

    public record VersionSummary(
        String knowledgeVersionId, String documentId, int versionNo, String sourceTitle, String sourceUrl,
        String sourceFileId, String contentHash, String reviewStatus, String reviewerUserId, String reviewNote,
        Instant reviewedAt, Instant publishedAt, int version, Instant createdAt, Instant updatedAt
    ) { }

    public record VersionDetail(VersionSummary metadata, String content) { }

    public record CreatedDocument(String documentId, int version) { }

    public record CreatedVersion(String documentId, String knowledgeVersionId, int versionNo, int version) { }

    public record Page<T>(List<T> items, int page, int pageSize, long total) { }
}
