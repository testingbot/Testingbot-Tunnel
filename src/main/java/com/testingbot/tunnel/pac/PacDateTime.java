package com.testingbot.tunnel.pac;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;

/**
 * The time-based PAC predicates: {@code weekdayRange}, {@code dateRange} and {@code timeRange}.
 *
 * <p>Separated from the interpreter because their argument handling is fiddly in its own right:
 * each takes a variable number of arguments with an optional trailing {@code "GMT"}, and the
 * one- and two-argument forms mean different things.
 *
 * <p>These are rare in modern PAC files but do appear in ones that route differently during
 * business hours. Getting them wrong would send traffic the wrong way for part of the day, which
 * is exactly the sort of intermittent fault nobody enjoys diagnosing.
 */
final class PacDateTime {

    private PacDateTime() {
    }

    private static final List<String> DAYS =
            List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private static final List<String> MONTHS =
            List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");

    /** Strips a trailing "GMT" and reports whether it was there. */
    private static boolean useGmt(List<String> args) {
        return !args.isEmpty() && args.get(args.size() - 1).equalsIgnoreCase("GMT");
    }

    private static List<String> withoutZone(List<String> args) {
        return useGmt(args) ? args.subList(0, args.size() - 1) : args;
    }

    private static ZonedDateTime at(ZonedDateTime now, List<String> args) {
        return useGmt(args) ? now.withZoneSameInstant(ZoneOffset.UTC) : now;
    }

    /** {@code weekdayRange("MON")} or {@code weekdayRange("MON", "FRI")}, optional "GMT". */
    static boolean weekdayRange(List<String> rawArgs, ZonedDateTime now) {
        List<String> args = withoutZone(rawArgs);
        if (args.isEmpty()) {
            return false;
        }
        int today = dayIndex(at(now, rawArgs).getDayOfWeek());
        int from = DAYS.indexOf(args.get(0).toUpperCase(Locale.ROOT));
        if (from < 0) {
            return false;
        }
        if (args.size() == 1) {
            return today == from;
        }
        int to = DAYS.indexOf(args.get(1).toUpperCase(Locale.ROOT));
        if (to < 0) {
            return false;
        }
        // Ranges wrap: weekdayRange("FRI", "MON") spans the weekend.
        return from <= to ? today >= from && today <= to : today >= from || today <= to;
    }

    private static int dayIndex(DayOfWeek day) {
        return day == DayOfWeek.SUNDAY ? 0 : day.getValue();
    }

    /**
     * {@code dateRange} in all its documented shapes: a day, a month, a year, or a range of any
     * of those, with 1, 2, 4 or 6 arguments.
     */
    static boolean dateRange(List<String> rawArgs, ZonedDateTime now) {
        List<String> args = withoutZone(rawArgs);
        ZonedDateTime when = at(now, rawArgs);
        int day = when.getDayOfMonth();
        int month = when.getMonthValue();
        int year = when.getYear();

        try {
            switch (args.size()) {
                case 1: {
                    String only = args.get(0);
                    if (isMonth(only)) {
                        return month == monthValue(only);
                    }
                    int value = Integer.parseInt(only);
                    return value > 31 ? year == value : day == value;
                }
                case 2: {
                    String a = args.get(0);
                    String b = args.get(1);
                    if (isMonth(a) && isMonth(b)) {
                        return inRange(month, monthValue(a), monthValue(b), 12);
                    }
                    int from = Integer.parseInt(a);
                    int to = Integer.parseInt(b);
                    return from > 31
                            ? year >= from && year <= to
                            : inRange(day, from, to, 31);
                }
                case 4: {
                    // day1, month1, day2, month2
                    int fromDay = Integer.parseInt(args.get(0));
                    int fromMonth = monthValue(args.get(1));
                    int toDay = Integer.parseInt(args.get(2));
                    int toMonth = monthValue(args.get(3));
                    int current = month * 100 + day;
                    return inRange(current, fromMonth * 100 + fromDay,
                            toMonth * 100 + toDay, 1231);
                }
                case 6: {
                    // day1, month1, year1, day2, month2, year2
                    long from = Integer.parseInt(args.get(2)) * 10000L
                            + monthValue(args.get(1)) * 100L + Integer.parseInt(args.get(0));
                    long to = Integer.parseInt(args.get(5)) * 10000L
                            + monthValue(args.get(4)) * 100L + Integer.parseInt(args.get(3));
                    long current = year * 10000L + month * 100L + day;
                    return current >= from && current <= to;
                }
                default:
                    return false;
            }
        } catch (IllegalArgumentException malformed) {
            // A malformed dateRange must not take down the whole evaluation; the surrounding
            // PAC logic almost always has a DIRECT fallback.
            return false;
        }
    }

    /** {@code timeRange} with 1, 2, 4 or 6 arguments, optional "GMT". */
    static boolean timeRange(List<String> rawArgs, ZonedDateTime now) {
        List<String> args = withoutZone(rawArgs);
        LocalTime when = at(now, rawArgs).toLocalTime();
        int seconds = when.toSecondOfDay();

        try {
            switch (args.size()) {
                case 1:
                    return when.getHour() == Integer.parseInt(args.get(0));
                case 2: {
                    int from = Integer.parseInt(args.get(0)) * 3600;
                    int to = Integer.parseInt(args.get(1)) * 3600;
                    return wrappingRange(seconds, from, to, 86400);
                }
                case 4: {
                    int from = Integer.parseInt(args.get(0)) * 3600
                            + Integer.parseInt(args.get(1)) * 60;
                    int to = Integer.parseInt(args.get(2)) * 3600
                            + Integer.parseInt(args.get(3)) * 60;
                    return wrappingRange(seconds, from, to, 86400);
                }
                case 6: {
                    int from = Integer.parseInt(args.get(0)) * 3600
                            + Integer.parseInt(args.get(1)) * 60 + Integer.parseInt(args.get(2));
                    int to = Integer.parseInt(args.get(3)) * 3600
                            + Integer.parseInt(args.get(4)) * 60 + Integer.parseInt(args.get(5));
                    return wrappingRange(seconds, from, to, 86400);
                }
                default:
                    return false;
            }
        } catch (NumberFormatException malformed) {
            return false;
        }
    }

    private static boolean inRange(int value, int from, int to, int wrapAt) {
        return from <= to ? value >= from && value <= to : value >= from || value <= to;
    }

    private static boolean wrappingRange(int value, int from, int to, int period) {
        return from <= to ? value >= from && value <= to : value >= from || value <= to;
    }

    private static boolean isMonth(String text) {
        return MONTHS.contains(text.toUpperCase(Locale.ROOT));
    }

    private static int monthValue(String text) {
        int index = MONTHS.indexOf(text.toUpperCase(Locale.ROOT));
        if (index < 0) {
            throw new IllegalArgumentException("Not a month: " + text);
        }
        return index + 1;
    }
}
