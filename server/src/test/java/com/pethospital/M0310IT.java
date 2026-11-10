package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;

import com.pethospital.identity.BackendPermissionCatalog;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.Actor;
import com.pethospital.identity.ResourceAccessPolicy.AiSessionAccess;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentVisibility;
import com.pethospital.identity.ResourceAccessPolicy.MedicalAccess;
import com.pethospital.identity.ResourceAccessPolicy.PetAccess;
import com.pethospital.testing.MySqlTestContainer;
import com.pethospital.testing.TestTags;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@Tag(TestTags.MYSQL_IT)
@SpringBootTest(properties = {
    "springdoc.api-docs.enabled=false",
    "identity.jwt.allow-ephemeral=true",
    "identity.profile.phone-key-base64=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
    "identity.access.completed-appointment-days=30"
})
class M0310IT {
    private static final MySqlTestContainer MYSQL = MySqlTestContainer.start();
    private static final Instant NOW = Instant.now();

    private static final long OWNER_ONE = 31_001;
    private static final long OWNER_TWO = 31_002;
    private static final long DOCTOR_USER_ONE = 31_101;
    private static final long DOCTOR_USER_TWO = 31_102;
    private static final long ADMIN_USER = 31_201;
    private static final long DOCTOR_ONE = 32_101;
    private static final long DOCTOR_TWO = 32_102;

    private static final long PET_ACTIVE = 33_001;
    private static final long PET_OTHER = 33_002;
    private static final long PET_PENDING = 33_003;
    private static final long PET_RECENT = 33_004;
    private static final long PET_EXPIRED = 33_005;

    private static final long SESSION_ACTIVE = 34_001;
    private static final long SESSION_OTHER = 34_002;
    private static final long SESSION_PENDING = 34_003;
    private static final long SESSION_RECENT = 34_004;
    private static final long SESSION_EXPIRED = 34_005;

    private static final long APPOINTMENT_ACTIVE = 36_001;
    private static final long APPOINTMENT_OTHER = 36_002;
    private static final long APPOINTMENT_PENDING = 36_003;
    private static final long APPOINTMENT_RECENT = 36_004;
    private static final long APPOINTMENT_EXPIRED = 36_005;

