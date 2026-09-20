package com.swarmcron.test;

/**
 * Aggregates every TestSuite in the project. Add a call here whenever a new
 * *Test class is added. run-tests.sh compiles and runs this class.
 */
public final class AllTests {

    public static void main(String[] args) {
        boolean ok = true;
        ok &= JsonTest.run();
        ok &= ConfigParserTest.run();
        ok &= CodecTest.run();
        ok &= SimTransportTest.run();
        ok &= UdpTransportTest.run();

        if (ok) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println("SOME TESTS FAILED");
            System.exit(1);
        }
    }
}
