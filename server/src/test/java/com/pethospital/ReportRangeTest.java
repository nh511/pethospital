package com.pethospital;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pethospital.reporting.ReportDtos.AppointmentCohort;
import com.pethospital.reporting.ReportDtos.Granularity;
import com.pethospital.reporting.ReportDtos.Metric;
import com.pethospital.reporting.ReportQuerySupport;
import com.pethospital.reporting.ReportRange;
import com.pethospital.shared.BusinessException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReportRangeTest {
    @Test
    void parsesOffsetTimestampsToUtcAndDisplaysShanghai() {
        ReportRange range = ReportRange.parse(
            "2026-10-01T00:00:00+08:00",
            "2026-11-01T00:00:00+08:00",
            "month"
        );

        assertThat(range.fromInclusive()).isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
        assertThat(range.toExclusive()).isEqualTo(Instant.parse("2026-10-31T16:00:00Z"));
        assertThat(range.displayFrom().toString()).isEqualTo("2026-10-01T00:00+08:00");
        assertThat(range.granularity()).isEqualTo(Granularity.MONTH);
    }

    @Test
    void containsStartButExcludesEnd() {
        ReportRange range = ReportRange.parse(
            "2026-10-01T00:00:00+08:00",
            "2026-10-02T00:00:00+08:00",
            "DAY"
        );

        assertThat(range.contains(range.fromInclusive())).isTrue();
        assertThat(range.contains(range.toExclusive().minusNanos(1))).isTrue();
        assertThat(range.contains(range.toExclusive())).isFalse();
    }

    @Test
    void acceptsExactly366DaysAndRejectsLongerOrReversedRanges() {
        Instant start = Instant.parse("2025-01-01T00:00:00Z");
        assertThat(new ReportRange(start, start.plus(Duration.ofDays(366)), Granularity.DAY))
            .isNotNull();
        assertThatThrownBy(() -> new ReportRange(
            start,
            start.plus(Duration.ofDays(366)).plusSeconds(1),
            Granularity.DAY
        )).isInstanceOf(BusinessException.class)
            .extracting(exception -> ((BusinessException) exception).code)
            .isEqualTo("INVALID_REPORT_RANGE");
        assertThatThrownBy(() -> ReportRange.parse(
            "2026-10-02T00:00:00+08:00",
            "2026-10-01T00:00:00+08:00",
            "DAY"
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void dayBucketsAreContiguousAcrossMonthAndClipPartialEdges() {
        ReportRange range = ReportRange.parse(
            "2026-01-31T12:00:00+08:00",
            "2026-02-02T06:00:00+08:00",
            "DAY"
        );
        var buckets = range.buckets();

        assertThat(buckets).hasSize(3);
        assertThat(buckets.getFirst().from()).isEqualTo(range.displayFrom());
        assertThat(buckets.getLast().to()).isEqualTo(range.displayTo());
        for (int index = 1; index < buckets.size(); index++) {
            assertThat(buckets.get(index - 1).to()).isEqualTo(buckets.get(index).from());
        }
    }

    @Test
    void monthBucketsDoNotDuplicateBoundaryInstant() {
        ReportRange range = ReportRange.parse(
            "2025-12-15T00:00:00+08:00",
            "2026-02-01T00:00:00+08:00",
            "MONTH"
        );
        var buckets = range.buckets();

        assertThat(buckets).hasSize(2);
        assertThat(buckets.get(0).to()).isEqualTo(buckets.get(1).from());
        assertThat(buckets.get(1).to()).isEqualTo(range.displayTo());
    }

    @Test
    void zeroDenominatorProducesNullInsteadOfNanOrZeroPercent() {
        Metric empty = Metric.ratio(0, 0);
        Metric ratio = Metric.ratio(1, 3);

        assertThat(empty.value()).isNull();
        assertThat(ratio.value()).isEqualByComparingTo(new BigDecimal("0.333333"));
        assertThat(Metric.count(0).value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void queryColumnsComeOnlyFromClosedCohortEnum() {
        ReportRange range = ReportRange.parse(
            "2026-10-01T00:00:00+08:00",
            "2026-11-01T00:00:00+08:00",
            "DAY"
        );
        ReportQuerySupport support = new ReportQuerySupport();

        assertThat(support.appointmentWindow(range, AppointmentCohort.CREATED).predicate("a"))
            .isEqualTo("a.created_at >= ? AND a.created_at < ?");
        assertThat(support.appointmentWindow(range, AppointmentCohort.SCHEDULED).predicate("a"))
            .isEqualTo("a.scheduled_start_at >= ? AND a.scheduled_start_at < ?");
        assertThatThrownBy(() -> support.appointmentWindow(range, AppointmentCohort.CREATED)
            .predicate("a;DROP_TABLE"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
