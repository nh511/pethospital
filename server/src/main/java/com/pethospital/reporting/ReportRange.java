package com.pethospital.reporting;

import com.pethospital.reporting.ReportDtos.AppointmentCohort;
import com.pethospital.reporting.ReportDtos.Granularity;
import com.pethospital.reporting.ReportDtos.Metadata;
import com.pethospital.reporting.ReportDtos.TimeBucket;
import com.pethospital.shared.BusinessException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;

public record ReportRange(
    Instant fromInclusive,
    Instant toExclusive,
    Granularity granularity
) {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    public static final Duration MAX_RANGE = Duration.ofDays(366);

    public ReportRange {
        Objects.requireNonNull(fromInclusive, "fromInclusive");
        Objects.requireNonNull(toExclusive, "toExclusive");
        Objects.requireNonNull(granularity, "granularity");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw invalid("from must be before to");
        }
        if (Duration.between(fromInclusive, toExclusive).compareTo(MAX_RANGE) > 0) {
            throw invalid("report range must not exceed 366 days");
        }
    }

    public static ReportRange parse(String from, String to, String granularity) {
        try {
            Instant fromInstant = OffsetDateTime.parse(requireText(from, "from")).toInstant();
            Instant toInstant = OffsetDateTime.parse(requireText(to, "to")).toInstant();
            Granularity parsedGranularity = Granularity.valueOf(
                requireText(granularity, "granularity").toUpperCase(Locale.ROOT)
            );
            return new ReportRange(fromInstant, toInstant, parsedGranularity);
        } catch (DateTimeParseException exception) {
            throw invalid("from and to must be ISO 8601 timestamps with offsets");
        } catch (IllegalArgumentException exception) {
            throw invalid("granularity must be DAY, WEEK or MONTH");
        }
    }

    public boolean contains(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return !instant.isBefore(fromInclusive) && instant.isBefore(toExclusive);
    }

    public OffsetDateTime displayFrom() {
        return fromInclusive.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }

    public OffsetDateTime displayTo() {
        return toExclusive.atZone(DISPLAY_ZONE).toOffsetDateTime();
    }

    public List<TimeBucket> buckets() {
        var result = new ArrayList<TimeBucket>();
        Instant cursor = fromInclusive;
        while (cursor.isBefore(toExclusive)) {
            ZonedDateTime localCursor = cursor.atZone(DISPLAY_ZONE);
            Instant naturalEnd = nextBoundary(localCursor).toInstant();
            Instant end = naturalEnd.isAfter(toExclusive) ? toExclusive : naturalEnd;
            result.add(new TimeBucket(
                localCursor.toOffsetDateTime(),
                end.atZone(DISPLAY_ZONE).toOffsetDateTime(),
                bucketLabel(localCursor)
            ));
            cursor = end;
        }
        return List.copyOf(result);
    }

    public Metadata metadata(Clock clock, AppointmentCohort cohortBasis) {
        Objects.requireNonNull(clock, "clock");
        return new Metadata(
            DISPLAY_ZONE.getId(),
            displayFrom(),
            displayTo(),
            clock.instant().atZone(DISPLAY_ZONE).toOffsetDateTime(),
            cohortBasis
        );
    }

    private ZonedDateTime nextBoundary(ZonedDateTime cursor) {
        return switch (granularity) {
            case DAY -> cursor.toLocalDate().plusDays(1).atStartOfDay(DISPLAY_ZONE);
            case WEEK -> cursor.toLocalDate()
                .with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .atStartOfDay(DISPLAY_ZONE);
            case MONTH -> cursor.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(DISPLAY_ZONE);
        };
    }

    private String bucketLabel(ZonedDateTime cursor) {
        return switch (granularity) {
            case DAY -> cursor.toLocalDate().toString();
            case WEEK -> "WEEK_OF_" + cursor.toLocalDate();
            case MONTH -> "%04d-%02d".formatted(cursor.getYear(), cursor.getMonthValue());
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        return value.strip();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_RANGE", message);
    }
}
