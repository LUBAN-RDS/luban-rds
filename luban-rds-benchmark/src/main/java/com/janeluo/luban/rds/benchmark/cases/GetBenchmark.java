package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import redis.clients.jedis.Jedis;

public class GetBenchmark extends AbstractBenchmark {

    @Override
    public String getName() {
        return "GET";
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_set_" + threadId + "_" + iteration;
        jedis.get(key);
    }
}
