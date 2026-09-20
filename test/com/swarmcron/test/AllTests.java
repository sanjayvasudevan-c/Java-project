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
        ok &= MembershipTest.run();
        ok &= GossipBufferTest.run();
        ok &= SwimSimTest.run();
        ok &= VectorClockTest.run();
        ok &= HybridClockTest.run();
        ok &= MergeEngineTest.run();
        ok &= JobRegistryTest.run();
        ok &= TcpSyncChannelTest.run();
        ok &= JobRegistrySimTest.run();
        ok &= HashRingTest.run();
        ok &= RingManagerSimTest.run();
        ok &= CronExpressionTest.run();
        ok &= TimerWheelTest.run();
        ok &= RaftLiteTest.run();
        ok &= SymmetricPartitionSimTest.run();
        ok &= ProcessRunnerTest.run();
        ok &= WriteAheadLogTest.run();
        ok &= JobExecutorSimTest.run();

        if (ok) {
            System.out.println("ALL TESTS PASSED");
        } else {
            System.out.println("SOME TESTS FAILED");
            System.exit(1);
        }
    }
}
