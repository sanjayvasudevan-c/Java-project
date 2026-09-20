package com.swarmcron.schedule;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * Hand-rolled cron parser: 5 fields (m h dom mon dow) or 6 (s m h dom mon
 * dow), plus @hourly/@daily/@weekly/@monthly shorthand. Supports the
 * wildcard, lists (a,b,c), ranges (a-b), steps (a/n, a-b/n, wildcard/n), and
 * named months/days.
 *
 * nextFireTime() walks forward field by field (month, then day, then hour,
 * then minute, then second), jumping straight to the next candidate value
 * and resetting finer fields whenever a coarser one doesn't match, rather
 * than testing every unit of time one at a time. Working in LocalDateTime
 * (wall-clock, zone-agnostic) and converting to ZonedDateTime only once a
 * fully-matching candidate is found is what makes DST handling correct for
 * free: java.time's ZonedDateTime.of(LocalDateTime, ZoneId) resolves a local
 * time that falls in a spring-forward gap by shifting it past the gap, and
 * an ambiguous fall-back local time to one definite instant -- exactly the
 * behavior a human means by "run at 2:30 AM" on a day where that wall-clock
 * time is weird. Month-length and leap-year correctness fall out of using
 * LocalDate's own arithmetic (plusMonths/plusDays) instead of hand-rolled
 * calendar math.
 */
public final class CronExpression {

