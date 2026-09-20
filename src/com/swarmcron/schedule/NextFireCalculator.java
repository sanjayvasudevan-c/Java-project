package com.swarmcron.schedule;

import com.swarmcron.config.JobSpec;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges JobSpec (schedule stored as a raw cron string) to CronExpression,
 * caching parsed expressions by their source text so repeatedly rescheduling
 * the same job (or several jobs sharing an identical schedule string) never
 * re-parses it.
 */
public final class NextFireCalculator {

    private final ZoneId zone;
    private final Map<String, CronExpression> cache = new ConcurrentHashMap<>();

    public NextFireCalculator(ZoneId zone) {
        this.zone = zone;
    }

    public ZonedDateTime nextFireTime(JobSpec spec, ZonedDateTime after) {
        CronExpression expr = cache.computeIfAbsent(spec.schedule(), CronExpression::parse);
        return expr.nextFireTime(after.withZoneSameInstant(zone));
    }
}
