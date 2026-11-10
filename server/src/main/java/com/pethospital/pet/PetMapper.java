package com.pethospital.pet;

import com.pethospital.pet.PetDtos.PetCreateRequest;
import com.pethospital.pet.PetDtos.PetUpdateRequest;
import com.pethospital.pet.PetDtos.PetView;
import com.pethospital.pet.PetDtos.WeightSnapshot;
import com.pethospital.pet.AdminPetDtos.AdminPetPatchRequest;
import com.pethospital.shared.BusinessException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PetMapper {
    private final ObjectProvider<DataSource> dataSourceProvider;

    public PetMapper(ObjectProvider<DataSource> dataSourceProvider) {
        this.dataSourceProvider = dataSourceProvider;
    }

    public void insert(long petId, long ownerUserId, PetCreateRequest request) {
        jdbc().update("""
            INSERT INTO pet(
                id,owner_user_id,name,species_code,breed,sex,birth_date,neutered,
                avatar_file_id,owner_health_notes,status,version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',0)
            """,
            petId,
            ownerUserId,
            request.name().strip(),
            request.speciesCode(),
            nullableText(request.breed()),
            request.sex(),
            request.birthDate() == null ? null : Date.valueOf(request.birthDate()),
            request.neutered(),
            request.avatarFileId(),
            nullableText(request.ownerHealthNotes())
        );
    }

    public PetView findOwned(long petId, long ownerUserId) {
        PetRow row = findOwnedRow(petId, ownerUserId, false);
        return row == null ? null : view(row);
    }

    public PetRow findOwnedRow(long petId, long ownerUserId, boolean lock) {
        return jdbc().query("""
            SELECT id,owner_user_id,name,species_code,breed,sex,birth_date,neutered,
                   avatar_file_id,owner_health_notes,status,version,created_at,updated_at
            FROM pet WHERE id=? AND owner_user_id=?
            """ + (lock ? " FOR UPDATE" : ""),
            result -> result.next() ? row(result) : null,
            petId,
            ownerUserId
        );
    }

    public int updateOwned(long petId, long ownerUserId, PetUpdateRequest request) {
        return jdbc().update("""
            UPDATE pet
            SET name=?,species_code=?,breed=?,sex=?,birth_date=?,neutered=?,
                avatar_file_id=?,owner_health_notes=?,version=version+1
            WHERE id=? AND owner_user_id=? AND version=?
            """,
            request.name().strip(),
            request.speciesCode(),
            nullableText(request.breed()),
            request.sex(),
            request.birthDate() == null ? null : Date.valueOf(request.birthDate()),
            request.neutered(),
            request.avatarFileId(),
            nullableText(request.ownerHealthNotes()),
            petId,
            ownerUserId,
            request.version()
        );
    }

    public int archiveOwned(long petId, long ownerUserId, int version) {
        return jdbc().update("""
            UPDATE pet SET status='ARCHIVED',version=version+1
            WHERE id=? AND owner_user_id=? AND status='ACTIVE' AND version=?
            """, petId, ownerUserId, version);
    }

    public AdminPetRow findAdminRow(long petId, boolean lock) {
        return jdbc().query("""
            SELECT p.id,p.owner_user_id,p.name,p.species_code,p.breed,p.sex,p.birth_date,p.neutered,
                   p.avatar_file_id,p.owner_health_notes,p.status,p.version,p.created_at,p.updated_at,
                   u.display_name AS owner_display_name
            FROM pet p JOIN sys_user u ON u.id=p.owner_user_id
            WHERE p.id=?
            """ + (lock ? " FOR UPDATE" : ""),
            result -> result.next() ? adminRow(result) : null,
            petId
        );
    }

    public long countAdmin() {
        Long total = jdbc().queryForObject("SELECT COUNT(*) FROM pet", Long.class);
        return total == null ? 0 : total;
    }

    public List<AdminPetRow> listAdmin(int pageSize, long offset) {
        return jdbc().query("""
            SELECT p.id,p.owner_user_id,p.name,p.species_code,p.breed,p.sex,p.birth_date,p.neutered,
                   p.avatar_file_id,p.owner_health_notes,p.status,p.version,p.created_at,p.updated_at,
                   u.display_name AS owner_display_name
            FROM pet p JOIN sys_user u ON u.id=p.owner_user_id
            ORDER BY p.created_at DESC,p.id DESC LIMIT ? OFFSET ?
            """, (result, rowNumber) -> adminRow(result), pageSize, offset);
    }

    public int updateAdmin(long petId, AdminPetPatchRequest request) {
        return jdbc().update("""
            UPDATE pet SET name=?,species_code=?,breed=?,sex=?,birth_date=?,neutered=?,
                owner_health_notes=?,version=version+1
            WHERE id=? AND version=?
            """,
            request.name(), request.speciesCode(), request.breed(), request.sex(),
            request.birthDate() == null ? null : Date.valueOf(request.birthDate()), request.neutered(),
            request.ownerHealthNotes(), petId, request.version()
        );
    }

    public WeightSnapshot latestWeight(long petId) {
        return jdbc().query("""
            SELECT weight_kg,observed_at
            FROM pet_health_log
            WHERE pet_id=? AND weight_kg IS NOT NULL
            ORDER BY observed_at DESC,id DESC LIMIT 1
            """, result -> result.next() ? new WeightSnapshot(
                result.getBigDecimal("weight_kg"),
                instant(result.getTimestamp("observed_at")),
                "HEALTH_LOG"
            ) : null, petId);
    }

    public SubmittedClinicalRow latestSubmittedClinical(long petId) {
        return jdbc().query("""
            SELECT id,content_json,revision_no,submitted_at
            FROM medical_record
            WHERE pet_id=? AND status='SUBMITTED'
            ORDER BY submitted_at DESC,id DESC LIMIT 1
            """, result -> result.next() ? new SubmittedClinicalRow(
                result.getLong("id"),
                result.getString("content_json"),
                result.getInt("revision_no"),
                instant(result.getTimestamp("submitted_at"))
            ) : null, petId);
    }

    public long countOwned(long ownerUserId, String status) {
        Long total = status == null
            ? jdbc().queryForObject("SELECT COUNT(*) FROM pet WHERE owner_user_id=?", Long.class, ownerUserId)
            : jdbc().queryForObject(
                "SELECT COUNT(*) FROM pet WHERE owner_user_id=? AND status=?",
                Long.class,
                ownerUserId,
                status
            );
        return total == null ? 0 : total;
    }

    public List<PetView> listOwned(long ownerUserId, String status, int pageSize, long offset) {
        List<Object> arguments = new ArrayList<>();
        arguments.add(ownerUserId);
        String statusSql = "";
        if (status != null) {
            statusSql = " AND status=?";
            arguments.add(status);
        }
        arguments.add(pageSize);
        arguments.add(offset);
        return jdbc().query("""
            SELECT id,name,species_code,breed,sex,birth_date,neutered,avatar_file_id,
                   status,version,created_at,updated_at
            FROM pet WHERE owner_user_id=?
            """ + statusSql + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (result, rowNumber) -> view(result),
            arguments.toArray()
        );
    }

    private static PetView view(ResultSet result) throws SQLException {
        Date birthDate = result.getDate("birth_date");
        Long avatarFileId = result.getObject("avatar_file_id", Long.class);
        return new PetView(
            Long.toString(result.getLong("id")),
            result.getString("name"),
            result.getString("species_code"),
            result.getString("breed"),
            result.getString("sex"),
            birthDate == null ? null : birthDate.toLocalDate(),
            result.getString("neutered"),
            avatarFileId == null ? null : Long.toString(avatarFileId),
            result.getString("status"),
            result.getInt("version"),
            instant(result.getTimestamp("created_at")),
            instant(result.getTimestamp("updated_at"))
        );
    }

    public static PetView view(PetRow row) {
        return new PetView(
            Long.toString(row.id()),
            row.name(),
            row.speciesCode(),
            row.breed(),
            row.sex(),
            row.birthDate(),
            row.neutered(),
            row.avatarFileId() == null ? null : Long.toString(row.avatarFileId()),
            row.status(),
            row.version(),
            instant(row.createdAt()),
            instant(row.updatedAt())
        );
    }

    private static PetRow row(ResultSet result) throws SQLException {
        Date birthDate = result.getDate("birth_date");
        return new PetRow(
            result.getLong("id"),
            result.getLong("owner_user_id"),
            result.getString("name"),
            result.getString("species_code"),
            result.getString("breed"),
            result.getString("sex"),
            birthDate == null ? null : birthDate.toLocalDate(),
            result.getString("neutered"),
            result.getObject("avatar_file_id", Long.class),
            result.getString("owner_health_notes"),
            result.getString("status"),
            result.getInt("version"),
            result.getTimestamp("created_at"),
            result.getTimestamp("updated_at")
        );
    }

    private static AdminPetRow adminRow(ResultSet result) throws SQLException {
        return new AdminPetRow(row(result), result.getString("owner_display_name"));
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PET_DEPENDENCY_UNAVAILABLE",
                "宠物资料数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private static String nullableText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private static String instant(Timestamp value) {
        return value.toInstant().toString();
    }

    public record PetRow(
        long id,
        long ownerUserId,
        String name,
        String speciesCode,
        String breed,
        String sex,
        java.time.LocalDate birthDate,
        String neutered,
        Long avatarFileId,
        String ownerHealthNotes,
        String status,
        int version,
        Timestamp createdAt,
        Timestamp updatedAt
    ) {
    }

    public record SubmittedClinicalRow(
        long recordId,
        String contentJson,
        int revisionNo,
        String submittedAt
    ) {
    }

    public record AdminPetRow(PetRow pet, String ownerDisplayName) {
    }
}
