package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import redis.clients.jedis.Jedis;

public class IncrBenchmark extends AbstractBenchmark {

    @Override
    public String getName() {
        return "INCR";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        super.setup(config);
        // Pre-populate keys to avoid errors if strict? Usually INCR works on non-existent keys (treats as 0)
        // But let's init them to be safe/consistent with old benchmark
        try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
            for (int t = 0; t < config.getThreads(); t++) {
                jedis.set(config.getKeyPrefix() + "_incr_" + t, "0");
            }
        }
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_incr_" + threadId;
        jedis.incr(key);
    }
}
