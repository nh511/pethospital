package com.pethospital.pet;

import com.pethospital.identity.CurrentIdentity;
import com.pethospital.identity.CurrentIdentityProvider;
import com.pethospital.identity.ResourceAccessPolicy;
import com.pethospital.identity.ResourceAccessPolicy.PetAccess;
import com.pethospital.pet.PetTimelineDtos.TimelineItem;
import com.pethospital.pet.PetTimelineDtos.TimelinePage;
import com.pethospital.pet.PetTimelineDtos.TimelineType;
import com.pethospital.shared.BusinessException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetTimelineService {
    private final CurrentIdentityProvider identities;
    private final ResourceAccessPolicy access;
    private final PetTimelineQuery query;

    public PetTimelineService(CurrentIdentityProvider identities, ResourceAccessPolicy access, PetTimelineQuery query) {
        this.identities = identities;
        this.access = access;
        this.query = query;
    }

    @Transactional(readOnly = true)
    public TimelinePage list(long petId, TimelineType type, String cursor, int pageSize) {
        CurrentIdentity actor = identities.require();
        if (!"MINI".equals(actor.clientType()) || !actor.roles().contains("USER")) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权访问宠物时间线");
        }
        if (access.pet(petId) != PetAccess.OWNER) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PET_NOT_FOUND", "宠物不存在");
        }

        String scope = petId + "." + (type == null ? "ALL" : type.name()) + ".";
        Cursor position = cursor == null ? null : Cursor.parse(cursor, scope, type);
        List<TimelineItem> rows = query.list(petId, actor.userId(), type, position, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new TimelinePage(rows, null);
        }
        TimelineItem last = rows.get(pageSize - 1);
        String next = scope + Instant.parse(last.occurredAt()).toEpochMilli() + "." + last.id() + "." + last.type();
        return new TimelinePage(List.copyOf(rows.subList(0, pageSize)), next);
    }

    record Cursor(Instant time, long id, TimelineType type) {
        private static final Instant MIN_TIME = Instant.parse("1000-01-01T00:00:00Z");
        private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999Z");

        static Cursor parse(String cursor, String scope, TimelineType filter) {
            try {
                if (cursor.startsWith(scope)) {
                    String[] parts = cursor.substring(scope.length()).split("\\.", -1);
                    if (parts.length == 3) {
                        Instant time = Instant.ofEpochMilli(Long.parseLong(parts[0]));
                        long id = Long.parseLong(parts[1]);
                        TimelineType type = TimelineType.valueOf(parts[2]);
                        if (id > 0 && !time.isBefore(MIN_TIME) && !time.isAfter(MAX_TIME)
                            && (filter == null || type == filter)) {
                            return new Cursor(time, id, type);
                        }
                    }
                }
            } catch (IllegalArgumentException invalidCursor) {
                // 数值和枚举解析错误统一返回无效游标。
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIMELINE_CURSOR", "时间线游标无效");
        }
    }
}
