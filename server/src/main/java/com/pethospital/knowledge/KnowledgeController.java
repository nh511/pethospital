package com.pethospital.knowledge;

import com.pethospital.knowledge.KnowledgeDtos.CreateDocument;
import com.pethospital.knowledge.KnowledgeDtos.CreateVersion;
import com.pethospital.knowledge.KnowledgeDtos.CreatedDocument;
import com.pethospital.knowledge.KnowledgeDtos.CreatedVersion;
import com.pethospital.knowledge.KnowledgeDtos.DocumentView;
import com.pethospital.knowledge.KnowledgeDtos.Page;
import com.pethospital.knowledge.KnowledgeDtos.PatchDocument;
import com.pethospital.knowledge.KnowledgeDtos.PatchVersion;
import com.pethospital.knowledge.KnowledgeDtos.ReviewReceipt;
import com.pethospital.knowledge.KnowledgeDtos.ReviewVersion;
import com.pethospital.knowledge.KnowledgeDtos.SubmitReview;
import com.pethospital.knowledge.KnowledgeDtos.VersionDetail;
import com.pethospital.knowledge.KnowledgeDtos.VersionSummary;
import com.pethospital.shared.ApiResponse;
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
@RequestMapping(KnowledgeController.BASE)
public class KnowledgeController {
    static final String BASE = "/api/v1/staff/knowledge";
    private final KnowledgeService knowledge;

    public KnowledgeController(KnowledgeService knowledge) {
        this.knowledge = knowledge;
    }

    @GetMapping("/documents")
    ResponseEntity<ApiResponse<Page<DocumentView>>> documents(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return respond(HttpStatus.OK, knowledge.documents(page, pageSize));
    }

    @PostMapping("/documents")
    ResponseEntity<ApiResponse<CreatedDocument>> createDocument(
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
        @Valid @RequestBody CreateDocument request
    ) {
        return respond(HttpStatus.CREATED, knowledge.createDocument(request, key));
    }

    @GetMapping("/documents/{documentId}")
    ResponseEntity<ApiResponse<DocumentView>> document(@PathVariable @Positive long documentId) {
        return respond(HttpStatus.OK, knowledge.document(documentId));
    }

    @PatchMapping("/documents/{documentId}")
    ResponseEntity<ApiResponse<DocumentView>> patchDocument(
        @PathVariable @Positive long documentId, @Valid @RequestBody PatchDocument request
    ) {
        return respond(HttpStatus.OK, knowledge.patchDocument(documentId, request));
    }

    @GetMapping("/documents/{documentId}/versions")
    ResponseEntity<ApiResponse<Page<VersionSummary>>> versions(
        @PathVariable @Positive long documentId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return respond(HttpStatus.OK, knowledge.versions(documentId, page, pageSize));
    }

    @PostMapping("/documents/{documentId}/versions")
    ResponseEntity<ApiResponse<CreatedVersion>> createVersion(
        @PathVariable @Positive long documentId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
        @Valid @RequestBody CreateVersion request
    ) {
        return respond(HttpStatus.CREATED, knowledge.createVersion(documentId, request, key));
    }

    @GetMapping("/versions/{knowledgeVersionId}")
    ResponseEntity<ApiResponse<VersionDetail>> version(@PathVariable @Positive long knowledgeVersionId) {
        return respond(HttpStatus.OK, knowledge.version(knowledgeVersionId));
    }

    @PatchMapping("/versions/{knowledgeVersionId}")
    ResponseEntity<ApiResponse<VersionDetail>> patchVersion(
        @PathVariable @Positive long knowledgeVersionId, @Valid @RequestBody PatchVersion request
    ) {
        return respond(HttpStatus.OK, knowledge.patchVersion(knowledgeVersionId, request));
    }

    @PostMapping("/versions/{knowledgeVersionId}/submit-review")
    ResponseEntity<ApiResponse<ReviewReceipt>> submitReview(
        @PathVariable @Positive long knowledgeVersionId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
        @Valid @RequestBody SubmitReview request
    ) {
        return respond(HttpStatus.OK, knowledge.submitReview(knowledgeVersionId, request, key));
    }

    @PostMapping("/versions/{knowledgeVersionId}/review")
    ResponseEntity<ApiResponse<ReviewReceipt>> review(
        @PathVariable @Positive long knowledgeVersionId,
        @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String key,
        @Valid @RequestBody ReviewVersion request
    ) {
        return respond(HttpStatus.OK, knowledge.review(knowledgeVersionId, request, key));
    }

    private static <T> ResponseEntity<ApiResponse<T>> respond(HttpStatus status, T data) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.ok(data));
    }
}
