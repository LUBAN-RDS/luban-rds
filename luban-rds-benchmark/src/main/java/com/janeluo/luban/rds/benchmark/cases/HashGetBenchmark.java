package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.core.AbstractBenchmark;
import redis.clients.jedis.Jedis;

public class HashGetBenchmark extends AbstractBenchmark {

    @Override
    public String getName() {
        return "HGET";
    }

    @Override
    protected void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_hash_" + threadId;
        jedis.hget(key, "field_" + iteration);
    }
}
