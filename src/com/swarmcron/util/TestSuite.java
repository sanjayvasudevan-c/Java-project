package com.swarmcron.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny in-process test runner. A suite is a named list of cases; run() executes
 * each, catching Throwable so one failing case doesn't stop the others, and
 * prints a PASS/FAIL line per case plus a summary. No JUnit, no reflection scan.
 */
public final class TestSuite {

    @FunctionalInterface
    public interface Case {
        void run() throws Exception;
    }

    private final String name;
    private final List<String> caseNames = new ArrayList<>();
    private final List<Case> cases = new ArrayList<>();

    public TestSuite(String name) {
        this.name = name;
    }

    public TestSuite test(String caseName, Case c) {
        caseNames.add(caseName);
        cases.add(c);
        return this;
    }

    /** Returns true iff every case passed. */
    public boolean run() {
        int passed = 0;
        for (int i = 0; i < cases.size(); i++) {
            String caseName = caseNames.get(i);
            try {
                cases.get(i).run();
                System.out.println("  [PASS] " + name + " :: " + caseName);
                passed++;
            } catch (Throwable t) {
                System.out.println("  [FAIL] " + name + " :: " + caseName + " -> " + t);
            }
        }
        System.out.println(name + ": " + passed + "/" + cases.size() + " passed");
        return passed == cases.size();
    }
}
