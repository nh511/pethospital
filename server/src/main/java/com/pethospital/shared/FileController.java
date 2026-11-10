package com.pethospital.shared;

import com.pethospital.shared.FileDtos.FileView;
import com.pethospital.shared.FileDtos.DownloadTicketRequest;
import com.pethospital.shared.FileDtos.DownloadTicketView;
import com.pethospital.shared.FileDtos.DiscardRequest;
import com.pethospital.shared.FileDtos.DiscardView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/files")
public class FileController {
    private final FileService files;

    public FileController(FileService files) {
        this.files = files;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ApiResponse<FileView>> upload(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @RequestPart("file") MultipartFile file,
        @RequestPart("purpose") @Size(min = 2, max = 32) String purpose
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(files.upload(file, purpose, operationKey)));
    }

    @GetMapping("/{fileId}")
    ResponseEntity<ApiResponse<FileView>> detail(@PathVariable @Positive long fileId) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(files.detail(fileId)));
    }

    @PostMapping("/{fileId}/download-ticket")
    ResponseEntity<ApiResponse<DownloadTicketView>> downloadTicket(
        @PathVariable @Positive long fileId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody DownloadTicketRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(files.downloadTicket(fileId, request, operationKey)));
    }

    @PostMapping("/{fileId}/discard")
    ResponseEntity<ApiResponse<DiscardView>> discard(
        @PathVariable @Positive long fileId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String operationKey,
        @Valid @RequestBody DiscardRequest request
    ) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(ApiResponse.ok(files.discard(fileId, request, operationKey)));
    }
}
