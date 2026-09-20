package com.swarmcron.schedule;

/** Thrown when the next fire time can't be found within CronExpression's search bound (4 years) -- an unsatisfiable expression, not a bug in the caller. */
public class CronScheduleException extends RuntimeException {
    public CronScheduleException(String message) {
        super(message);
    }
}
