package com.swarmcron.util;

import java.util.Objects;

/**
 * Minimal assertion library for the hand-rolled test harness (see TestSuite).
 * No JUnit: this is the entirety of the assertion vocabulary the project uses.
 */
public final class Assert {

    private Assert() {}

    public static void that(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void equals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=<" + expected + "> actual=<" + actual + ">)");
        }
    }

    public static void fail(String message) {
        throw new AssertionError(message);
    }
}
