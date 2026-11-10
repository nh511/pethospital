package com.pethospital.persistence.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

public final class DemoFixtureService {
    private static final List<DemoUser> USERS = List.of(
        new DemoUser(DemoFixtureIds.USER_ONE, "demo_user_1", "演示用户甲"),
        new DemoUser(DemoFixtureIds.USER_TWO, "demo_user_2", "演示用户乙"),
        new DemoUser(DemoFixtureIds.DOCTOR_USER_ONE, "demo_doctor_1", "演示医生甲"),
        new DemoUser(DemoFixtureIds.DOCTOR_USER_TWO, "demo_doctor_2", "演示医生乙"),
        new DemoUser(DemoFixtureIds.ADMIN_USER, "demo_admin", "演示管理员")
    );

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public DemoFixtureSummary rebuild(DataSource dataSource, String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank() || rawPassword.length() < 8) {
            throw new IllegalArgumentException("DEMO_PASSWORD must contain at least 8 characters");
        }
        String passwordHash = passwordEncoder.encode(rawPassword);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                deleteExistingFixture(connection);
                long userRoleId = ensureRole(connection, DemoFixtureIds.ROLE_USER, "USER", "用户");
                long doctorRoleId = ensureRole(connection, DemoFixtureIds.ROLE_DOCTOR, "DOCTOR", "医生");
                long adminRoleId = ensureRole(connection, DemoFixtureIds.ROLE_ADMIN, "ADMIN", "管理员");
                insertUsers(connection, passwordHash);
                insertRoleBindings(connection, userRoleId, doctorRoleId, adminRoleId);
                insertBusinessData(connection);
                connection.commit();
                return new DemoFixtureSummary(5, 2, 2, 3, 2, 1, 2, 1, 2);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to rebuild the local demo fixture", exception);
        }
    }

    private static void deleteExistingFixture(Connection connection) throws SQLException {
        execute(connection, "DELETE FROM health_standard WHERE id=-8001");
        execute(connection, "DELETE FROM health_guide WHERE id=-7001");
        execute(connection, "DELETE FROM ai_session WHERE id=-6001");
        execute(connection, "DELETE FROM medical_record_version WHERE id IN (-9101,-9102)");
        execute(connection, "DELETE FROM medical_record WHERE id=-9001");
        execute(connection, "DELETE FROM appointment_status_log WHERE id IN (-5101,-5102)");
        execute(connection, "DELETE FROM appointment WHERE id IN (-5001,-5002)");
        execute(connection, "DELETE FROM doctor_slot WHERE id IN (-4001,-4002,-4003)");
        execute(connection, "DELETE FROM pet_health_log WHERE id IN (-3101,-3102)");
        execute(connection, "DELETE FROM pet WHERE id IN (-3001,-3002)");
        execute(connection, "DELETE FROM doctor_profile WHERE id IN (-2001,-2002)");
        execute(connection, "DELETE FROM sys_user_role WHERE id IN (-1201,-1202,-1203,-1204,-1205)");
        execute(connection, "DELETE FROM sys_user WHERE id IN (-1001,-1002,-1003,-1004,-1005)");
    }

    private static long ensureRole(Connection connection, long preferredId, String code, String name)
        throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT id FROM sys_role WHERE code=?")) {
            query.setString(1, code);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return result.getLong(1);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            INSERT INTO sys_role(id,code,name,status,version)
            VALUES (?,?,?,'ACTIVE',0)
            """)) {
            insert.setLong(1, preferredId);
            insert.setString(2, code);
            insert.setString(3, name);
            insert.executeUpdate();
        }
        return preferredId;
    }

    private static void insertUsers(Connection connection, String passwordHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_user(id,username,password_hash,display_name,status,token_version,version)
            VALUES (?,?,?,?,'ACTIVE',0,0)
            """)) {
            for (DemoUser user : USERS) {
                statement.setLong(1, user.id());
                statement.setString(2, user.username());
                statement.setString(3, passwordHash);
                statement.setString(4, user.displayName());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertRoleBindings(
        Connection connection,
        long userRoleId,
        long doctorRoleId,
        long adminRoleId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO sys_user_role(id,user_id,role_id) VALUES (?,?,?)
            """)) {
            addRoleBinding(statement, DemoFixtureIds.USER_ROLE_ONE, DemoFixtureIds.USER_ONE, userRoleId);
            addRoleBinding(statement, DemoFixtureIds.USER_ROLE_TWO, DemoFixtureIds.USER_TWO, userRoleId);
            addRoleBinding(statement, DemoFixtureIds.USER_ROLE_DOCTOR_ONE, DemoFixtureIds.DOCTOR_USER_ONE, doctorRoleId);
            addRoleBinding(statement, DemoFixtureIds.USER_ROLE_DOCTOR_TWO, DemoFixtureIds.DOCTOR_USER_TWO, doctorRoleId);
            addRoleBinding(statement, DemoFixtureIds.USER_ROLE_ADMIN, DemoFixtureIds.ADMIN_USER, adminRoleId);
            statement.executeBatch();
        }
    }

    private static void addRoleBinding(PreparedStatement statement, long id, long userId, long roleId)
        throws SQLException {
        statement.setLong(1, id);
        statement.setLong(2, userId);
        statement.setLong(3, roleId);
        statement.addBatch();
    }

    private static void insertBusinessData(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO doctor_profile(id,user_id,doctor_name,title,specialties,introduction,status,version)
            VALUES
              (-2001,-1003,'演示医生甲','主治兽医师','猫科常见病','纯虚构演示医生资料，不代表真实执业资质。','ACTIVE',0),
              (-2002,-1004,'演示医生乙','兽医师','犬科预防保健','纯虚构演示医生资料，不代表真实执业资质。','ACTIVE',0)
            """);
        execute(connection, """
            INSERT INTO pet(id,owner_user_id,name,species_code,breed,sex,birth_date,neutered,owner_health_notes,status,version)
            VALUES
              (-3001,-1001,'团子','CAT','中华田园猫','FEMALE','2022-05-01','YES','固定演示宠物，无真实个体信息。','ACTIVE',0),
              (-3002,-1002,'可可','DOG','混种犬','MALE','2021-09-15','YES','固定演示宠物，无真实个体信息。','ACTIVE',0)
            """);
        execute(connection, """
            INSERT INTO pet_health_log(id,pet_id,author_user_id,observed_at,weight_kg,diet_note,activity_note,version)
            VALUES
              (-3101,-3001,-1001,'2035-01-02 08:30:00.123',4.35,'正常进食','活动正常',0),
              (-3102,-3002,-1002,'2035-01-02 09:15:00.456',12.80,'饮水正常','散步三十分钟',0)
            """);
        execute(connection, """
            INSERT INTO doctor_slot(id,doctor_id,slot_date,start_at,end_at,capacity,occupied_count,status,version)
            VALUES
              (-4001,-2001,'2035-01-10','2035-01-10 09:00:00.000','2035-01-10 09:30:00.000',1,1,'OPEN',0),
              (-4002,-2002,'2035-01-10','2035-01-10 10:00:00.000','2035-01-10 10:30:00.000',1,0,'OPEN',0),
              (-4003,-2001,'2035-01-02','2035-01-02 10:00:00.000','2035-01-02 10:30:00.000',1,1,'CLOSED',0)
            """);
        execute(connection, """
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
              initial_start_at,status,chief_complaint,confirm_deadline_at,idempotency_key,request_hash,version
            ) VALUES (
              -5001,'DEMO-20350110-001',-1001,-3001,-2001,-4001,
              '2035-01-10 09:00:00.000','2035-01-10 09:30:00.000','2035-01-10 09:00:00.000',
              'PENDING','固定演示：近期偶尔抓挠耳部。','2035-01-09 09:00:00.000',
              'demo-m0205-appointment','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0
            )
            """);
        execute(connection, """
            INSERT INTO appointment_status_log(
              id,appointment_id,from_status,to_status,actor_type,actor_user_id,reason,operation_key,request_hash
            ) VALUES (
              -5101,-5001,NULL,'PENDING','USER',-1001,'固定演示预约创建',
              'demo-m0205-created','bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
            )
            """);
        insertMedicalExample(connection);
        execute(connection, """
            INSERT INTO ai_session(
              id,user_id,pet_id,title,status,pet_snapshot_json,summary_through_seq,summary_version,
              last_message_at,version
            ) VALUES (
              -6001,-1002,-3002,'可可的日常健康咨询','OPEN',
              JSON_OBJECT('petId','-3002','name','可可','speciesCode','DOG'),0,0,
              '2035-01-02 09:20:00.789',0
            )
            """);
        execute(connection, """
            INSERT INTO health_guide(
              id,title,species_code,category_code,body,source_note,status,published_at,editor_user_id,version
            ) VALUES (
              -7001,'演示：家庭观察记录要点','COMMON','DAILY_CARE',
              '记录饮食、饮水、排便和活动变化；出现急症信号应立即线下就医。',
              '纯虚构演示内容，不构成医疗建议。','PUBLISHED','2035-01-01 00:00:00.000',-1005,0
            )
            """);
        execute(connection, """
            INSERT INTO health_standard(
              id,name,species_code,age_min_months,age_max_months,metric_code,unit,
              reference_min,reference_max,description,conditions_note,source_note,status,editor_doctor_id,version
            ) VALUES (
              -8001,'演示体重观察区间','CAT',12,120,'WEIGHT_KG','kg',3.0000,6.0000,
              '仅用于演示结构，不能用于个体诊断。','成年猫示例条件。',
              '纯虚构演示数据，无真实医学来源。','PUBLISHED',-2001,0
            )
            """);
    }

    private static void insertMedicalExample(Connection connection) throws SQLException {
        execute(connection, """
            INSERT INTO appointment(
              id,appointment_no,user_id,pet_id,doctor_id,slot_id,scheduled_start_at,scheduled_end_at,
              initial_start_at,status,chief_complaint,confirm_deadline_at,confirmed_at,started_at,completed_at,
              idempotency_key,request_hash,version
            ) VALUES (
              -5002,'DEMO-20350102-002',-1001,-3001,-2001,-4003,
              '2035-01-02 10:00:00.000','2035-01-02 10:30:00.000','2035-01-02 10:00:00.000',
              'COMPLETED','纯虚构病历，用于演示版本追溯。','2035-01-01 10:00:00.000',
              '2035-01-01 09:00:00.000','2035-01-02 10:00:00.000','2035-01-02 10:30:00.000',
              'demo-m0805-appointment','cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',3
            )
            """);
        execute(connection, """
            INSERT INTO appointment_status_log(
              id,appointment_id,from_status,to_status,actor_type,actor_user_id,reason,operation_key,request_hash,created_at
            ) VALUES (
              -5102,-5002,'IN_CONSULTATION','COMPLETED','DOCTOR',-1003,'固定演示诊疗完成',
              'demo-m0805-complete','dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
              '2035-01-02 10:30:00.000'
            )
            """);
        execute(connection, """
            INSERT INTO medical_record(id,appointment_id,pet_id,doctor_id,status,content_json,revision_no,submitted_at,version)
            VALUES (-9001,-5002,-3001,-2001,'SUBMITTED',
              JSON_OBJECT('chiefComplaint','虚构演示主诉','examination','虚构检查记录','diagnosis','演示结论',
                'treatment','演示处置记录','medications',JSON_ARRAY(),'allergies',JSON_ARRAY(),
                'chronicConditions',JSON_ARRAY(),'followupAdvice','演示复诊安排待补充','attachmentIds',JSON_ARRAY()),
              1,'2035-01-02 10:30:00.000',1)
            """);
        execute(connection, """
            INSERT INTO medical_record_version(
              id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key,created_at
            )
            SELECT -9101,id,1,NULL,content_json,'首次提交',-1003,'demo-m0805-complete','2035-01-02 10:30:00.000'
            FROM medical_record WHERE id=-9001
            """);
        execute(connection, """
            INSERT INTO medical_record_version(
              id,record_id,revision_no,before_content_json,after_content_json,reason,actor_user_id,idempotency_key,created_at
            )
            SELECT -9102,id,2,content_json,JSON_SET(content_json,'$.followupAdvice','已补充演示复诊安排'),
              '补记演示复诊安排',-1003,'demo-m0805-revision','2035-01-03 09:00:00.000'
            FROM medical_record WHERE id=-9001
            """);
        execute(connection, """
            UPDATE medical_record r JOIN medical_record_version v ON v.record_id=r.id AND v.revision_no=2
            SET r.content_json=v.after_content_json,r.revision_no=2,r.version=2 WHERE r.id=-9001
            """);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private record DemoUser(long id, String username, String displayName) {
    }
}
