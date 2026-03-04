package com.janeluo.luban.rds.benchmark;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import com.janeluo.luban.rds.benchmark.core.BenchmarkRunner;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BenchmarkRunnerTest {

    @Test
    public void testRunner() {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setThreads(2);
        config.setTotalOperations(10);
        
        // Mock benchmark
        AbstractBenchmark mockBenchmark = new AbstractBenchmark() {
            @Override
            public String getName() {
                return "MockBench";
            }

            @Override
            protected Jedis createJedis(BenchmarkConfig config) {
                Jedis mockJedis = mock(Jedis.class);
                when(mockJedis.ping()).thenReturn("PONG");
                return mockJedis;
            }

            @Override
            protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
                // do nothing
            }
        };

        BenchmarkRunner runner = new BenchmarkRunner(config);
        runner.addBenchmark(mockBenchmark);
        runner.run();
        
        // No exception means pass. 
        // Ideally we should check results but Runner prints to stdout.
        // We can manually run benchmark.run() to check result.
    }
    
    @Test
    public void testBenchmarkResult() throws Exception {
        BenchmarkConfig config = new BenchmarkConfig();
        config.setThreads(2);
        config.setTotalOperations(20);
        
        AbstractBenchmark mockBenchmark = new AbstractBenchmark() {
            @Override
            public String getName() {
                return "MockBench";
            }

            @Override
            protected Jedis createJedis(BenchmarkConfig config) {
                Jedis mockJedis = mock(Jedis.class);
                when(mockJedis.ping()).thenReturn("PONG");
                return mockJedis;
            }

            @Override
            protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
                // do nothing
            }
        };
        
        mockBenchmark.setup(config);
        BenchmarkResult result = mockBenchmark.run(config);
        
        assertEquals(20, result.getOperations());
        assertEquals(0, result.getErrorCount());
    }
}
