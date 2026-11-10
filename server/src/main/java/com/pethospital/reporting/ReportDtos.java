package com.pethospital.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Objects;

public final class ReportDtos {
    private ReportDtos() {
    }

    public enum Granularity {
        DAY,
        WEEK,
        MONTH
    }

    public enum AppointmentCohort {
        CREATED("created_at"),
        SCHEDULED("scheduled_start_at");

        private final String column;

        AppointmentCohort(String column) {
            this.column = column;
        }

        String column() {
            return column;
        }
    }

    public record TimeBucket(
        OffsetDateTime from,
        OffsetDateTime to,
        String label
    ) {
        public TimeBucket {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            label = Objects.requireNonNull(label, "label");
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("time bucket from must be before to");
            }
        }
    }

    public record Metric(
        long numerator,
        Long denominator,
        BigDecimal value
    ) {
        public Metric {
            if (numerator < 0 || denominator != null && denominator < 0) {
                throw new IllegalArgumentException("metric values must not be negative");
            }
            if (denominator != null && denominator == 0 && value != null) {
                throw new IllegalArgumentException("zero denominator requires null value");
            }
        }

        public static Metric ratio(long numerator, long denominator) {
            if (numerator < 0 || denominator < 0) {
                throw new IllegalArgumentException("metric values must not be negative");
            }
            BigDecimal value = denominator == 0
                ? null
                : BigDecimal.valueOf(numerator)
                    .divide(BigDecimal.valueOf(denominator), 6, RoundingMode.HALF_UP);
            return new Metric(numerator, denominator, value);
        }

        public static Metric count(long count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            return new Metric(count, null, BigDecimal.valueOf(count));
        }
    }

    public record Metadata(
        String timezone,
        OffsetDateTime from,
        OffsetDateTime to,
        OffsetDateTime generatedAt,
        AppointmentCohort cohortBasis
    ) {
        public Metadata {
            timezone = Objects.requireNonNull(timezone, "timezone");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(generatedAt, "generatedAt");
        }
    }
}
