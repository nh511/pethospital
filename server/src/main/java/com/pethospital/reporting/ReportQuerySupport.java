package com.pethospital.reporting;

import com.pethospital.reporting.ReportDtos.AppointmentCohort;
import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public final class ReportQuerySupport {
    public QueryWindow appointmentWindow(ReportRange range, AppointmentCohort cohort) {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(cohort, "cohort");
        return new QueryWindow(
            cohort.column(),
            Timestamp.from(range.fromInclusive()),
            Timestamp.from(range.toExclusive())
        );
    }

    public record QueryWindow(String column, Timestamp fromInclusive, Timestamp toExclusive) {
        public QueryWindow {
            Objects.requireNonNull(column, "column");
            Objects.requireNonNull(fromInclusive, "fromInclusive");
            Objects.requireNonNull(toExclusive, "toExclusive");
        }

        public String predicate(String trustedAlias) {
            if (trustedAlias == null || !trustedAlias.matches("[A-Za-z][A-Za-z0-9_]{0,30}")) {
                throw new IllegalArgumentException("invalid trusted SQL alias");
            }
            return "%s.%s >= ? AND %s.%s < ?".formatted(
                trustedAlias, column, trustedAlias, column
            );
        }

        public Object[] parameters() {
            return new Object[] {fromInclusive, toExclusive};
        }
    }
}
