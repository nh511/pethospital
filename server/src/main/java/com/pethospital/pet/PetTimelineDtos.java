package com.pethospital.pet;

import java.util.List;

public final class PetTimelineDtos {
    private PetTimelineDtos() { }

    public enum TimelineType { LOG, MEDICAL, AI, APPOINTMENT }

    public record TimelineItem(TimelineType type, String id, String occurredAt, String summary) { }

    public record TimelinePage(List<TimelineItem> items, String nextCursor) { }
}
