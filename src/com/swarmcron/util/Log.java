package com.swarmcron.util;

import java.time.Instant;

/**
 * Process-wide leveled logger. INFO by default; --debug on the CLI flips the
 * threshold to DEBUG (see Main). Safe to call from any thread: the only
 * shared state is the volatile threshold field.
 */
public final class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    private static volatile Level threshold = Level.INFO;

    private Log() {}

    public static void setDebug(boolean debug) {
        threshold = debug ? Level.DEBUG : Level.INFO;
    }

    public static boolean isDebug() {
        return threshold == Level.DEBUG;
    }

    public static void debug(String tag, String fmt, Object... args) {
        log(Level.DEBUG, tag, fmt, args);
    }

    public static void info(String tag, String fmt, Object... args) {
        log(Level.INFO, tag, fmt, args);
    }

    public static void warn(String tag, String fmt, Object... args) {
        log(Level.WARN, tag, fmt, args);
    }

    public static void error(String tag, String fmt, Object... args) {
        log(Level.ERROR, tag, fmt, args);
    }

    private static void log(Level level, String tag, String fmt, Object... args) {
        if (level.ordinal() < threshold.ordinal()) {
            return;
        }
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        System.out.println(Instant.now() + " [" + level + "] [" + tag + "] " + msg);
    }
}
