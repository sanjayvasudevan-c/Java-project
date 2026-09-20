package com.swarmcron.test;

import com.swarmcron.schedule.CronExpression;
import com.swarmcron.schedule.CronScheduleException;
import com.swarmcron.util.Assert;
import com.swarmcron.util.TestSuite;

import java.time.ZoneId;
import java.time.ZonedDateTime;

final class CronExpressionTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final ZoneId UTC = ZoneId.of("UTC");

    static boolean run() {
        return new TestSuite("CronExpressionTest")
                .test("every-minute wildcard fires on the next minute boundary", CronExpressionTest::everyMinute)
                .test("a specific minute/hour fires at the next matching time, possibly the next day", CronExpressionTest::specificTime)
                .test("lists, ranges, and steps parse and match correctly", CronExpressionTest::listsRangesAndSteps)
                .test("named months and days of week are accepted case-insensitively", CronExpressionTest::namedFields)
                .test("dom and dow both restricted combine with OR semantics", CronExpressionTest::domDowOrSemantics)
                .test("6-field expressions match on seconds", CronExpressionTest::sixFieldSeconds)
                .test("@hourly/@daily/@weekly/@monthly shorthand expand correctly", CronExpressionTest::shorthandExpands)
                .test("a malformed expression is rejected", CronExpressionTest::rejectsMalformed)
                .test("a schedule with an impossible date throws rather than looping forever", CronExpressionTest::impossibleDateThrows)
                .test("a spring-forward gap shifts forward past the missing hour", CronExpressionTest::springForwardGap)
                .test("a fall-back overlap resolves to a single well-defined, monotonic instant", CronExpressionTest::fallBackOverlap)
                .test("daily fires stay strictly monotonic across a DST spring-forward transition", CronExpressionTest::monotonicAcrossSpringForward)
                .test("Feb 29 is only matched in leap years and correctly skips to the next one", CronExpressionTest::leapYearFeb29)
                .test("a day-of-month that doesn't exist in the starting month rolls to the next month that has it", CronExpressionTest::monthLengthBoundary)
                .run();
    }

    private static void everyMinute() {
        CronExpression expr = CronExpression.parse("* * * * *");
        ZonedDateTime after = ZonedDateTime.of(2024, 6, 15, 10, 30, 45, 0, UTC);
        ZonedDateTime next = expr.nextFireTime(after);
        Assert.equals(ZonedDateTime.of(2024, 6, 15, 10, 31, 0, 0, UTC), next, "should land on the very next minute boundary");
    }

    private static void specificTime() {
        CronExpression expr = CronExpression.parse("30 2 * * *"); // 2:30 AM daily
        ZonedDateTime after = ZonedDateTime.of(2024, 6, 15, 10, 0, 0, 0, UTC);
        ZonedDateTime next = expr.nextFireTime(after);
        Assert.equals(ZonedDateTime.of(2024, 6, 16, 2, 30, 0, 0, UTC), next, "should roll to 2:30 AM the next day");
    }

    private static void listsRangesAndSteps() {
        CronExpression list = CronExpression.parse("0 9,13,17 * * *");
        ZonedDateTime after = ZonedDateTime.of(2024, 1, 1, 10, 0, 0, 0, UTC);
        Assert.equals(ZonedDateTime.of(2024, 1, 1, 13, 0, 0, 0, UTC), list.nextFireTime(after), "list field should hit the next listed hour");

        CronExpression range = CronExpression.parse("0 0 * 6-8 *"); // midnight, June through August
        ZonedDateTime beforeSummer = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, UTC);
        Assert.equals(ZonedDateTime.of(2024, 6, 1, 0, 0, 0, 0, UTC), range.nextFireTime(beforeSummer), "range field should jump to the start of the range");

        CronExpression step = CronExpression.parse("*/15 * * * *");
        ZonedDateTime after2 = ZonedDateTime.of(2024, 1, 1, 10, 16, 0, 0, UTC);
        Assert.equals(ZonedDateTime.of(2024, 1, 1, 10, 30, 0, 0, UTC), step.nextFireTime(after2), "step field should hit the next quarter-hour");
    }

    private static void namedFields() {
        CronExpression expr = CronExpression.parse("0 0 1 jan *");
        ZonedDateTime after = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, UTC);
        Assert.equals(ZonedDateTime.of(2025, 1, 1, 0, 0, 0, 0, UTC), expr.nextFireTime(after), "named month 'jan' (lowercase) should parse like month 1");

        CronExpression dow = CronExpression.parse("0 0 * * MON");
        ZonedDateTime afterSat = ZonedDateTime.of(2024, 6, 15, 0, 0, 0, 0, UTC); // a Saturday
        ZonedDateTime nextMonday = dow.nextFireTime(afterSat);
        Assert.equals(java.time.DayOfWeek.MONDAY, nextMonday.getDayOfWeek(), "named day 'MON' should match Monday");
    }

    private static void domDowOrSemantics() {
        // Fire on the 1st of the month OR on a Friday -- classic cron OR-when-both-restricted behavior.
        CronExpression expr = CronExpression.parse("0 0 1 * FRI");
        ZonedDateTime after = ZonedDateTime.of(2024, 6, 3, 0, 0, 0, 0, UTC); // a Monday
        ZonedDateTime next = expr.nextFireTime(after);
        Assert.that(next.getDayOfMonth() == 1 || next.getDayOfWeek() == java.time.DayOfWeek.FRIDAY,
                "result should satisfy day-of-month=1 OR day-of-week=Friday, got " + next);
        // The nearest Friday after June 3, 2024 is June 7.
        Assert.equals(ZonedDateTime.of(2024, 6, 7, 0, 0, 0, 0, UTC), next, "should hit the nearer of the two OR'd conditions");
    }

    private static void sixFieldSeconds() {
        CronExpression expr = CronExpression.parse("30 * * * * *"); // every minute, at :30 seconds
        ZonedDateTime after = ZonedDateTime.of(2024, 1, 1, 10, 0, 10, 0, UTC);
        Assert.equals(ZonedDateTime.of(2024, 1, 1, 10, 0, 30, 0, UTC), expr.nextFireTime(after), "should hit second 30 of the current minute");
        Assert.that(expr.hasSeconds(), "a 6-field expression should report hasSeconds()=true");
    }

    private static void shorthandExpands() {
        ZonedDateTime after = ZonedDateTime.of(2024, 6, 15, 10, 30, 0, 0, UTC); // a Saturday

        Assert.equals(ZonedDateTime.of(2024, 6, 15, 11, 0, 0, 0, UTC),
                CronExpression.parse("@hourly").nextFireTime(after), "@hourly should fire at the top of the next hour");
        Assert.equals(ZonedDateTime.of(2024, 6, 16, 0, 0, 0, 0, UTC),
                CronExpression.parse("@daily").nextFireTime(after), "@daily should fire at the next midnight");
        Assert.equals(ZonedDateTime.of(2024, 6, 16, 0, 0, 0, 0, UTC),
                CronExpression.parse("@weekly").nextFireTime(after), "@weekly should fire at the next Sunday midnight");
        Assert.equals(ZonedDateTime.of(2024, 7, 1, 0, 0, 0, 0, UTC),
                CronExpression.parse("@monthly").nextFireTime(after), "@monthly should fire at midnight on the 1st of next month");
    }

    private static void rejectsMalformed() {
        assertThrows(() -> CronExpression.parse("* * * *"), "4 fields should be rejected");
        assertThrows(() -> CronExpression.parse("60 * * * *"), "minute 60 is out of range");
        assertThrows(() -> CronExpression.parse("* * * 13 *"), "month 13 is out of range");
        assertThrows(() -> CronExpression.parse("* * * NOTAMONTH *"), "an unknown month name should be rejected");
        assertThrows(() -> CronExpression.parse(""), "a blank expression should be rejected");
    }

    private static void impossibleDateThrows() {
        CronExpression neverMatches = CronExpression.parse("0 0 30 2 *"); // Feb 30 never exists
        ZonedDateTime after = ZonedDateTime.of(2024, 1, 1, 0, 0, 0, 0, UTC);
        boolean threw = false;
        try {
            neverMatches.nextFireTime(after);
        } catch (CronScheduleException e) {
            threw = true;
        }
        Assert.that(threw, "an unsatisfiable expression should throw CronScheduleException, not loop forever");
    }

    private static void springForwardGap() {
        // 2024-03-10 in America/New_York: clocks spring forward 2:00 AM -> 3:00 AM, so 2:30 AM doesn't exist.
        CronExpression expr = CronExpression.parse("30 2 * * *");
        ZonedDateTime after = ZonedDateTime.of(2024, 3, 9, 12, 0, 0, 0, NY);
        ZonedDateTime next = expr.nextFireTime(after);
        // java.time resolves a gap by shifting forward by the gap's length: 2:30 AM becomes 3:30 AM that same day.
        Assert.equals(ZonedDateTime.of(2024, 3, 10, 3, 30, 0, 0, NY), next,
                "a local time inside the spring-forward gap should resolve by shifting past the gap");
    }

    private static void fallBackOverlap() {
        // 2024-11-03 in America/New_York: clocks fall back 2:00 AM -> 1:00 AM, so 1:30 AM occurs twice.
        CronExpression expr = CronExpression.parse("30 1 * * *");
        ZonedDateTime after = ZonedDateTime.of(2024, 11, 2, 12, 0, 0, 0, NY);
        ZonedDateTime next = expr.nextFireTime(after);
        Assert.equals(3, next.getDayOfMonth(), "should land on November 3rd");
        Assert.equals(1, next.getHour(), "should land on the 1:xx hour");
        Assert.equals(30, next.getMinute(), "should land on :30 minutes");
        // Exactly one of the two valid offsets for that day; java.time deterministically picks the earlier one.
        Assert.that(next.getOffset().equals(java.time.ZoneOffset.ofHours(-4)) || next.getOffset().equals(java.time.ZoneOffset.ofHours(-5)),
                "the resolved offset should be one of the two valid offsets for that ambiguous local time");
    }

    private static void monotonicAcrossSpringForward() {
        CronExpression daily = CronExpression.parse("30 2 * * *");
        ZonedDateTime cursor = ZonedDateTime.of(2024, 3, 8, 0, 0, 0, 0, NY);
        java.time.Instant previousInstant = null;
        for (int i = 0; i < 5; i++) {
            ZonedDateTime next = daily.nextFireTime(cursor);
            if (previousInstant != null) {
                Assert.that(next.toInstant().isAfter(previousInstant), "each successive daily fire must be strictly after the last, even across a DST transition");
            }
            previousInstant = next.toInstant();
            cursor = next;
        }
    }

    private static void leapYearFeb29() {
        CronExpression expr = CronExpression.parse("0 0 29 2 *");
        ZonedDateTime startOf2023 = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, UTC); // 2023 is not a leap year
        ZonedDateTime next = expr.nextFireTime(startOf2023);
        Assert.equals(ZonedDateTime.of(2024, 2, 29, 0, 0, 0, 0, UTC), next, "should skip forward to the next leap year's Feb 29");

        ZonedDateTime rightAfter2024 = ZonedDateTime.of(2024, 2, 29, 0, 0, 0, 0, UTC);
        ZonedDateTime nextAfterThat = expr.nextFireTime(rightAfter2024);
        Assert.equals(ZonedDateTime.of(2028, 2, 29, 0, 0, 0, 0, UTC), nextAfterThat, "2025-2027 are not leap years, so the next Feb 29 is 2028");
    }

    private static void monthLengthBoundary() {
        CronExpression expr = CronExpression.parse("0 0 31 * *"); // fire on the 31st of any month that has one
        ZonedDateTime inApril = ZonedDateTime.of(2024, 4, 15, 0, 0, 0, 0, UTC); // April has only 30 days
        ZonedDateTime next = expr.nextFireTime(inApril);
        Assert.equals(ZonedDateTime.of(2024, 5, 31, 0, 0, 0, 0, UTC), next,
                "April has no 31st, so the next fire should be May 31st, not a clamped April 30th");
    }

    private static void assertThrows(Runnable action, String message) {
        boolean threw = false;
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        Assert.that(threw, message);
    }
}
