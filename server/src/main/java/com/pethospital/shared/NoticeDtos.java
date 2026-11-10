package com.pethospital.shared;

import java.util.List;

public final class NoticeDtos {
    private NoticeDtos() {
    }

    public enum NoticeType {
        APPOINTMENT_CREATED,
        APPOINTMENT_CONFIRMED,
        APPOINTMENT_REJECTED,
        APPOINTMENT_CANCELLED,
        APPOINTMENT_RESCHEDULED,
        APPOINTMENT_CHANGE_REJECTED,
        APPOINTMENT_CHANGE_WITHDRAWN,
        APPOINTMENT_CHANGE_EXPIRED,
        MEDICAL_COMPLETED
    }

    public record NoticeCommand(
        long recipientUserId,
        String eventKey,
        NoticeType noticeType,
        String resourceType,
        long resourceId
    ) {
    }

    public record NoticeView(
        String id,
        String noticeType,
        String title,
        String body,
        String resourceType,
        String resourceId,
        String readAt,
        String createdAt
    ) {
    }

    public record NoticePage(
        List<NoticeView> items,
        int page,
        int pageSize,
        long total
    ) {
        public NoticePage {
            items = List.copyOf(items);
        }
    }

    public record NoticeReadView(String id, String readAt) {
    }
}
