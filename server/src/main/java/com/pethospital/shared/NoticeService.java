package com.pethospital.shared;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.shared.AuditDtos.ActorType;
import com.pethospital.shared.AuditDtos.IdempotencyCommand;
import com.pethospital.shared.AuditDtos.IdempotentResult;
import com.pethospital.shared.NoticeDtos.NoticeCommand;
import com.pethospital.shared.NoticeDtos.NoticePage;
import com.pethospital.shared.NoticeDtos.NoticeReadView;
import com.pethospital.shared.NoticeDtos.NoticeType;
import com.pethospital.shared.NoticeDtos.NoticeView;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeService {
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final CurrentIdentityProvider identities;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public NoticeService(
        ObjectProvider<DataSource> dataSourceProvider,
        CurrentIdentityProvider identities,
        IdempotencyService idempotency,
        ObjectMapper objectMapper
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identities = identities;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long publish(NoticeCommand command) {
        validate(command);
        Template template = template(command.noticeType());
        requireResourceType(command.noticeType(), command.resourceType());
        long candidateId = IdWorker.getId();
        JdbcTemplate jdbc = jdbc();
        jdbc.update("""
            INSERT INTO user_notice(
              id,recipient_user_id,event_key,notice_type,title,body,resource_type,resource_id
            ) VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE id=id
            """,
            candidateId,
            command.recipientUserId(),
            command.eventKey().strip(),
            command.noticeType().name(),
            template.title(),
            template.body(),
            command.resourceType(),
            command.resourceId()
        );
        StoredNotice stored = jdbc.query("""
            SELECT id,notice_type,title,body,resource_type,resource_id
            FROM user_notice WHERE recipient_user_id=? AND event_key=? FOR UPDATE
            """, rs -> rs.next() ? new StoredNotice(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getLong(6)
        ) : null, command.recipientUserId(), command.eventKey().strip());
        if (stored == null) {
            throw new IllegalStateException("notice insert did not create or find a row");
        }
        if (!stored.matches(command, template)) {
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "NOTICE_EVENT_CONFLICT",
                "通知事件键已用于其他事件"
            );
        }
        return stored.id();
    }

    @Transactional(readOnly = true)
    public NoticePage list(Boolean unread, int page, int pageSize) {
        CurrentIdentity actor = identities.require();
        String condition = unread == null ? "" : unread ? " AND read_at IS NULL" : " AND read_at IS NOT NULL";
        JdbcTemplate jdbc = jdbc();
        long total = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_notice WHERE recipient_user_id=?" + condition,
            Long.class,
            actor.userId()
        );
        List<NoticeView> items = jdbc.query("""
            SELECT id,notice_type,title,body,resource_type,resource_id,read_at,created_at
            FROM user_notice WHERE recipient_user_id=?
            """ + condition + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs, row) -> new NoticeView(
                Long.toString(rs.getLong("id")),
                rs.getString("notice_type"),
                rs.getString("title"),
                rs.getString("body"),
                rs.getString("resource_type"),
                Long.toString(rs.getLong("resource_id")),
                instant(rs.getTimestamp("read_at")),
                instant(rs.getTimestamp("created_at"))
            ),
            actor.userId(), pageSize, Math.multiplyExact((long) page - 1L, pageSize)
        );
        return new NoticePage(items, page, pageSize, total);
    }

    @Transactional
    public NoticeReadView read(long noticeId, String operationKey) {
        CurrentIdentity actor = identities.require();
        var command = new IdempotencyCommand(
            actor.userId(), actorType(actor), "NOTICE_READ", "USER_NOTICE",
            "POST", "/api/v1/notices/" + noticeId + "/read", operationKey,
            Map.of(), null, null, null
        );
        return idempotency.execute(
            command,
            NoticeReadView.class,
            () -> markRead(actor.userId(), noticeId)
        );
    }

    private IdempotentResult<NoticeReadView> markRead(long actorUserId, long noticeId) {
        JdbcTemplate jdbc = jdbc();
        NoticeLock row = jdbc.query("""
            SELECT id,read_at FROM user_notice
            WHERE id=? AND recipient_user_id=? FOR UPDATE
            """, rs -> rs.next() ? new NoticeLock(rs.getLong(1), rs.getTimestamp(2)) : null,
            noticeId, actorUserId);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "消息不存在");
        }
        if (row.readAt() == null) {
            int updated = jdbc.update("""
                UPDATE user_notice SET read_at=CURRENT_TIMESTAMP(3)
                WHERE id=? AND recipient_user_id=? AND read_at IS NULL
                """, noticeId, actorUserId);
            if (updated != 1) {
                throw new IllegalStateException("notice did not transition to read");
            }
        }
        Timestamp readAt = jdbc.queryForObject(
            "SELECT read_at FROM user_notice WHERE id=?",
            Timestamp.class,
            noticeId
        );
        NoticeReadView view = new NoticeReadView(Long.toString(noticeId), instant(readAt));
        return new IdempotentResult<>(
            view,
            noticeId,
            objectMapper.valueToTree(Map.of("readAt", view.readAt()))
        );
    }

    private static void validate(NoticeCommand command) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(command.noticeType(), "noticeType");
        if (command.recipientUserId() <= 0 || command.resourceId() <= 0) {
            throw new IllegalArgumentException("notice recipient and resource IDs must be positive");
        }
        String eventKey = command.eventKey() == null ? "" : command.eventKey().strip();
        if (!eventKey.matches("[A-Za-z0-9._:-]{16,128}")) {
            throw new IllegalArgumentException("notice event key has invalid format");
        }
        String resourceType = command.resourceType() == null ? "" : command.resourceType().strip();
        if (!resourceType.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("notice resource type has invalid format");
        }
    }

    private static void requireResourceType(NoticeType type, String resourceType) {
        String required = type == NoticeType.MEDICAL_COMPLETED ? "MEDICAL_RECORD" : "APPOINTMENT";
        if (!required.equals(resourceType)) {
            throw new IllegalArgumentException("notice type and resource type do not match");
        }
    }

    private static Template template(NoticeType type) {
        return switch (type) {
            case APPOINTMENT_CREATED -> new Template("有新的待确认预约", "请打开医生工作台查看并处理预约。");
            case APPOINTMENT_CONFIRMED -> new Template("预约已确认", "您的预约状态已更新，请打开预约查看详情。");
            case APPOINTMENT_REJECTED -> new Template("预约未确认", "您的预约状态已更新，请打开预约查看详情。");
            case APPOINTMENT_CANCELLED -> new Template("预约已取消", "您的预约状态已更新，请打开预约查看详情。");
            case APPOINTMENT_RESCHEDULED -> new Template("预约时间已变更", "您的预约时间已更新，请打开预约查看详情。");
            case APPOINTMENT_CHANGE_REJECTED -> new Template("改期申请已拒绝", "请打开预约查看改期申请结果。");
            case APPOINTMENT_CHANGE_WITHDRAWN -> new Template("改期申请已撤回", "请打开预约查看改期申请结果。");
            case APPOINTMENT_CHANGE_EXPIRED -> new Template("改期申请已到期", "请打开预约查看改期申请结果。");
            case MEDICAL_COMPLETED -> new Template("诊疗记录已更新", "诊疗记录已更新，请打开记录查看详情。");
        };
    }

    private static ActorType actorType(CurrentIdentity actor) {
        if (actor.roles().contains("ADMIN")) return ActorType.ADMIN;
        if (actor.roles().contains("DOCTOR")) return ActorType.DOCTOR;
        return ActorType.USER;
    }

    private static String instant(Timestamp value) {
        return value == null ? null : value.toInstant().toString();
    }

    private JdbcTemplate jdbc() {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            throw new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "NOTICE_DEPENDENCY_UNAVAILABLE",
                "消息数据源不可用"
            );
        }
        return new JdbcTemplate(dataSource);
    }

    private record Template(String title, String body) {
    }

    private record NoticeLock(long id, Timestamp readAt) {
    }

    private record StoredNotice(
        long id,
        String noticeType,
        String title,
        String body,
        String resourceType,
        long resourceId
    ) {
        boolean matches(NoticeCommand command, Template template) {
            return noticeType.equals(command.noticeType().name())
                && title.equals(template.title())
                && body.equals(template.body())
                && resourceType.equals(command.resourceType())
                && resourceId == command.resourceId();
        }
    }
}
