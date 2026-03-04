package com.janeluo.luban.rds.server;

import org.junit.Test;
import java.util.concurrent.TimeUnit;

public class MonitorBenchmarkTest {

    @Test
    public void benchmarkSubmitOverhead() {
        MonitorManager manager = MonitorManager.getInstance();
        // Warmup
        for (int i = 0; i < 10000; i++) {
            manager.submit(0, "127.0.0.1:1234", "PING", null);
        }

        int iterations = 1_000_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            manager.submit(0, "127.0.0.1:1234", "PING", null);
        }
        long end = System.nanoTime();
        
        long totalNs = end - start;
        double nsPerOp = (double) totalNs / iterations;
        
        System.out.println("MonitorManager.submit overhead:");
        System.out.println("Total time for " + iterations + " ops: " + TimeUnit.NANOSECONDS.toMillis(totalNs) + " ms");
        System.out.println("Time per op: " + nsPerOp + " ns");
        
        // Requirement: < 50us per 10,000 commands => 5ns per command.
        // My implementation uses ConcurrentLinkedQueue.offer which creates a Node.
        // Allocation + pointer CAS might be around 10-50ns.
        // 5ns is extremely tight (basically a volatile write or two).
        // Let's see what we get.
        // If it's higher, we might need Disruptor or ArrayBlockingQueue (but ABQ is slower due to lock).
        // ConcurrentLinkedQueue is lock-free but allocates Node.
    }
}