    private static final int MAX_YEARS_AHEAD = 4;
    private static final int MAX_ITERATIONS = 100_000; // defensive backstop; the year cap should always trip first

    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(
            Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
            Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
            Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

    private static final Map<String, Integer> DOW_NAMES = Map.ofEntries(
            Map.entry("SUN", 0), Map.entry("MON", 1), Map.entry("TUE", 2), Map.entry("WED", 3),
            Map.entry("THU", 4), Map.entry("FRI", 5), Map.entry("SAT", 6));

    private final boolean hasSeconds;
    private final FieldSpec seconds;
    private final FieldSpec minutes;
    private final FieldSpec hours;
    private final FieldSpec dayOfMonth;
    private final FieldSpec month;
    private final FieldSpec dayOfWeek;
    private final String source;

    private CronExpression(boolean hasSeconds, FieldSpec seconds, FieldSpec minutes, FieldSpec hours,
                            FieldSpec dayOfMonth, FieldSpec month, FieldSpec dayOfWeek, String source) {
        this.hasSeconds = hasSeconds;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.dayOfMonth = dayOfMonth;
        this.month = month;
        this.dayOfWeek = dayOfWeek;
        this.source = source;
    }

    public static CronExpression parse(String expression) {
        String text = expression.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Cron expression must not be blank");
        }
        if (text.startsWith("@")) {
            text = expandShorthand(text);
        }
        String[] fields = text.split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException(
                    "Cron expression must have 5 fields (m h dom mon dow) or 6 (s m h dom mon dow), got "
                            + fields.length + ": '" + expression + "'");
        }
        boolean hasSeconds = fields.length == 6;
        int i = 0;
        FieldSpec secondsField = hasSeconds ? parseField(fields[i++], 0, 59, null, 0) : FieldSpec.singleton(0);
        FieldSpec minutesField = parseField(fields[i++], 0, 59, null, 0);
        FieldSpec hoursField = parseField(fields[i++], 0, 23, null, 0);
        FieldSpec domField = parseField(fields[i++], 1, 31, null, 0);
        FieldSpec monthField = parseField(fields[i++], 1, 12, MONTH_NAMES, 0);
        FieldSpec dowField = parseField(fields[i], 0, 7, DOW_NAMES, 7);
        return new CronExpression(hasSeconds, secondsField, minutesField, hoursField, domField, monthField, dowField, expression);
    }

    private static String expandShorthand(String text) {
        return switch (text) {
            case "@hourly" -> "0 * * * *";
            case "@daily", "@midnight" -> "0 0 * * *";
            case "@weekly" -> "0 0 * * 0";
            case "@monthly" -> "0 0 1 * *";
            default -> throw new IllegalArgumentException("Unknown cron shorthand: " + text);
        };
    }

    /** The next ZonedDateTime strictly after `after` (same zone) that matches this expression. */
    public ZonedDateTime nextFireTime(ZonedDateTime after) {
        int startYear = after.getYear();
        LocalDateTime candidate = hasSeconds
                ? after.toLocalDateTime().plusSeconds(1).withNano(0)
                : after.toLocalDateTime().plusMinutes(1).withSecond(0).withNano(0);

        for (int iterations = 0; ; iterations++) {
            if (candidate.getYear() > startYear + MAX_YEARS_AHEAD) {
                throw new CronScheduleException("No fire time found for '" + source + "' within "
                        + MAX_YEARS_AHEAD + " years after " + after);
            }
            if (iterations > MAX_ITERATIONS) {
                throw new CronScheduleException("Exceeded iteration bound searching for the next fire time of '" + source + "'");
            }
            if (!month.contains(candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!dayMatches(candidate)) {
                candidate = candidate.plusDays(1).withHour(0).withMinute(0).withSecond(0);
                continue;
            }
            if (!hours.contains(candidate.getHour())) {
                candidate = candidate.plusHours(1).withMinute(0).withSecond(0);
                continue;
            }
            if (!minutes.contains(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1).withSecond(0);
                continue;
            }
            if (hasSeconds && !seconds.contains(candidate.getSecond())) {
                candidate = candidate.plusSeconds(1);
                continue;
            }
            return ZonedDateTime.of(candidate, after.getZone());
        }
    }

    private boolean dayMatches(LocalDateTime candidate) {
        boolean domRestricted = !dayOfMonth.wildcard();
        boolean dowRestricted = !dayOfWeek.wildcard();
        if (!domRestricted && !dowRestricted) {
            return true;
        }
        // ISO DayOfWeek is 1=Monday..7=Sunday; cron is 0=Sunday..6=Saturday. %7 maps 7->0 and leaves 1-6 as-is.
        boolean dowMatch = dayOfWeek.contains(candidate.getDayOfWeek().getValue() % 7);
        if (!domRestricted) {
            return dowMatch;
        }
        boolean domMatch = dayOfMonth.contains(candidate.getDayOfMonth());
        if (!dowRestricted) {
            return domMatch;
        }
        return domMatch || dowMatch; // classic cron: both restricted means either can satisfy the day
    }

    public boolean hasSeconds() {
        return hasSeconds;
    }

    @Override
    public String toString() {
        return source;
    }

    /** One field's set of allowed values, plus whether the original text was a bare "*" (needed for dom/dow OR-semantics). */
    private record FieldSpec(BitSet allowed, boolean wildcard) {
        boolean contains(int value) {
            return allowed.get(value);
        }

        static FieldSpec singleton(int value) {
            BitSet b = new BitSet();
            b.set(value);
            return new FieldSpec(b, false);
        }
    }

    private static FieldSpec parseField(String field, int min, int max, Map<String, Integer> names, int wrapModulus) {
        boolean wildcard = field.equals("*");
        BitSet bits = new BitSet(max + 1);
        for (String part : field.split(",")) {
            parsePart(part, min, max, names, wrapModulus, bits);
        }
        return new FieldSpec(bits, wildcard);
    }

    private static void parsePart(String part, int min, int max, Map<String, Integer> names, int wrapModulus, BitSet bits) {
        String rangeText = part;
        int step = 1;
        int slash = part.indexOf('/');
        if (slash >= 0) {
            step = Integer.parseInt(part.substring(slash + 1).strip());
            if (step <= 0) {
                throw new IllegalArgumentException("Cron step must be positive, got '" + part + "'");
            }
            rangeText = part.substring(0, slash);
        }

        int start;
        int end;
        int dash = rangeText.indexOf('-');
        if (rangeText.equals("*")) {
            start = min;
            end = max;
        } else if (dash > 0) {
            start = resolveValue(rangeText.substring(0, dash), names);
            end = resolveValue(rangeText.substring(dash + 1), names);
        } else {
            start = resolveValue(rangeText, names);
            end = slash >= 0 ? max : start; // bare "value/step" (no dash) means value..max stepped, per common cron dialects
        }

        if (start < min || start > max || end < min || end > max || start > end) {
            throw new IllegalArgumentException("Cron field value out of range [" + min + "," + max + "] in '" + part + "'");
        }
        for (int v = start; v <= end; v += step) {
            bits.set(wrapModulus > 0 ? v % wrapModulus : v);
        }
    }

    private static int resolveValue(String token, Map<String, Integer> names) {
        String t = token.strip().toUpperCase(Locale.ROOT);
        if (names != null && names.containsKey(t)) {
            return names.get(t);
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid cron field value: '" + token + "'", e);
        }
    }
}