    private static final long ACTIVE_RECORD = 37_001;
    private static final long EXPIRED_RECORD = 37_002;
    private static final long EXPIRED_VERSION = 38_002;
    private static final long HEALTH_LOG = 39_001;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::jdbcUrl);
        registry.add("spring.datasource.username", MYSQL::username);
        registry.add("spring.datasource.password", MYSQL::password);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ResourceAccessPolicy policy;

    @AfterAll
    static void stopIsolatedMySql() {
        MYSQL.close();
    }

    @BeforeEach
    void resetFixtures() {
        jdbc.update("DELETE FROM medical_record_version");
        jdbc.update("DELETE FROM medical_record");
        jdbc.update("DELETE FROM appointment_status_log");
        jdbc.update("DELETE FROM appointment_change_request");
        jdbc.update("DELETE FROM appointment");
        jdbc.update("DELETE FROM doctor_slot");
        jdbc.update("DELETE FROM ai_message");
        jdbc.update("DELETE FROM ai_session");
        jdbc.update("DELETE FROM pet_health_log");
        jdbc.update("DELETE FROM pet");
        jdbc.update("DELETE FROM doctor_profile");
        jdbc.update("DELETE FROM sys_role_menu WHERE id>=39000");
        jdbc.update("DELETE FROM sys_menu WHERE id>=39000");
        jdbc.update("DELETE FROM sys_user_role WHERE user_id>=31000");
        jdbc.update("DELETE FROM sys_user WHERE id>=31000");

        insertUser(OWNER_ONE, "scope-owner-one", "主人甲");
        insertUser(OWNER_TWO, "scope-owner-two", "主人乙");
        insertUser(DOCTOR_USER_ONE, "scope-doctor-one", "医生甲");
        insertUser(DOCTOR_USER_TWO, "scope-doctor-two", "医生乙");
        insertUser(ADMIN_USER, "scope-admin", "管理员");
        grantRole(OWNER_ONE, "USER", 40_001);
        grantRole(OWNER_TWO, "USER", 40_002);
        grantRole(DOCTOR_USER_ONE, "DOCTOR", 40_101);
        grantRole(DOCTOR_USER_TWO, "DOCTOR", 40_102);
        grantRole(ADMIN_USER, "ADMIN", 40_201);

        jdbc.update("""
            INSERT INTO doctor_profile(id,user_id,doctor_name,status,version)
            VALUES (?,?,?,'ACTIVE',0),(?,?,?,'ACTIVE',0)
            """, DOCTOR_ONE, DOCTOR_USER_ONE, "医生甲", DOCTOR_TWO, DOCTOR_USER_TWO, "医生乙");

        insertPet(PET_ACTIVE, OWNER_ONE, "正在问诊宠物");
        insertPet(PET_OTHER, OWNER_TWO, "另一主人宠物");
        insertPet(PET_PENDING, OWNER_ONE, "待确认宠物");
        insertPet(PET_RECENT, OWNER_ONE, "近期完成宠物");
        insertPet(PET_EXPIRED, OWNER_ONE, "超期完成宠物");

        insertSession(SESSION_ACTIVE, OWNER_ONE, PET_ACTIVE);
        insertSession(SESSION_OTHER, OWNER_TWO, PET_OTHER);
        insertSession(SESSION_PENDING, OWNER_ONE, PET_PENDING);
        insertSession(SESSION_RECENT, OWNER_ONE, PET_RECENT);
        insertSession(SESSION_EXPIRED, OWNER_ONE, PET_EXPIRED);

        insertSlot(35_001, DOCTOR_ONE, 1);
        insertSlot(35_002, DOCTOR_TWO, 2);
        insertSlot(35_003, DOCTOR_ONE, 3);
        insertSlot(35_004, DOCTOR_ONE, 4);
        insertSlot(35_005, DOCTOR_ONE, 5);

        insertAppointment(APPOINTMENT_ACTIVE, OWNER_ONE, PET_ACTIVE, DOCTOR_ONE, 35_001,
            SESSION_ACTIVE, "CONFIRMED", null);
        insertAppointment(APPOINTMENT_OTHER, OWNER_TWO, PET_OTHER, DOCTOR_TWO, 35_002,
            SESSION_OTHER, "CONFIRMED", null);
        insertAppointment(APPOINTMENT_PENDING, OWNER_ONE, PET_PENDING, DOCTOR_ONE, 35_003,
            SESSION_PENDING, "PENDING", null);
        insertAppointment(APPOINTMENT_RECENT, OWNER_ONE, PET_RECENT, DOCTOR_ONE, 35_004,
            SESSION_RECENT, "COMPLETED", NOW.minusSeconds(29L * 86_400));
        insertAppointment(APPOINTMENT_EXPIRED, OWNER_ONE, PET_EXPIRED, DOCTOR_ONE, 35_005,
            SESSION_EXPIRED, "COMPLETED", NOW.minusSeconds(31L * 86_400));

        jdbc.update("""
            INSERT INTO medical_record(
              id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,
              submitted_at,version
            ) VALUES (?,?,?,?,?,'{}',?,?,0),(?,?,?,?,?,'{}',?,?,0)
            """,
            ACTIVE_RECORD, APPOINTMENT_ACTIVE, PET_ACTIVE, DOCTOR_ONE, "DRAFT", 0, null,
            EXPIRED_RECORD, APPOINTMENT_EXPIRED, PET_EXPIRED, DOCTOR_ONE, "SUBMITTED", 1,
            Timestamp.from(NOW.minusSeconds(31L * 86_400))
        );
        jdbc.update("""
            INSERT INTO medical_record_version(
              id,record_id,revision_no,before_content_json,after_content_json,reason,
              actor_user_id,idempotency_key
            ) VALUES (?,?,1,NULL,'{}','首次提交',?,?)
            """, EXPIRED_VERSION, EXPIRED_RECORD, DOCTOR_USER_ONE, "m0310-version");
        jdbc.update("""
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,symptom_note,version)
            VALUES (?,?,?,?,?,0)
            """, HEALTH_LOG, PET_ACTIVE, OWNER_ONE, Timestamp.from(NOW), "食欲下降");
    }

    @Test
    void miniOwnersOnlySeeTheirOwnObjectsAndCannotUseWebAudienceToExpandScope() {
        Actor ownerOne = mini(OWNER_ONE);
        Actor ownerTwo = mini(OWNER_TWO);

        assertThat(policy.pet(ownerOne, PET_ACTIVE)).isEqualTo(PetAccess.OWNER);
        assertThat(policy.pet(ownerOne, PET_OTHER)).isEqualTo(PetAccess.NONE);
        assertThat(policy.pet(ownerTwo, PET_ACTIVE)).isEqualTo(PetAccess.NONE);
        assertThat(policy.appointment(ownerOne, APPOINTMENT_ACTIVE))
            .isEqualTo(AppointmentVisibility.FULL);
        assertThat(policy.appointment(ownerOne, APPOINTMENT_OTHER))
            .isEqualTo(AppointmentVisibility.NONE);
        assertThat(policy.aiSession(ownerOne, SESSION_ACTIVE)).isEqualTo(AiSessionAccess.OWNER);
        assertThat(policy.aiSession(ownerOne, SESSION_OTHER)).isEqualTo(AiSessionAccess.NONE);
        assertThat(policy.pet(web(OWNER_ONE), PET_ACTIVE)).isEqualTo(PetAccess.NONE);
    }

    @Test
    void pendingAppointmentOnlyExposesNecessaryAppointmentFields() {
        Actor doctorOne = web(DOCTOR_USER_ONE);
        Actor doctorTwo = web(DOCTOR_USER_TWO);

        assertThat(policy.appointment(doctorOne, APPOINTMENT_PENDING))
            .isEqualTo(AppointmentVisibility.LIMITED);
        assertThat(policy.pet(doctorOne, PET_PENDING)).isEqualTo(PetAccess.NONE);
        assertThat(policy.aiSession(doctorOne, SESSION_PENDING)).isEqualTo(AiSessionAccess.NONE);
        assertThat(policy.appointment(doctorTwo, APPOINTMENT_PENDING))
            .isEqualTo(AppointmentVisibility.NONE);
    }

    @Test
    void confirmedAndRecentCompletedAppointmentsOpenOnlyAssignedDoctorScope() {
        Actor doctorOne = web(DOCTOR_USER_ONE);
        Actor doctorTwo = web(DOCTOR_USER_TWO);

        assertThat(policy.appointment(doctorOne, APPOINTMENT_ACTIVE))
            .isEqualTo(AppointmentVisibility.FULL);
        assertThat(policy.pet(doctorOne, PET_ACTIVE)).isEqualTo(PetAccess.READ);
        assertThat(policy.healthLog(doctorOne, HEALTH_LOG)).isEqualTo(PetAccess.READ);
        assertThat(policy.aiSession(doctorOne, SESSION_ACTIVE)).isEqualTo(AiSessionAccess.READ);
        assertThat(policy.pet(doctorOne, PET_RECENT)).isEqualTo(PetAccess.READ);
        assertThat(policy.aiSession(doctorOne, SESSION_RECENT)).isEqualTo(AiSessionAccess.READ);

        assertThat(policy.pet(doctorTwo, PET_ACTIVE)).isEqualTo(PetAccess.NONE);
        assertThat(policy.healthLog(doctorTwo, HEALTH_LOG)).isEqualTo(PetAccess.NONE);
        assertThat(policy.aiSession(doctorTwo, SESSION_ACTIVE)).isEqualTo(AiSessionAccess.NONE);
    }

    @Test
    void thirtyDayExpiryDoesNotRemoveOriginalDoctorsRecordRights() {
        Actor doctorOne = web(DOCTOR_USER_ONE);
        Actor doctorTwo = web(DOCTOR_USER_TWO);

        assertThat(policy.pet(doctorOne, PET_EXPIRED)).isEqualTo(PetAccess.NONE);
        assertThat(policy.aiSession(doctorOne, SESSION_EXPIRED)).isEqualTo(AiSessionAccess.NONE);
        assertThat(policy.medicalRecord(doctorOne, EXPIRED_RECORD)).isEqualTo(MedicalAccess.AUTHOR);
        assertThat(policy.medicalRecordVersion(doctorOne, EXPIRED_VERSION))
            .isEqualTo(MedicalAccess.READ);
        assertThat(policy.medicalRecord(doctorTwo, EXPIRED_RECORD)).isEqualTo(MedicalAccess.NONE);
        assertThat(policy.medicalRecordVersion(doctorTwo, EXPIRED_VERSION))
            .isEqualTo(MedicalAccess.NONE);
        assertThat(policy.medicalRecord(mini(OWNER_ONE), EXPIRED_RECORD))
            .isEqualTo(MedicalAccess.READ);
        assertThat(policy.medicalRecord(mini(OWNER_ONE), ACTIVE_RECORD))
            .isEqualTo(MedicalAccess.NONE);
    }

    @Test
    void adminNeedsDedicatedAuditPermissionsAndNeverBecomesClinicalAuthor() {
        Actor admin = web(ADMIN_USER);

        assertThat(policy.appointment(admin, APPOINTMENT_ACTIVE))
            .isEqualTo(AppointmentVisibility.FULL);
        assertThat(policy.aiSession(admin, SESSION_ACTIVE)).isEqualTo(AiSessionAccess.NONE);
        assertThat(policy.healthLog(admin, HEALTH_LOG)).isEqualTo(PetAccess.NONE);
        assertThat(policy.medicalRecord(admin, EXPIRED_RECORD)).isEqualTo(MedicalAccess.NONE);

        grantAdminPermission(39_001, 39_101, BackendPermissionCatalog.AUDIT_READ);
        grantAdminPermission(39_002, 39_102, BackendPermissionCatalog.AI_CONSULT_AUDIT);

        assertThat(policy.aiSession(admin, SESSION_ACTIVE)).isEqualTo(AiSessionAccess.READ);
        assertThat(policy.healthLog(admin, HEALTH_LOG)).isEqualTo(PetAccess.READ);
        assertThat(policy.medicalRecord(admin, EXPIRED_RECORD)).isEqualTo(MedicalAccess.READ);
        assertThat(policy.medicalRecordVersion(admin, EXPIRED_VERSION))
            .isEqualTo(MedicalAccess.READ);
        assertThat(policy.medicalRecord(admin, ACTIVE_RECORD)).isEqualTo(MedicalAccess.NONE);
    }

    private void insertUser(long id, String username, String displayName) {
        jdbc.update("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,NULL,?,'ACTIVE',0,0)
            """, id, username, displayName);
    }

    private void grantRole(long userId, String roleCode, long assignmentId) {
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code=?", Long.class, roleCode);
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)",
            assignmentId, userId, roleId);
    }

    private void insertPet(long id, long ownerId, String name) {
        jdbc.update("""
            INSERT INTO pet(id,owner_user_id,name,species_code,sex,neutered,status,version)
            VALUES (?, ?, ?, 'CAT', 'UNKNOWN', 'UNKNOWN', 'ACTIVE', 0)
            """, id, ownerId, name);
    }

    private void insertSession(long id, long ownerId, long petId) {
        jdbc.update("""
            INSERT INTO ai_session(
              id,user_id,pet_id,title,status,pet_snapshot_json,summary_through_seq,
              summary_version,version
            ) VALUES (?,?,?,'测试问诊','OPEN','{}',0,0,0)
            """, id, ownerId, petId);
    }

    private void insertSlot(long id, long doctorId, int dayOffset) {
        Instant start = NOW.plusSeconds((10L + dayOffset) * 86_400);
        jdbc.update("""
            INSERT INTO doctor_slot(
              id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version
            ) VALUES (?,?,?,?,?,1,1,'OPEN',0)
            """,
            id,
            doctorId,
            Date.valueOf(LocalDate.now().plusDays(10L + dayOffset)),
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(1_800))
        );
    }

    private void insertAppointment(
        long id,
        long userId,
        long petId,
        long doctorId,
        long slotId,
        long sessionId,
        String status,
        Instant completedAt
    ) {
        Instant start = NOW.plusSeconds((id - 35_990) * 86_400);
        Timestamp confirmedAt = "PENDING".equals(status) ? null : Timestamp.from(NOW.minusSeconds(3_600));
        jdbc.update("""
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,
              scheduled_end_at,initial_start_at,status,chief_complaint,ai_session_id,
              confirm_deadline_at,confirmed_at,completed_at,idempotency_key,request_hash,version
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)
            """,
            id,
            "M0310-" + id,
            userId,
            petId,
            doctorId,
            slotId,
            Timestamp.from(start),
            Timestamp.from(start.plusSeconds(1_800)),
            Timestamp.from(start),
            status,
            "测试主诉",
            sessionId,
            Timestamp.from(start.minusSeconds(86_400)),
            confirmedAt,
            completedAt == null ? null : Timestamp.from(completedAt),
            "m0310-" + id,
            "%064x".formatted(id)
        );
    }

    private void grantAdminPermission(long menuId, long roleMenuId, String permissionCode) {
        Long roleId = jdbc.queryForObject("SELECT id FROM sys_role WHERE code='ADMIN'", Long.class);
        jdbc.update("""
            INSERT INTO sys_menu(id,parent_id,name,kind,permission_code,sort_no,status,version)
            VALUES (?,NULL,?,'BUTTON',?,0,'ACTIVE',0)
            """, menuId, permissionCode, permissionCode);
        jdbc.update("INSERT INTO sys_role_menu(id,role_id,menu_id) VALUES (?,?,?)",
            roleMenuId, roleId, menuId);
    }

    private static Actor mini(long userId) {
        return new Actor(userId, "MINI");
    }

    private static Actor web(long userId) {
        return new Actor(userId, "WEB");
    }
}
