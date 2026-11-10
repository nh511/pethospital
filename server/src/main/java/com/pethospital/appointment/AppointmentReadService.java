package com.pethospital.appointment;

import com.pethospital.appointment.AppointmentReadDtos.AppointmentDetail;
import com.pethospital.appointment.AppointmentReadDtos.AppointmentPage;
import com.pethospital.appointment.AppointmentReadDtos.AppointmentStatusEntry;
import com.pethospital.appointment.AppointmentReadDtos.AppointmentSummary;
import com.pethospital.appointment.AppointmentReadQuery.Row;
import com.pethospital.appointment.AppointmentStateMachine.Action;
import com.pethospital.appointment.AppointmentStateMachine.Actor;
import com.pethospital.appointment.AppointmentStateMachine.Status;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentRole;
import com.pethospital.identity.ResourceAccessPolicy.AppointmentViewer;
import com.pethospital.shared.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AppointmentReadService {
    private final AppointmentStateMachine rules;
    private final ResourceAccessPolicy access;
    private final AppointmentReadQuery query;
    private final Clock clock;

    public AppointmentReadService(
        ResourceAccessPolicy access, AppointmentReadQuery query, Clock clock, AppointmentStateMachine rules
    ) {
        this.access = access;
        this.query = query;
        this.clock = clock;
        this.rules = rules;
    }

    public AppointmentPage list(Status status, LocalDate date, Long petId, int page, int size) {
        AppointmentViewer viewer = access.requireAppointmentViewer();
        if (date != null && (date.getYear() < 1000 || date.getYear() > 9999)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPOINTMENT_DATE", "预约筛选日期超出范围");
        }
        var rows = query.list(viewer, status, date, petId, size, (page - 1L) * size);
        Instant now = clock.instant();
        List<AppointmentSummary> items = rows.items().stream().map(row -> summary(row, viewer, now)).toList();
        return new AppointmentPage(items, page, size, rows.total());
    }

    public AppointmentDetail detail(long id) {
        AppointmentViewer viewer = access.requireAppointmentViewer();
        Row row = requireAppointment(viewer, id);
        AppointmentSummary summary = summary(row, viewer, clock.instant());
        if (limited(row, viewer)) {
            return new AppointmentDetail(summary, row.chiefComplaint(), null, null, null, null, null, null, null);
        }
        String aiSessionId = viewer.role() == AppointmentRole.USER ? row.aiSessionId() : null;
        return new AppointmentDetail(summary, row.chiefComplaint(), aiSessionId,
            row.confirmed(), row.started(), row.completed(), row.cancelled(), row.cancelledBy(), row.terminalReason());
    }

    public List<AppointmentStatusEntry> statusLogs(long id) {
        requireAppointment(access.requireAppointmentViewer(), id);
        return query.statusLogs(id);
    }

    private Row requireAppointment(AppointmentViewer viewer, long id) {
        Row row = query.find(viewer, id);
        if (row == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", "预约不存在");
        }
        return row;
    }

    private AppointmentSummary summary(Row row, AppointmentViewer viewer, Instant now) {
        Actor actor = Actor.valueOf(viewer.role().name());
        List<Action> actions = Arrays.stream(Action.values()).filter(action -> allowed(row, action, actor, now)).toList();
        return new AppointmentSummary(row.id(), row.appointmentNo(), row.doctorId(), row.doctorName(),
            limited(row, viewer) ? null : row.petId(), row.petName(), row.status(), row.start(), row.end(), row.deadline(),
            row.created(), row.version(), actions);
    }

    private static boolean limited(Row row, AppointmentViewer viewer) {
        return viewer.role() != AppointmentRole.USER && row.status() == Status.PENDING;
    }

    private boolean allowed(Row row, Action action, Actor actor, Instant now) {
        try {
            rules.transition(row.status(), action, actor, now, row.start(), row.deadline());
            return true;
        } catch (BusinessException unavailable) {
            return false;
        }
    }
}
