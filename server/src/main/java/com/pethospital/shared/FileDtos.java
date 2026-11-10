package com.pethospital.shared;

import java.util.Locale;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;

public final class FileDtos {
    private FileDtos() {
    }

    public enum FilePurpose {
        AVATAR,
        PET,
        HEALTH_LOG,
        MEDICAL,
        KNOWLEDGE,
        GUIDE,
        QUALIFICATION;

        public static FilePurpose parse(String value) {
            try {
                return valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILE_PURPOSE",
                    "文件用途不受支持"
                );
            }
        }
    }

    public enum FileStatus {
        QUARANTINED,
        READY,
        REJECTED,
        DELETED
    }

    public record FileView(
        String id,
        String originalName,
        String mimeType,
        String sizeBytes,
        String purpose,
        String status
    ) {
    }

    public record FileUploadResult(
        FileView file,
        String rejectionCode,
        String rejectionMessage
    ) {
        public boolean rejected() {
            return FileStatus.REJECTED.name().equals(file.status());
        }
    }

    public record DownloadTicketRequest(
        String businessType,
        @Pattern(regexp = "^[1-9][0-9]*$") String businessId
    ) {
    }

    public record DownloadTicketView(String url, String expiresAt) {
    }

    public record DiscardRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record DiscardView(String id, String status) {
    }
}
