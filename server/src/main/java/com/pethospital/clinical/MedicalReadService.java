package com.pethospital.clinical;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pethospital.clinical.MedicalDraftDtos.MedicalRecordInput;
import com.pethospital.clinical.MedicalReadDtos.Content;
import com.pethospital.clinical.MedicalReadDtos.Detail;
import com.pethospital.clinical.MedicalReadDtos.Page;
import com.pethospital.clinical.MedicalReadDtos.Summary;
import com.pethospital.clinical.MedicalReadDtos.Version;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.MedicalAccess;
import com.pethospital.identity.ResourceAccessPolicy.PetAccess;
import com.pethospital.pet.StaffWorkspaceService;
import com.pethospital.shared.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class MedicalReadService {
    private static final List<String> MEDICAL_FIELDS = List.of(
        "chiefComplaint", "examination", "diagnosis", "treatment", "medications", "allergies",
        "chronicConditions", "followupAdvice", "followupAt"
    );
    private static final String SUMMARY_COLUMNS = """
        SELECT r.id,r.appointment_id,r.pet_id,r.doctor_id,d.doctor_name,r.status,r.submitted_at,r.version,r.revision_no
        """;
    private static final String RECORD_RELATIONS = """
        FROM medical_record r
        JOIN appointment a ON a.id=r.appointment_id AND a.pet_id=r.pet_id AND a.doctor_id=r.doctor_id
        JOIN pet p ON p.id=r.pet_id AND p.owner_user_id=a.user_id
        JOIN doctor_profile d ON d.id=r.doctor_id
        """;
    private static final String PET_FORMAL_RECORDS = RECORD_RELATIONS + """
        WHERE r.pet_id=? AND p.owner_user_id=? AND r.status='SUBMITTED' AND a.status='COMPLETED'
        """;
    private final ResourceAccessPolicy access;
    private final CurrentIdentityProvider identities;
    private final StaffWorkspaceService workspace;
    private final ObjectProvider<JdbcTemplate> templates;
    private final ObjectMapper json;

    public MedicalReadService(
        ResourceAccessPolicy access, CurrentIdentityProvider identities, StaffWorkspaceService workspace,
        ObjectProvider<JdbcTemplate> templates, ObjectMapper json
    ) {
        this.access = access;
        this.identities = identities;
        this.workspace = workspace;
        this.templates = templates;
        this.json = json;
    }

    public Page<Summary> ownerList(long petId, int page, int pageSize) {
        if (access.pet(petId) != PetAccess.OWNER) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }
        return list(petId, identities.require().userId(), page, pageSize);
    }

    public Page<Summary> staffList(long petId, int page, int pageSize) {
        return list(petId, workspace.requirePetOwner(petId), page, pageSize);
    }

    public Detail detail(long recordId) {
        RecordRow row = requireRecord(recordId);
        return new Detail(row.summary(), content(row.content()));
    }

    public Page<Version> versions(long recordId, int page, int pageSize) {
        RecordRow row = requireRecord(recordId);
        if (!"SUBMITTED".equals(row.summary().status())) {
            throw missingRecord();
        }
        JdbcTemplate jdbc = templates.getObject();
        List<Version> items = jdbc.query("""
            SELECT id,revision_no,before_content_json,after_content_json,reason,created_at
            FROM medical_record_version WHERE record_id=? ORDER BY revision_no DESC LIMIT ? OFFSET ?
            """, (rs, index) -> version(rs, row.summary().doctorName()), recordId, pageSize, offset(page, pageSize));
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM medical_record_version WHERE record_id=?", Long.class, recordId);
        return new Page<>(items, page, pageSize, total);
    }

    private Page<Summary> list(long petId, long ownerId, int page, int pageSize) {
        JdbcTemplate jdbc = templates.getObject();
        List<Summary> items = jdbc.query(SUMMARY_COLUMNS + PET_FORMAL_RECORDS
                + " ORDER BY r.submitted_at DESC,r.id DESC LIMIT ? OFFSET ?",
            (rs, index) -> summary(rs), petId, ownerId, pageSize, offset(page, pageSize));
        long total = jdbc.queryForObject("SELECT COUNT(*) " + PET_FORMAL_RECORDS, Long.class, petId, ownerId);
        return new Page<>(items, page, pageSize, total);
    }

    private RecordRow requireRecord(long id) {
        MedicalAccess permission = access.medicalRecord(id);
        if (permission == MedicalAccess.NONE) {
            throw missingRecord();
        }
        RecordRow row = templates.getObject().query(SUMMARY_COLUMNS + ",r.content_json " + RECORD_RELATIONS + """
            WHERE r.id=? AND ((r.status='SUBMITTED' AND a.status='COMPLETED')
                OR (r.status='DRAFT' AND a.status='IN_CONSULTATION' AND ?))
            """, rs -> rs.next() ? new RecordRow(summary(rs), rs.getString("content_json")) : null,
            id, permission == MedicalAccess.AUTHOR);
        if (row == null) {
            throw missingRecord();
        }
        return row;
    }

    private Content content(String raw) {
        try {
            if (!(json.readTree(raw) instanceof ObjectNode snapshot)) {
                throw new IllegalStateException("已保存的病历内容无法读取");
            }
            JsonNode attachmentValues = snapshot.remove("attachmentIds");
            List<String> attachments = new ArrayList<>();
            if (attachmentValues != null && attachmentValues.isArray()) {
                for (JsonNode id : attachmentValues) {
                    if (id.asText().matches("[1-9][0-9]{0,18}")) {
                        attachments.add(id.asText());
                    }
                }
            }
            snapshot.retain(MEDICAL_FIELDS);
            return new Content(json.treeToValue(snapshot, MedicalRecordInput.class), List.copyOf(attachments));
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("已保存的病历内容无法读取", invalid);
        }
    }

    private Version version(ResultSet rs, String doctorName) throws SQLException {
        String previous = rs.getString("before_content_json");
        Content before = previous == null ? null : content(previous);
        Content after = content(rs.getString("after_content_json"));
        JsonNode priorBody = before == null ? json.getNodeFactory().nullNode() : json.valueToTree(before.medicalRecord());
        JsonNode updatedBody = json.valueToTree(after.medicalRecord());
        List<String> changed = new ArrayList<>();
        for (String field : MEDICAL_FIELDS) {
            if (!priorBody.path(field).equals(updatedBody.path(field))) {
                changed.add(field);
            }
        }
        if (!(before == null ? List.of() : before.attachmentIds()).equals(after.attachmentIds())) {
            changed.add("attachmentIds");
        }
        return new Version(rs.getString("id"), rs.getInt("revision_no"), doctorName, rs.getString("reason"),
            instant(rs, "created_at"), before, after, List.copyOf(changed));
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        return new Summary(rs.getString("id"), rs.getString("appointment_id"), rs.getString("pet_id"),
            rs.getString("doctor_id"), rs.getString("doctor_name"), rs.getString("status"),
            instant(rs, "submitted_at"), rs.getInt("version"), rs.getInt("revision_no"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        LocalDateTime time = rs.getObject(field, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    private static long offset(int page, int pageSize) {
        return (page - 1L) * pageSize;
    }

    private static BusinessException missingRecord() {
        return new BusinessException(HttpStatus.NOT_FOUND, "MEDICAL_RECORD_NOT_FOUND", "病历不存在");
    }

    private record RecordRow(Summary summary, String content) { }
}
