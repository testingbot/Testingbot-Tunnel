package com.testingbot.tunnel.pac;

import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The time-based predicates, against a fixed clock.
 *
 * <p>These decide routing for part of the day, so getting one wrong produces an intermittent
 * fault that is painful to trace back to the PAC file.
 */
class PacDateTimeTest {

    /** Wednesday, 15 March 2028, 14:30:00 UTC. */
    private static final ZonedDateTime WED_1430 =
            ZonedDateTime.of(2028, 3, 15, 14, 30, 0, 0, ZoneOffset.UTC);

    @Test
    void weekdayRange_singleDay() {
        assertThat(PacDateTime.weekdayRange(List.of("WED"), WED_1430)).isTrue();
        assertThat(PacDateTime.weekdayRange(List.of("THU"), WED_1430)).isFalse();
    }

    @Test
    void weekdayRange_span() {
        assertThat(PacDateTime.weekdayRange(List.of("MON", "FRI"), WED_1430)).isTrue();
        assertThat(PacDateTime.weekdayRange(List.of("SAT", "SUN"), WED_1430)).isFalse();
    }

    @Test
    void weekdayRange_wrapsAroundTheWeekend() {
        // FRI..MON spans Saturday and Sunday; Wednesday is outside it.
        assertThat(PacDateTime.weekdayRange(List.of("FRI", "MON"), WED_1430)).isFalse();
        assertThat(PacDateTime.weekdayRange(List.of("TUE", "THU"), WED_1430)).isTrue();
    }

    @Test
    void weekdayRange_acceptsTrailingGmt() {
        assertThat(PacDateTime.weekdayRange(List.of("WED", "GMT"), WED_1430)).isTrue();
        assertThat(PacDateTime.weekdayRange(List.of("MON", "FRI", "GMT"), WED_1430)).isTrue();
    }

    @Test
    void weekdayRange_unknownDayIsFalseNotAnError() {
        assertThat(PacDateTime.weekdayRange(List.of("FUNDAY"), WED_1430)).isFalse();
        assertThat(PacDateTime.weekdayRange(List.of(), WED_1430)).isFalse();
    }

    @Test
    void dateRange_dayOfMonth() {
        assertThat(PacDateTime.dateRange(List.of("15"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("16"), WED_1430)).isFalse();
    }

    @Test
    void dateRange_month() {
        assertThat(PacDateTime.dateRange(List.of("MAR"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("APR"), WED_1430)).isFalse();
    }

    @Test
    void dateRange_year() {
        // A value above 31 is a year, not a day.
        assertThat(PacDateTime.dateRange(List.of("2028"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("2029"), WED_1430)).isFalse();
    }

    @Test
    void dateRange_spans() {
        assertThat(PacDateTime.dateRange(List.of("JAN", "JUN"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("JUL", "DEC"), WED_1430)).isFalse();
        assertThat(PacDateTime.dateRange(List.of("10", "20"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("2027", "2029"), WED_1430)).isTrue();
    }

    @Test
    void dateRange_dayAndMonthSpan() {
        assertThat(PacDateTime.dateRange(List.of("1", "MAR", "31", "MAR"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(List.of("1", "APR", "30", "APR"), WED_1430)).isFalse();
    }

    @Test
    void dateRange_fullSpanWithYears() {
        assertThat(PacDateTime.dateRange(
                List.of("1", "JAN", "2028", "31", "DEC", "2028"), WED_1430)).isTrue();
        assertThat(PacDateTime.dateRange(
                List.of("1", "JAN", "2029", "31", "DEC", "2029"), WED_1430)).isFalse();
    }

    @Test
    void dateRange_malformedIsFalseNotAnError() {
        // The surrounding PAC logic nearly always has a DIRECT fallback; throwing here would
        // take the whole evaluation down instead.
        assertThat(PacDateTime.dateRange(List.of("NOTADATE"), WED_1430)).isFalse();
        assertThat(PacDateTime.dateRange(List.of("1", "NOTAMONTH", "2", "MAR"), WED_1430)).isFalse();
    }

    @Test
    void timeRange_hour() {
        assertThat(PacDateTime.timeRange(List.of("14"), WED_1430)).isTrue();
        assertThat(PacDateTime.timeRange(List.of("15"), WED_1430)).isFalse();
    }

    @Test
    void timeRange_hourSpan() {
        assertThat(PacDateTime.timeRange(List.of("9", "17"), WED_1430)).isTrue();
        assertThat(PacDateTime.timeRange(List.of("18", "23"), WED_1430)).isFalse();
    }

    @Test
    void timeRange_wrapsOverMidnight() {
        // 22:00..06:00 is the classic out-of-hours window; 14:30 is outside it.
        assertThat(PacDateTime.timeRange(List.of("22", "6"), WED_1430)).isFalse();
        assertThat(PacDateTime.timeRange(List.of("6", "22"), WED_1430)).isTrue();
    }

    @Test
    void timeRange_minuteAndSecondPrecision() {
        assertThat(PacDateTime.timeRange(List.of("14", "0", "14", "45"), WED_1430)).isTrue();
        assertThat(PacDateTime.timeRange(List.of("14", "35", "14", "45"), WED_1430)).isFalse();
        assertThat(PacDateTime.timeRange(
                List.of("14", "29", "0", "14", "31", "0"), WED_1430)).isTrue();
    }

    @Test
    void timeRange_malformedIsFalse() {
        assertThat(PacDateTime.timeRange(List.of("NOON"), WED_1430)).isFalse();
    }
}
