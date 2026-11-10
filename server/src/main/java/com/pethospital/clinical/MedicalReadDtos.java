package com.pethospital.clinical;

import com.pethospital.clinical.MedicalDraftDtos.MedicalRecordInput;
import java.time.Instant;
import java.util.List;

public final class MedicalReadDtos {
    private MedicalReadDtos() { }

    public record Summary(
        String id, String appointmentId, String petId, String doctorId, String doctorName,
        String status, Instant submittedAt, int version, int revisionNo
    ) { }

    public record Content(MedicalRecordInput medicalRecord, List<String> attachmentIds) { }

    public record Detail(Summary record, Content content) { }

    public record Version(
        String id, int revisionNo, String doctorName, String reason, Instant createdAt,
        Content before, Content after, List<String> changedFields
    ) { }

    public record Page<T>(List<T> items, int page, int pageSize, long total) { }
}
